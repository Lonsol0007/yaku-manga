"""Yaku Manga's on-device translation pipeline, as a Python package.

A port of the Android app's `:translation` module: page image in, detected and translated
text out, lettered back onto the page. Every stage is an ONNX graph executed locally, and it
reads the same model packs the app downloads, with the same manifest format and the same
on-disk layout.

    from yaku_translate import ModelRepository, OnnxTranslationEngine, TranslationLanguage
    from yaku_translate import TranslationRenderer
    from PIL import Image

    repository = ModelRepository()
    pack = repository.installed_packs()[0]
    with OnnxTranslationEngine(pack, repository) as engine:
        page = Image.open("page.png")
        result = engine.translate_page(page, TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH)
        TranslationRenderer().render(page, result).save("page.translated.png")
"""

from .detector import OnnxComicDetector, OnnxTextDetector, TextDetector
from .engine import OnnxTranslationEngine, TranslationEngine
from .manifest import ModelFile, ModelManifest, ModelPack, PackConfig
from .model import (
    BoxF,
    PageTranslation,
    TextBlock,
    TranslationLanguage,
    TranslationProgress,
)
from .ort_model import OrtModel, default_threads
from .recognizer import OnnxTextRecognizer
from .render import Balloon, BalloonFinder, RenderStyle, TranslationRenderer
from .repository import DownloadProgress, ModelRepository, default_models_root
from .tokenizer import SentencePieceVocab, UnigramTokenizer
from .translator import OnnxTranslator

__version__ = "0.0.3"

__all__ = [
    "Balloon",
    "BalloonFinder",
    "BoxF",
    "DownloadProgress",
    "ModelFile",
    "ModelManifest",
    "ModelPack",
    "ModelRepository",
    "OnnxComicDetector",
    "OnnxTextDetector",
    "OnnxTextRecognizer",
    "OnnxTranslationEngine",
    "OnnxTranslator",
    "OrtModel",
    "PackConfig",
    "PageTranslation",
    "RenderStyle",
    "SentencePieceVocab",
    "TextBlock",
    "TextDetector",
    "TranslationEngine",
    "TranslationLanguage",
    "TranslationProgress",
    "TranslationRenderer",
    "UnigramTokenizer",
    "default_models_root",
    "default_threads",
]
