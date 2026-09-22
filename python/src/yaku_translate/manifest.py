"""Describes the ONNX assets a translation pack is made of.

A port of `yaku.translation.store.ModelManifest`. The JSON field names are the ones the
Android app writes and the published manifests use, so a `pack.json` written by either
implementation loads in the other unchanged - which is the point of porting this at all.

Kotlin gets these names from `@SerialName`; here each dataclass carries an explicit
``_FIELDS`` map and its own ``from_dict`` / ``to_dict``, because the wire format is a
contract with already-published packs rather than whatever a serializer would emit.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any

# --------------------------------------------------------------------------------------
# Pack configuration
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class PackConfig:
    """Per-pack knobs.

    Defaults match a DBNet detector + manga-ocr recogniser + NLLB translator, which is the
    combination the reference pack uses.
    """

    config_version: int = 0
    """Which generation of tuning the detector settings below belong to.

    A descriptor is written once at download time and read back verbatim forever, so without
    a marker there is no way to tell a pack that was built with deliberate settings from one
    carrying values that were simply the defaults of the day. Packs older than
    ``TUNED_DETECTOR`` have their detector trio replaced on load.
    """

    # -- detector
    detector_kind: str = "dbnet"
    """Which sort of detector the pack ships.

    ``dbnet`` returns a probability map that has to be thresholded and grouped into boxes,
    and knows nothing about balloons. ``comic`` returns boxes and speech balloons directly,
    which removes the grouping heuristics and the guesswork about where a balloon is.
    """

    detector_min_confidence: float = 0.5
    """Detections below this score are dropped. Only used by a ``comic`` detector."""

    detector_input_size: int = 960
    detector_input_name: str = "input"
    detector_output_name: str = "output"

    detector_threshold: float = 0.15
    """Deliberately low. Swept against a page with known text: at 0.3 the detector found
    0-2 of 4 speech bubbles, at 0.15 it found all four. Manga lettering sits on white with
    thin strokes, and a DBNet trained on documents reports it far less confidently than it
    reports printed paragraphs."""

    detector_box_expand: float = 0.15
    """0.08 clipped the last characters off horizontal lines; 0.15 keeps them."""

    detector_min_area_ratio: float = 0.00015

    detector_max_box_area_ratio: float = 0.12
    """Largest share of the page one text box may occupy.

    A page of real artwork produces hundreds of blobs, and merging can chain across all of
    them: measured on a scanned page, one box came back as the whole 1336x1920 sheet and was
    duly given a sentence of its own, painted over everything. No line of dialogue is a
    seventh of a page, so a box that large is a runaway rather than text.
    """

    detector_merge_slop: float = 3.5
    """How far apart two boxes may sit and still be merged, as a multiple of the smaller box.

    Detectors emit one blob per glyph cluster, not per bubble, and Japanese is usually set
    vertically, so a line of dialogue arrives as a stack of separate boxes with a full
    character of leading between them. Feeding those to the recogniser one glyph at a time
    produces plausible single words, costs a full inference pass each, and loses the context
    that makes the translation mean anything.
    """

    detector_merge_max_size_ratio: float = 3.0
    """Boxes whose heights differ by more than this are never merged."""

    detector_mean: tuple[float, ...] = (0.485, 0.456, 0.406)
    detector_std: tuple[float, ...] = (0.229, 0.224, 0.225)

    # -- recogniser
    recognizer_input_size: int = 224
    recognizer_max_tokens: int = 64
    recognizer_mean: tuple[float, ...] = (0.5, 0.5, 0.5)
    recognizer_std: tuple[float, ...] = (0.5, 0.5, 0.5)

    # -- translator
    translator_max_tokens: int = 128
    translator_bos_token: str = "<s>"
    translator_eos_token: str = "</s>"
    translator_pad_token: str = "<pad>"
    translator_unk_token: str = "<unk>"

    translator_uses_language_token: bool = True
    """NLLB-style models need the target language as the first decoder token."""

    translator_decoder_start_token: str | None = None
    """Token the decoder is primed with.

    NLLB and M2M start from the EOS token, which is the fallback; Marian/opus-MT starts from
    the pad token instead. Priming with the wrong one yields fluent nonsense rather than an
    obvious failure, so it is worth setting explicitly.
    """

    TUNED_DETECTOR = 1
    """Generation that carries the swept detector settings. Raise this, and update the
    defaults above, to retune every installed pack on the next load."""

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> PackConfig:
        known = set(cls.__dataclass_fields__)
        values: dict[str, Any] = {k: v for k, v in raw.items() if k in known}
        for key in ("detector_mean", "detector_std", "recognizer_mean", "recognizer_std"):
            if key in values and values[key] is not None:
                values[key] = tuple(float(v) for v in values[key])
        return cls(**values)

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        for name in self.__dataclass_fields__:
            value = getattr(self, name)
            out[name] = list(value) if isinstance(value, tuple) else value
        return out


# --------------------------------------------------------------------------------------
# Pack and manifest
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ModelFile:
    name: str
    """File name on disk, unique within a pack."""
    url: str
    size_bytes: int = 0
    sha256: str = ""
    """Lowercase hex SHA-256. Downloads that do not match are discarded."""

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> ModelFile:
        return cls(
            name=raw["name"],
            url=raw.get("url", ""),
            size_bytes=int(raw.get("size_bytes", 0)),
            sha256=raw.get("sha256", ""),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "url": self.url,
            "size_bytes": self.size_bytes,
            "sha256": self.sha256,
        }


@dataclass(frozen=True)
class ModelPack:
    id: str
    name: str
    detector: ModelFile
    recognizer_encoder: ModelFile
    recognizer_decoder: ModelFile
    recognizer_vocab: ModelFile
    translator_encoder: ModelFile
    translator_decoder: ModelFile
    translator_vocab: ModelFile
    description: str = ""
    source_languages: tuple[str, ...] = ()
    target_languages: tuple[str, ...] = ()
    config: PackConfig = field(default_factory=PackConfig)

    source: str = ""
    """Manifest this pack was offered by. Absent from manifests themselves - the repository
    stamps it in on fetch and stores it in the installed descriptor.

    Two sources can name a pack ``ja-en-base`` and mean different things, and packs install
    into a directory named after their id. Recording where one came from is what lets an
    install refuse to write over an unrelated pack that happens to share a name.
    """

    FILE_KEYS = (
        "detector",
        "recognizer_encoder",
        "recognizer_decoder",
        "recognizer_vocab",
        "translator_encoder",
        "translator_decoder",
        "translator_vocab",
    )

    @property
    def files(self) -> list[ModelFile]:
        return [getattr(self, key) for key in self.FILE_KEYS]

    @property
    def total_bytes(self) -> int:
        return sum(f.size_bytes for f in self.files)

    def replace(self, **changes) -> ModelPack:
        return replace(self, **changes)

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> ModelPack:
        missing = [key for key in cls.FILE_KEYS if key not in raw]
        if missing:
            raise ValueError(f"Pack '{raw.get('id', '?')}' is missing: {', '.join(missing)}")
        return cls(
            id=raw["id"],
            name=raw.get("name", raw["id"]),
            description=raw.get("description", ""),
            source_languages=tuple(raw.get("source_languages", ())),
            target_languages=tuple(raw.get("target_languages", ())),
            config=PackConfig.from_dict(raw.get("config", {}) or {}),
            source=raw.get("source", ""),
            **{key: ModelFile.from_dict(raw[key]) for key in cls.FILE_KEYS},
        )

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "source_languages": list(self.source_languages),
            "target_languages": list(self.target_languages),
        }
        out.update({key: getattr(self, key).to_dict() for key in self.FILE_KEYS})
        out["config"] = self.config.to_dict()
        out["source"] = self.source
        return out


@dataclass(frozen=True)
class ModelManifest:
    version: int = 1
    packs: tuple[ModelPack, ...] = ()

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> ModelManifest:
        return cls(
            version=int(raw.get("version", 1)),
            packs=tuple(ModelPack.from_dict(p) for p in raw.get("packs", ()) or ()),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"version": self.version, "packs": [p.to_dict() for p in self.packs]}
