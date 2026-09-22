import json

import pytest

from yaku_translate.manifest import ModelPack, PackConfig
from yaku_translate.repository import PACK_DESCRIPTOR, default_models_root


def test_fetch_stamps_the_source_onto_every_pack(pack_source, repository):
    directory, _ = pack_source
    url = (directory / "manifest.json").resolve().as_uri()
    manifest = repository.fetch_manifest(url)
    assert manifest.packs[0].source == url


def test_download_verifies_installs_and_writes_a_descriptor(pack_source, repository):
    directory, _raw = pack_source
    url = (directory / "manifest.json").resolve().as_uri()
    pack = repository.fetch_manifest(url).packs[0]

    seen = []
    repository.download(pack, seen.append)

    assert repository.is_installed(pack)
    assert (repository.pack_dir(pack.id) / PACK_DESCRIPTOR).exists()
    assert seen[-1].current_file is None and seen[-1].fraction == 1.0
    assert repository.installed_pack(pack.id).id == pack.id
    assert repository.installed_bytes() > 0


def test_a_corrupt_download_is_discarded_rather_than_installed(pack_source, repository):
    directory, _ = pack_source
    url = (directory / "manifest.json").resolve().as_uri()
    pack = repository.fetch_manifest(url).packs[0]
    wrong = pack.replace(detector=pack.detector.__class__(**{**pack.detector.to_dict(), "sha256": "00" * 32}))

    with pytest.raises(RuntimeError, match="Checksum mismatch"):
        repository.download(wrong)
    assert not (repository.pack_dir(pack.id) / pack.detector.name).exists()


def test_a_same_named_pack_from_another_source_is_refused(pack_source, repository):
    directory, _ = pack_source
    url = (directory / "manifest.json").resolve().as_uri()
    pack = repository.fetch_manifest(url).packs[0]
    repository.download(pack)

    impostor = pack.replace(source="https://somewhere.else/manifest.json")
    with pytest.raises(RuntimeError, match="already installed from"):
        repository.download(impostor)


def test_one_dead_source_does_not_hide_the_others(pack_source, repository):
    directory, _ = pack_source
    good = (directory / "manifest.json").resolve().as_uri()
    bad = (directory / "nothing-here.json").resolve().as_uri()

    results = repository.fetch_all([good, bad, good])
    assert [p.id for p in results.packs] == ["test-pack"]
    assert results.failures.get(bad)


def test_html_from_a_captive_portal_is_named_as_such(repository, tmp_path):
    page = tmp_path / "portal.json"
    page.write_text("<html><body>Sign in</body></html>", encoding="utf-8")
    with pytest.raises(RuntimeError, match="web page instead of the manifest"):
        repository.fetch_manifest(page.resolve().as_uri())


def test_an_unreadable_descriptor_is_reported_not_silently_hidden(repository, caplog):
    directory = repository.pack_dir("broken")
    directory.mkdir(parents=True)
    (directory / PACK_DESCRIPTOR).write_text("{not json", encoding="utf-8")
    assert repository.installed_pack("broken") is None
    assert "Unreadable pack descriptor" in caplog.text


def test_packs_predating_the_tuning_have_their_detector_trio_replaced(repository, tmp_path):
    directory = repository.pack_dir("old")
    directory.mkdir(parents=True)
    entry = {"name": "f", "url": "u", "size_bytes": 1, "sha256": "ab"}
    raw = {
        "id": "old",
        "name": "Old",
        **dict.fromkeys(ModelPack.FILE_KEYS, entry),
        "config": {"config_version": 0, "detector_threshold": 0.9, "detector_merge_slop": 1.0},
    }
    (directory / PACK_DESCRIPTOR).write_text(json.dumps(raw), encoding="utf-8")

    tuned = repository.installed_pack("old")
    assert tuned.config.detector_threshold == PackConfig().detector_threshold
    assert tuned.config.detector_merge_slop == PackConfig().detector_merge_slop
    assert tuned.config.config_version == PackConfig.TUNED_DETECTOR


def test_a_pack_that_sets_its_config_version_is_left_as_its_author_built_it(repository):
    directory = repository.pack_dir("deliberate")
    directory.mkdir(parents=True)
    entry = {"name": "f", "url": "u", "size_bytes": 1, "sha256": "ab"}
    raw = {
        "id": "deliberate",
        "name": "D",
        **dict.fromkeys(ModelPack.FILE_KEYS, entry),
        "config": {"config_version": 1, "detector_threshold": 0.9},
    }
    (directory / PACK_DESCRIPTOR).write_text(json.dumps(raw), encoding="utf-8")
    assert repository.installed_pack("deliberate").config.detector_threshold == 0.9


def test_delete_removes_the_pack(pack_source, repository):
    directory, _ = pack_source
    pack = repository.fetch_manifest((directory / "manifest.json").resolve().as_uri()).packs[0]
    repository.download(pack)
    repository.delete(pack.id)
    assert not repository.is_installed(pack)
    assert repository.installed_packs() == []


def test_models_root_honours_the_environment(monkeypatch, tmp_path):
    monkeypatch.setenv("YAKU_TRANSLATE_HOME", str(tmp_path))
    assert default_models_root() == tmp_path / "translation-models"
