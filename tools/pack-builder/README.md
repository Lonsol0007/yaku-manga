# Pack builder

Builds the ONNX model packs that Yaku Manga's translation feature runs on. This is a **desktop
build step** — none of it ships in the APK, and the phone never talks to anything but your own
machine.

The app deliberately ships with no weights. That keeps the download small, keeps model licensing
out of the APK, and means the translation pipeline is something you assemble and can inspect
rather than something baked in.

## What a pack contains

```
ja-en-base/
    pack.json                  written by the builder; lets the pack load with no network
    detector.onnx              DBNet — finds text regions on the page
    recognizer_encoder.onnx    manga-ocr vision encoder
    recognizer_decoder.onnx    manga-ocr text decoder
    recognizer_vocab.json      flat token array, id order
    translator_encoder.onnx    opus-MT / NLLB encoder
    translator_decoder.onnx    opus-MT / NLLB decoder
    translator_vocab.json      SentencePiece unigram pieces + scores
```

## Prerequisites

Python 3.10+ is required and is **not** currently installed on this machine — the `python` on
PATH is a Microsoft Store stub that only opens the Store.

```
winget install Python.Python.3.12
```

Open a new terminal so PATH picks it up, then:

```
cd tools/pack-builder
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

That pulls in PyTorch and will take a while (~2.5 GB). It is only needed to *build* packs.

## Build

```
python build_pack.py --preset ja-en
```

Downloads the three source models from HuggingFace, exports each to ONNX, quantises to int8,
dumps the vocabularies, verifies every graph loads with the tensor names the app expects, and
writes `out/ja-en-base/` plus an `out/manifest.json`.

Expect roughly 15–30 minutes on a first run, most of it model download.

| Preset | Translator | Pack size (int8) | Notes |
|---|---|---|---|
| `ja-en` | opus-MT ja-en | **~130 MB** | Default. The only size class that is comfortable on a phone. |
| `ja-multi` | NLLB-200 distilled 600M | ~700 MB | Seven target languages, much slower per bubble. |

Useful flags:

- `--skip-quantize` — keep fp32 weights, for checking whether int8 is costing you quality.
- `--out DIR` — write somewhere other than `out/<pack id>`.
- `--base-url URL` — the URL prefix baked into `manifest.json`. Defaults to this machine's LAN
  address, which is what `serve_pack.py` hands out.

## Install onto the phone

### Over Wi-Fi (recommended)

```
python serve_pack.py out
```

It prints a URL. In the app: **Settings → Translation → Manifest URL**, paste it, then
**Browse packs** and download. Phone and PC must be on the same network.

Nothing leaves your network — this is a plain local file server, and it is the reason the
manifest URL is a setting rather than a hardcoded endpoint.

### Over ADB (debug builds only)

Works because debug builds are debuggable, so `run-as` can reach the private files directory.
Release builds cannot do this without root.

```
adb push out/ja-en-base /data/local/tmp/ja-en-base
adb shell run-as app.yaku.dev mkdir -p files/translation-models
adb shell run-as app.yaku.dev cp -r /data/local/tmp/ja-en-base files/translation-models/
```

Then enable the toggle in Settings → Translation.

## If verification fails

The builder refuses to emit a pack whose graphs the app could not load, and prints the tensor
names each model actually exposes. Optimum changes these between versions.

Fix it by setting the relevant `*_name` keys in the pack's `config` block — see
[`docs/translation-packs.md`](../../docs/translation-packs.md) for the full list. The app also
resolves several common aliases automatically (`pixel_values`/`input`,
`last_hidden_state`/`output`), so a mismatch usually only needs one or two keys.

## Choosing different models

Any model that fits these shapes will work:

- **Detector** — one image input, one probability-map output. The sigmoid must be *inside* the
  graph; an export that emits raw logits detects nothing at the default threshold.
- **Recogniser** — a `VisionEncoderDecoderModel`, exported without KV cache.
- **Translator** — a seq2seq model with a SentencePiece unigram vocabulary, exported without KV
  cache.

Add a `Preset` to `build_pack.py` and set `translator_decoder_start_token` correctly for the
family: NLLB and M2M prime the decoder with `</s>`, Marian/opus-MT with `<pad>`. Getting this
wrong produces fluent nonsense rather than an obvious error, so it is worth checking against the
model's `generation_config.json`.
