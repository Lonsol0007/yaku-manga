import pytest

from yaku_translate.manifest import ModelManifest, ModelPack, PackConfig


def _minimal_pack(**overrides) -> dict:
    file_entry = {"name": "f", "url": "u", "size_bytes": 3, "sha256": "ab"}
    raw = {
        "id": "p",
        "name": "P",
        "detector": file_entry,
        "recognizer_encoder": file_entry,
        "recognizer_decoder": file_entry,
        "recognizer_vocab": file_entry,
        "translator_encoder": file_entry,
        "translator_decoder": file_entry,
        "translator_vocab": file_entry,
    }
    raw.update(overrides)
    return raw


def test_pack_round_trips_through_the_wire_format():
    pack = ModelPack.from_dict(_minimal_pack(config={"detector_kind": "comic"}))
    again = ModelPack.from_dict(pack.to_dict())
    assert again == pack
    assert again.config.detector_kind == "comic"


def test_snake_case_names_are_the_contract_with_published_packs():
    raw = ModelPack.from_dict(_minimal_pack()).to_dict()
    for key in ("recognizer_encoder", "translator_vocab", "source_languages"):
        assert key in raw
    assert "detector_max_box_area_ratio" in raw["config"]


def test_unknown_config_keys_are_ignored_rather_than_fatal():
    # A newer pack must still load on an older build.
    config = PackConfig.from_dict({"detector_kind": "comic", "invented_by_a_later_version": 7})
    assert config.detector_kind == "comic"


def test_a_pack_missing_a_model_is_rejected_by_name():
    incomplete = _minimal_pack()
    del incomplete["translator_decoder"]
    with pytest.raises(ValueError, match="translator_decoder"):
        ModelPack.from_dict(incomplete)


def test_total_bytes_counts_every_file():
    assert ModelPack.from_dict(_minimal_pack()).total_bytes == 21


def test_manifest_parses_its_packs():
    manifest = ModelManifest.from_dict({"version": 1, "packs": [_minimal_pack()]})
    assert manifest.version == 1
    assert manifest.packs[0].id == "p"
