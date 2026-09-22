# yaku-translate

Yaku Manga's on-device translation pipeline, ported from the app's Kotlin `:translation`
module to a Python package and a command line tool.

Same pipeline, same model packs, same manifest format, same on-disk layout — running on a
desktop over image files instead of inside the reader on a phone.

```
page image ─▶ text detection ─▶ text recognition ─▶ translation ─▶ text drawn onto the page
              (DBNet / comic)    (manga-ocr-class)   (NLLB / opus-MT)
```

Every stage is an ONNX graph executed locally by ONNX Runtime. Nothing is sent anywhere.

## What this is for

The Android app is the product; this is the same engine addressed from a terminal, which is
useful for the things a phone is bad at:

- **Trying a model pack** without installing an APK, and seeing the detected boxes, the
  recognised source text and the translation as JSON.
- **Translating a whole volume** in one go — a folder of pages or a `.cbz`.
- **Working on the pipeline** — the detector's post-process, the balloon finder, the
  typesetting — with a test suite that runs in a second instead of a Gradle build and an
  emulator.

It is **not** a replacement for the app: there is no library, no sources, no reader.

## Install

```bash
cd python
pip install -e ".[dev]"      # drop [dev] if you do not need the tests
```

Requires Python 3.10+. The runtime dependencies are `onnxruntime`, `pillow` and `numpy`.

## Getting a model pack

The app ships with no weights and neither does this. Packs are the same ones the app
downloads, verified by SHA-256 before use:

```bash
yaku-translate packs list
yaku-translate packs install <pack-id>
yaku-translate packs installed
```

Packs land in `~/.local/share/yaku-translate/translation-models/<pack-id>/` by default —
the same directory layout the app uses under its private files directory, so a pack copied
off a phone works here and vice versa. Override with `--models-root`, or set
`YAKU_TRANSLATE_HOME`.

## Translating

One page:

```bash
yaku-translate page chapter/001.png -o 001.translated.png
```

A folder of pages or a `.cbz`, optionally packed back into a `.cbz`:

```bash
yaku-translate batch chapter/ -o out/
yaku-translate batch volume.cbz -o out/ --cbz volume.translated.cbz
```

Useful flags:

| Flag | What it does |
| --- | --- |
| `--from ja --to en` | Source and target language (default `ja` → `en`) |
| `--pack <id>` | Which installed pack to use, when more than one is installed |
| `--json out.json` | Write the boxes, source text and translations as JSON |
| `--no-render` | Run the pipeline without writing an image — pairs with `--json` |
| `--font path.ttf` | Lettering font (defaults to the app's ComicNeue-Bold in a checkout) |
| `--no-uppercase` | Leave the lettering cased instead of setting it in capitals |
| `--threads N` | ONNX intra-op threads |

## As a library

```python
from PIL import Image
from yaku_translate import (
    ModelRepository,
    OnnxTranslationEngine,
    TranslationLanguage,
    TranslationRenderer,
)

repository = ModelRepository()
pack = repository.installed_packs()[0]

with OnnxTranslationEngine(pack, repository) as engine:
    page = Image.open("001.png")
    result = engine.translate_page(page, TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH)

    for block in result.blocks:
        print(block.box, block.source_text, "->", block.translated_text)

    TranslationRenderer().render(page, result).save("001.translated.png")
```

`translate_page` takes an optional `on_progress` callback that reports each stage, the same
way the reader drives its progress indicator.

## How it maps onto the Kotlin

One module per Kotlin file, keeping the names so the two can be read side by side:

| Kotlin | Python |
| --- | --- |
| `model/TextBlock.kt`, `model/PageTranslation.kt` | `model.py` |
| `store/ModelManifest.kt` | `manifest.py` |
| `store/ModelRepository.kt` | `repository.py` |
| `engine/onnx/OrtModel.kt` | `ort_model.py` |
| `engine/onnx/ImageTensors.kt` | `image_tensors.py` |
| `engine/onnx/tokenizer/UnigramTokenizer.kt` | `tokenizer.py` |
| `engine/TextDetector.kt`, `OnnxTextDetector.kt`, `OnnxComicDetector.kt` | `detector.py` |
| `engine/onnx/OnnxTextRecognizer.kt` | `recognizer.py` |
| `engine/onnx/OnnxTranslator.kt` | `translator.py` |
| `engine/TranslationEngine.kt`, `OnnxTranslationEngine.kt` | `engine.py` |
| `render/TranslationRenderer.kt` | `render.py` |

The pipeline's behaviour is deliberately identical, including every tuned constant and the
reasons behind it — the detector's merge slop, the balloon escape fraction, the length ratio
that rejects a decoder talking to itself. Where the platform forced a change:

- **Bitmap/Canvas/Paint → Pillow.** `ImageDraw` and a FreeType face. Android's font metrics
  report a negative ascent and Pillow's a positive one, which is the only arithmetic that
  differs.
- **Connected components and the balloon flood fill scan runs, not pixels.** Both find the
  same regions — the same 4-connected sets — but per-row, so numpy does the scanning. A
  pixel-at-a-time loop in Python would take tens of seconds per page.
- **Coroutines → plain calls.** `suspend` functions become ordinary ones and the download
  `Flow` becomes a progress callback. The lazy-load mutex becomes a `threading.Lock`, which
  it needs for the same reason: ONNX Runtime sessions are not safe to initialise
  concurrently.
- **OkHttp → `urllib`.** Both refuse to serve a manifest from cache, for the same reason: a
  captive portal's cached HTML would otherwise replay forever.
- **The ownership map is `int32`, not bytes.** Kotlin packs balloon stamps into a `ByteArray`
  to keep it small on a phone, which caps a page at 127 blocks. There is no reason to accept
  that here.

## Tests

```bash
cd python && pytest
```

The suite builds a complete translation pack out of hand-written ONNX graphs (about 4KB in
total, in `tests/fakepack.py`) whose inputs and outputs have the same signatures as a real
pack's exports, and whose answers are deterministic. That means the end-to-end test runs the
real ONNX Runtime sessions, the real detector post-process, the real greedy decoders, the
real tokenizer and the real renderer — without a few hundred megabytes of weights.

## Licence

Apache-2.0, the same as the rest of the repository.
