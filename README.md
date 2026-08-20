# Yaku Manga — translation packs

This branch is the home of Yaku Manga's translation model packs: the tooling that builds them,
the format they follow, and the catalogue of what is published.

**The app lives on [`main`](https://github.com/Lonsol0007/yaku-manga/tree/main).** This branch
shares no history with it — it is an orphan branch, so cloning the app never drags several
hundred megabytes of model tooling along with it.

## What is a pack?

Yaku Manga ships with no model weights at all. A *pack* is a set of ONNX graphs plus their
vocabularies that together implement one detect → recognise → translate pipeline, downloaded on
request and verified by SHA-256 before use.

```
page bitmap ─▶ text detection ─▶ text recognition ─▶ translation ─▶ text drawn onto the page
               (DBNet)            (manga-ocr)         (opus-MT / NLLB)
```

## Published packs

| Pack | Size | Source → target | Status |
|---|---|---|---|
| `ja-en-base` | 263 MB | Japanese → English | Stable |
| `zh-en-base` | 270 MB | Chinese → English | Experimental |

Downloadable from the [packs-v1 release](https://github.com/Lonsol0007/yaku-manga/releases/tag/packs-v1),
which is also the app's default source — there is nothing to configure to get them.
[`manifest.json`](manifest.json) here is a copy of what that release serves, kept for reference.

Both were checked end to end before publishing, running the app's own tokenizer and greedy
decoder against the exact published files:

```
おはようございます        → Good morning.
この街には秘密が多すぎる。 → There's too much secrecy in this town.

早上好                   → Good morning.
这个城市有太多秘密。       → There are so many secrets about this city.
```

### Why Chinese is experimental, and why there is no Korean pack

The recogniser is manga-ocr, trained on **Japanese** manga. Counting its vocabulary by script
settles what it can and cannot read:

| Script | Tokens |
|---|---|
| Han (kanji / hanzi) | 4918 |
| Hangul | 193 |
| Latin | 128 |
| Katakana | 89 |
| Hiragana | 87 |

Chinese is viable because hanzi are covered — but the model never saw Chinese typography in
training, so treat quality on real manhua as unproven.

Korean is not. 193 hangul tokens against the ~2350 common syllables Korean needs would produce
confident nonsense, which is worse than shipping nothing. Korean needs a different recogniser;
the pack format already supports swapping one in without an app change.

## Building a pack

See [`pack-builder/README.md`](pack-builder/README.md). In short:

```
cd pack-builder
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python build_pack.py --preset ja-en
```

The builder exports each model to ONNX, quantises to int8, dumps both vocabularies in the formats
the app reads, and **verifies every graph loads with the tensor names the app resolves** before
writing anything. That last step matters: Optimum renames tensors between versions, and on-device
the failure surfaces as an exception inside the reader rather than at install time.

| Script | Purpose |
|---|---|
| `build_pack.py` | Export, quantise, verify, emit `pack.json` + `manifest.json` |
| `smoke_test.py` | Translate sample text through the app's own algorithm |
| `page_test.py` | Run detector + recogniser + translator over a synthetic page |
| `serve_pack.py` | Serve built packs over the LAN so a phone can install them |
| `publish_packs.py` | Stage packs for a GitHub release, content-addressed and deduplicated |

## Publishing your own

The app's pack source list takes any number of manifest URLs, so third-party packs do not
displace the official ones. Point a URL at JSON in the shape described in [`format.md`](format.md).

Two things to get right:

- **Pack ids are directory names.** A pack installs to `<app files>/translation-models/<id>/`, so
  an id colliding with an installed pack from a different source is refused rather than allowed to
  overwrite it. Prefix yours if collisions are likely.
- **Every file needs a correct SHA-256.** It is enforced, and a mismatch deletes the file — a
  corrupt model does not fail cleanly, it produces garbage translations.

Weights are never committed to git. They are release assets; `publish_packs.py` stages them named
by content hash, which both avoids filename collisions in a release's flat namespace and lets
packs share files they have in common.

## Licensing

Packs are conversions of third-party models, redistributed under their own licences:
[manga-ocr](https://huggingface.co/kha-white/manga-ocr-base) (Apache 2.0),
[opus-MT](https://huggingface.co/Helsinki-NLP) (CC-BY 4.0),
[docTR](https://github.com/mindee/doctr) DBNet (Apache 2.0). Converted to ONNX and quantised to
int8; otherwise unmodified.

The tooling on this branch is Apache 2.0, same as the app.
