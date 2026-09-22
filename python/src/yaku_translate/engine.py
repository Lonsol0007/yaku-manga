"""A complete local translation backend.

A port of `yaku.translation.engine.TranslationEngine` and its ONNX implementation:
DBNet (or comic) detection -> manga-ocr recognition -> NLLB translation.

Implementations must never send page content anywhere. The interface exists so the ONNX
backend can be swapped for another local one without touching the caller; it is deliberately
not general enough to accommodate a network service.

Kotlin guards lazy session creation with a coroutine mutex because the reader can ask for two
pages at once when the user flips quickly and ONNX Runtime sessions are not safe to
initialise concurrently. A `threading.Lock` does the same job here.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from abc import ABC, abstractmethod
from collections.abc import Callable

from PIL import Image

from .detector import OnnxComicDetector, OnnxTextDetector, TextDetector
from .manifest import ModelPack
from .model import PageTranslation, TextBlock, TranslationLanguage, TranslationProgress
from .ort_model import OrtModel, default_threads
from .recognizer import OnnxTextRecognizer
from .repository import ModelRepository
from .tokenizer import SentencePieceVocab, UnigramTokenizer
from .translator import OnnxTranslator

log = logging.getLogger(__name__)

COMIC_DETECTOR = "comic"
"""`PackConfig.detector_kind` of a detector that returns boxes and balloons."""

MIN_SOURCE_LETTERS = 2
"""Below this the "text" is a fragment the detector tore off, not a line of dialogue."""

MAX_LENGTH_RATIO = 5
"""Japanese to English roughly triples; five times over is a decoder that has looped."""
LENGTH_ALLOWANCE = 20

SENTENCE_BREAK = re.compile(r"(?<=[.!?])\s+|\s+-\s+")
"""A sentence end, or the dash opus-MT uses to open a new speaker's line."""

ProgressCallback = Callable[[object], None]


class TranslationEngine(ABC):
    @property
    @abstractmethod
    def id(self) -> str: ...

    @abstractmethod
    def is_ready(self) -> bool:
        """True once every model file the engine needs is present and loadable."""

    @abstractmethod
    def warm_up(self) -> None:
        """Loads models into memory. Slow (hundreds of ms to seconds)."""

    @abstractmethod
    def translate_page(
        self,
        page: Image.Image,
        source: TranslationLanguage,
        target: TranslationLanguage,
        on_progress: ProgressCallback | None = None,
    ) -> PageTranslation:
        """Runs detection, recognition and translation over one page."""

    @abstractmethod
    def close(self) -> None: ...

    def __enter__(self) -> TranslationEngine:
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


class OnnxTranslationEngine(TranslationEngine):
    def __init__(
        self,
        pack: ModelPack,
        repository: ModelRepository,
        threads: int | None = None,
    ) -> None:
        self._pack = pack
        self._repository = repository
        self._threads = threads if threads is not None else default_threads()
        self._load_lock = threading.Lock()

        self._detector: TextDetector | None = None
        self._recognizer: OnnxTextRecognizer | None = None
        self._translator: OnnxTranslator | None = None

    @property
    def id(self) -> str:
        return self._pack.id

    def is_ready(self) -> bool:
        return self._repository.is_installed(self._pack)

    def warm_up(self) -> None:
        with self._load_lock:
            self._load_if_needed()

    def _file(self, name: str):
        return self._repository.installed_file(self._pack.id, name)

    def _load_if_needed(self) -> None:
        if self._detector is not None and self._recognizer is not None and self._translator is not None:
            return

        pack = self._pack
        # Both kinds answer the same question and nothing downstream can tell them apart,
        # but they arrive at it so differently that they cannot share an implementation: one
        # thresholds a map and groups blobs, the other reads boxes the model already drew.
        detector_model = OrtModel.open(self._file(pack.detector.name), self._threads)
        if pack.config.detector_kind == COMIC_DETECTOR:
            self._detector = OnnxComicDetector(detector_model, pack.config)
        else:
            self._detector = OnnxTextDetector(detector_model, pack.config)

        self._recognizer = OnnxTextRecognizer(
            encoder=OrtModel.open(self._file(pack.recognizer_encoder.name), self._threads),
            decoder=OrtModel.open(self._file(pack.recognizer_decoder.name), self._threads),
            vocab=OnnxTextRecognizer.load_vocab(self._file(pack.recognizer_vocab.name)),
            config=pack.config,
        )
        self._translator = OnnxTranslator(
            encoder=OrtModel.open(self._file(pack.translator_encoder.name), self._threads),
            decoder=OrtModel.open(self._file(pack.translator_decoder.name), self._threads),
            tokenizer=UnigramTokenizer(SentencePieceVocab.load(self._file(pack.translator_vocab.name))),
            config=pack.config,
        )

    def translate_page(
        self,
        page: Image.Image,
        source: TranslationLanguage,
        target: TranslationLanguage,
        on_progress: ProgressCallback | None = None,
    ) -> PageTranslation:
        report = on_progress or (lambda _p: None)
        started = time.monotonic()

        report(TranslationProgress.LoadingModels())
        with self._load_lock:
            self._load_if_needed()
        detector, recognizer, translator = self._detector, self._recognizer, self._translator
        assert detector is not None and recognizer is not None and translator is not None

        if page.mode != "RGB":
            page = page.convert("RGB")

        report(TranslationProgress.Detecting())
        detected = detector.detect(page)

        recognized: list[TextBlock] = []
        for index, block in enumerate(detected):
            report(TranslationProgress.Recognizing(index, len(detected)))
            try:
                text = recognizer.recognize(page, block.box)
            except Exception:
                log.warning("Recognition failed for a block", exc_info=True)
                text = ""
            # Two letters, not merely non-blank. A bubble outline reads as "(" often enough
            # that it was being translated and drawn as a box with a bracket in it, and on a
            # page of real artwork the detector returns single glyphs it has torn off a
            # column - each one arrives as a plausible word and is set on the page as if it
            # were a line of dialogue. A one-character line does exist, but it is rarer than
            # the failure, so losing the odd interjection is the better trade.
            if _letter_count(text) >= MIN_SOURCE_LETTERS:
                recognized.append(block.copy(source_text=text))

        translated: list[TextBlock] = []
        for index, block in enumerate(recognized):
            report(TranslationProgress.Translating(index, len(recognized)))
            try:
                text = translator.translate(block.source_text or "", source, target)
            except Exception:
                log.warning("Translation failed for a block", exc_info=True)
                text = ""
            cleaned = tidy(text)
            keep = cleaned.strip() and is_proportionate(cleaned, block.source_text or "")
            translated.append(block.copy(translated_text=cleaned if keep else None))

        result = PageTranslation(
            page_width=page.width,
            page_height=page.height,
            blocks=translated,
            source_language=source,
            target_language=target,
            elapsed_millis=int((time.monotonic() - started) * 1000),
        )
        report(TranslationProgress.Done(result))
        return result

    def close(self) -> None:
        for part in (self._detector, self._recognizer, self._translator):
            if part is not None:
                part.close()
        self._detector = None
        self._recognizer = None
        self._translator = None


def is_proportionate(translated: str, source: str) -> bool:
    """Rejects a translation far longer than its source could account for.

    A decoder that loses its way does not stop - it repeats until it hits the token limit,
    and returns a long, fluent, entirely invented passage. Measured on a scanned page, one
    box turned 63 characters of Japanese into 379 of English. Japanese to English roughly
    triples in length, so anything past five times the source plus a margin is the decoder
    talking to itself, and printing it over the artwork is worse than leaving the box alone.
    """
    return len(translated) <= len(source) * MAX_LENGTH_RATIO + LENGTH_ALLOWANCE


def tidy(text: str) -> str:
    """Removes the subtitle conventions the translator picked up from its training data.

    opus-MT was trained largely on film subtitles, so it prefixes lines with a dialogue dash
    and, when the source is a single short interjection, often emits the same sentence twice -
    a plain greeting came back as "- Good morning. - Good morning.". Both arrive as ordinary
    output tokens, indistinguishable from content until the sentence is read as a whole, so
    this is the first point that can tell them apart.
    """
    sentences = [
        stripped
        for stripped in (part.strip().removeprefix("-").strip() for part in SENTENCE_BREAK.split(text))
        if stripped
    ]
    if not sentences:
        return text.strip()
    # Collapse only when every sentence is the same one. Repetition for emphasis is rarer in
    # dialogue than this failure is, but a translation of several distinct sentences has to
    # survive intact.
    distinct = list(dict.fromkeys(sentences))
    return distinct[0] if len(distinct) == 1 else " ".join(sentences)


def _letter_count(text: str) -> int:
    """Kotlin's `Char::isLetter` is Unicode-aware, so kana and kanji count; `str.isalpha`
    is the same question."""
    return sum(1 for char in text if char.isalpha())
