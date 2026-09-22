"""The values carried through the pipeline, from a detected box to a finished page.

A direct port of `yaku.translation.model`. The Kotlin keeps its geometry in a plain data
class rather than `android.graphics.Rect` so detection and recognition stay testable off
device; here that choice costs nothing and is kept so the two implementations can be read
side by side.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum


@dataclass(frozen=True)
class BoxF:
    """A rectangle in the coordinate space of the *original*, unscaled page image."""

    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top

    @property
    def area(self) -> float:
        return self.width * self.height

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2.0

    @property
    def center_y(self) -> float:
        return (self.top + self.bottom) / 2.0

    @property
    def thickness(self) -> float:
        """The short side: roughly the size of the glyphs, whichever way the line runs.

        A vertical column is as wide as one character and as long as the sentence; a
        horizontal line is the other way round. The short side is the one that means the
        same thing in both.
        """
        return min(self.width, self.height)

    @property
    def is_vertical(self) -> bool:
        """True when this box is taller than it is wide, the usual shape of vertical Japanese."""
        return self.height > self.width * 1.2

    def expand(self, ratio: float, max_width: float, max_height: float) -> BoxF:
        """Grows the box by ``ratio`` of its *shorter* side on all four edges.

        Scaling each axis by its own length pads a tall vertical column far more vertically
        than horizontally - 0.15 on a 95x640 column adds 14px of side margin but 96px top and
        bottom, which runs past the text and into the curved ends of the speech bubble. The
        renderer then paints over that, erasing part of the bubble outline. A single distance
        keeps the margin even and proportional to the lettering.
        """
        margin = min(self.width, self.height) * ratio
        return BoxF(
            left=max(self.left - margin, 0.0),
            top=max(self.top - margin, 0.0),
            right=min(self.right + margin, max_width),
            bottom=min(self.bottom + margin, max_height),
        )

    def union(self, other: BoxF) -> BoxF:
        return BoxF(
            left=min(self.left, other.left),
            top=min(self.top, other.top),
            right=max(self.right, other.right),
            bottom=max(self.bottom, other.bottom),
        )

    def intersects(self, other: BoxF, slop_x: float = 0.0, slop_y: float = 0.0) -> bool:
        return (
            self.left - slop_x < other.right
            and other.left < self.right + slop_x
            and self.top - slop_y < other.bottom
            and other.top < self.bottom + slop_y
        )


@dataclass(frozen=True)
class TextBlock:
    """One detected region of text on a page, carried through the whole pipeline.

    ``source_text`` is filled in by the recognizer and ``translated_text`` by the translator;
    both are None while the corresponding stage has not run (or failed for this block alone,
    which is not fatal - the rest of the page still renders).
    """

    box: BoxF
    confidence: float
    source_text: str | None = None
    translated_text: str | None = None
    bubble: BoxF | None = None
    """The speech balloon this text sits in, when the detector could say.

    This is where the translation belongs, and it is not something that can be worked out
    from the text's own box: the box says where the lettering is, and the balloon extends
    well past it in every direction. A detector trained on comics reports both.
    """

    @property
    def is_translated(self) -> bool:
        return bool(self.translated_text and self.translated_text.strip())

    def copy(self, **changes) -> TextBlock:
        return replace(self, **changes)


class TranslationLanguage(Enum):
    """Language identifiers used across the pipeline.

    These map onto the codes the translation model was trained with; ``model_code`` is what
    actually gets fed to the tokenizer (NLLB-style models want ``jpn_Jpan``, opus-MT style
    models want ``ja``).
    """

    JAPANESE = ("ja", "jpn_Jpan", "Japanese")
    KOREAN = ("ko", "kor_Hang", "Korean")
    CHINESE_SIMPLIFIED = ("zh", "zho_Hans", "Chinese (Simplified)")
    CHINESE_TRADITIONAL = ("zh-Hant", "zho_Hant", "Chinese (Traditional)")
    ENGLISH = ("en", "eng_Latn", "English")
    SPANISH = ("es", "spa_Latn", "Spanish")
    FRENCH = ("fr", "fra_Latn", "French")
    GERMAN = ("de", "deu_Latn", "German")
    PORTUGUESE = ("pt", "por_Latn", "Portuguese")
    RUSSIAN = ("ru", "rus_Cyrl", "Russian")

    def __init__(self, code: str, model_code: str, display_name: str) -> None:
        self.code = code
        self.model_code = model_code
        self.display_name = display_name

    @classmethod
    def from_code(cls, code: str) -> TranslationLanguage | None:
        return next((lang for lang in cls if lang.code == code), None)


@dataclass(frozen=True)
class PageTranslation:
    """The result of running the full pipeline over a single page image."""

    page_width: int
    page_height: int
    blocks: list[TextBlock]
    source_language: TranslationLanguage
    target_language: TranslationLanguage
    elapsed_millis: int

    @property
    def has_content(self) -> bool:
        return any(block.is_translated for block in self.blocks)

    def to_dict(self) -> dict:
        """A JSON-friendly view, for `--json` output and for diffing runs against each other."""
        return {
            "page_width": self.page_width,
            "page_height": self.page_height,
            "source_language": self.source_language.code,
            "target_language": self.target_language.code,
            "elapsed_millis": self.elapsed_millis,
            "blocks": [
                {
                    "box": [b.box.left, b.box.top, b.box.right, b.box.bottom],
                    "confidence": b.confidence,
                    "source_text": b.source_text,
                    "translated_text": b.translated_text,
                    "bubble": None
                    if b.bubble is None
                    else [b.bubble.left, b.bubble.top, b.bubble.right, b.bubble.bottom],
                }
                for b in self.blocks
            ],
        }


class TranslationProgress:
    """Progress reporting so a caller can show something while a page is being worked on.

    Kotlin models this as a sealed interface; the nested classes below are the same set of
    cases, and callers are expected to branch on type.
    """

    class Idle:
        pass

    class LoadingModels:
        pass

    class Detecting:
        pass

    @dataclass(frozen=True)
    class Recognizing:
        done: int
        total: int

    @dataclass(frozen=True)
    class Translating:
        done: int
        total: int

    @dataclass(frozen=True)
    class Done:
        result: PageTranslation

    @dataclass(frozen=True)
    class Failed:
        error: BaseException
