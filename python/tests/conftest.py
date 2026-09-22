from __future__ import annotations

import sys
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))

import fakepack
from yaku_translate.manifest import ModelPack
from yaku_translate.repository import ModelRepository


@pytest.fixture(scope="session")
def pack_source(tmp_path_factory) -> tuple[Path, dict]:
    """A built pack plus a manifest, served from `file://` URLs."""
    directory = tmp_path_factory.mktemp("pack-source")
    pack = fakepack.build_pack(directory)
    fakepack.write_manifest(directory, pack)
    return directory, pack


@pytest.fixture
def repository(tmp_path) -> ModelRepository:
    return ModelRepository(tmp_path / "models")


@pytest.fixture
def installed_pack(pack_source, repository) -> ModelPack:
    directory, raw = pack_source
    manifest_url = (directory / "manifest.json").resolve().as_uri()
    results = repository.fetch_all([manifest_url])
    assert not results.failures, results.failures
    repository.download(results.packs[0])
    return repository.installed_pack(raw["id"])


@pytest.fixture
def page() -> Image.Image:
    """A page with one speech balloon holding a column of dark 'lettering'."""
    image = Image.new("RGB", (640, 960), (255, 255, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, 639, 959], outline=(30, 30, 30), width=4)  # panel border
    draw.ellipse([120, 180, 520, 620], fill=(255, 255, 255), outline=(0, 0, 0), width=5)
    draw.rectangle([300, 280, 340, 520], fill=(0, 0, 0))  # the lettering
    return image
