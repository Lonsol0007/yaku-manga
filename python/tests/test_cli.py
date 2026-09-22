import json
import zipfile

import pytest
from PIL import Image, ImageDraw

import fakepack
from yaku_translate.cli import _human_bytes, _natural_key, main
from yaku_translate.render import default_font_path


@pytest.fixture
def source_url(pack_source) -> str:
    directory, _ = pack_source
    return (directory / "manifest.json").resolve().as_uri()


def _page(path):
    image = Image.new("RGB", (640, 960), (255, 255, 255))
    draw = ImageDraw.Draw(image)
    draw.ellipse([120, 180, 520, 620], fill=(255, 255, 255), outline=(0, 0, 0), width=5)
    draw.rectangle([300, 280, 340, 520], fill=(0, 0, 0))
    image.save(path)


def test_pages_sort_the_way_a_reader_expects():
    names = ["page10.png", "page2.png", "page1.png"]
    assert sorted(names, key=_natural_key) == ["page1.png", "page2.png", "page10.png"]


def test_human_bytes_reads_at_a_glance():
    assert _human_bytes(512) == "512B"
    assert _human_bytes(1536) == "1.5KB"
    assert _human_bytes(263 * 1024 * 1024) == "263.0MB"


def test_listing_shows_what_a_source_offers(tmp_path, source_url, capsys):
    assert main(["--models-root", str(tmp_path), "packs", "list", "--source", source_url]) == 0
    assert "test-pack" in capsys.readouterr().out


def test_install_then_installed_then_remove(tmp_path, source_url, capsys):
    root = ["--models-root", str(tmp_path)]
    assert main([*root, "packs", "install", "test-pack", "--source", source_url]) == 0
    assert main([*root, "packs", "installed"]) == 0
    assert "test-pack" in capsys.readouterr().out

    assert main([*root, "packs", "remove", "test-pack"]) == 0
    assert main([*root, "packs", "installed"]) == 0
    assert "No packs installed" in capsys.readouterr().out


def test_installing_a_pack_no_source_offers_says_what_is_on_offer(tmp_path, source_url, capsys):
    code = main(["--models-root", str(tmp_path), "packs", "install", "nope", "--source", source_url])
    assert code == 1
    assert "test-pack" in capsys.readouterr().err


def test_translating_before_installing_anything_explains_what_to_do(tmp_path, capsys):
    _page(tmp_path / "page.png")
    assert main(["--models-root", str(tmp_path / "models"), "page", str(tmp_path / "page.png")]) == 1
    assert "packs install" in capsys.readouterr().err


def test_an_unknown_language_is_rejected_by_name(tmp_path, source_url, capsys):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])
    _page(tmp_path / "page.png")
    assert main([*root, "page", str(tmp_path / "page.png"), "--to", "klingon"]) == 1
    assert "Unknown target language" in capsys.readouterr().err


@pytest.mark.skipif(default_font_path() is None, reason="no lettering font available")
def test_page_writes_an_image_and_the_blocks_as_json(tmp_path, source_url):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])
    _page(tmp_path / "page.png")

    out = tmp_path / "out.png"
    blocks = tmp_path / "out.json"
    assert main([*root, "page", str(tmp_path / "page.png"), "-o", str(out), "--json", str(blocks)]) == 0

    assert out.exists()
    payload = json.loads(blocks.read_text(encoding="utf-8"))
    assert payload["blocks"][0]["source_text"] == fakepack.RECOGNIZED_TEXT
    assert payload["blocks"][0]["translated_text"] == fakepack.TRANSLATED_TEXT


def test_no_render_runs_the_pipeline_without_writing_an_image(tmp_path, source_url):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])
    _page(tmp_path / "page.png")

    out = tmp_path / "out.png"
    blocks = tmp_path / "out.json"
    assert (
        main(
            [*root, "page", str(tmp_path / "page.png"), "-o", str(out), "--json", str(blocks), "--no-render"]
        )
        == 0
    )
    assert not out.exists()
    assert blocks.exists()


@pytest.mark.skipif(default_font_path() is None, reason="no lettering font available")
def test_batch_translates_a_folder_in_reading_order_and_packs_a_cbz(tmp_path, source_url):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])

    pages = tmp_path / "pages"
    pages.mkdir()
    for number in (1, 2, 10):
        _page(pages / f"page{number}.png")

    out = tmp_path / "out"
    cbz = tmp_path / "volume.cbz"
    assert main([*root, "batch", str(pages), "-o", str(out), "--cbz", str(cbz)]) == 0

    assert sorted(p.name for p in out.iterdir()) == ["page1.png", "page10.png", "page2.png"]
    with zipfile.ZipFile(cbz) as archive:
        assert archive.namelist() == ["page1.png", "page2.png", "page10.png"]


@pytest.mark.skipif(default_font_path() is None, reason="no lettering font available")
def test_batch_reads_a_cbz_as_well_as_a_folder(tmp_path, source_url):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])

    _page(tmp_path / "page1.png")
    volume = tmp_path / "in.cbz"
    with zipfile.ZipFile(volume, "w") as archive:
        archive.write(tmp_path / "page1.png", "page1.png")

    out = tmp_path / "out"
    assert main([*root, "batch", str(volume), "-o", str(out)]) == 0
    assert (out / "page1.png").exists()


def test_batch_over_an_empty_folder_says_so(tmp_path, source_url, capsys):
    root = ["--models-root", str(tmp_path / "models")]
    main([*root, "packs", "install", "test-pack", "--source", source_url])
    empty = tmp_path / "empty"
    empty.mkdir()
    assert main([*root, "batch", str(empty), "-o", str(tmp_path / "out")]) == 1
    assert "no images found" in capsys.readouterr().err
