# Translation model packs

Yaku Manga ships with no model weights. A *pack* is a set of ONNX graphs plus their vocabularies
that together implement one detect → recognise → translate pipeline. Packs are downloaded on
request, verified by SHA-256, and stored under the app's private files directory:

```
<app files>/translation-models/<pack id>/
    pack.json            ← written at install time, lets the pack load with no network
    detector.onnx
    recognizer_encoder.onnx
    recognizer_decoder.onnx
    recognizer_vocab.json
    translator_encoder.onnx
    translator_decoder.onnx
    translator_vocab.json
```

Copying that directory into place by hand works too — the app reads `pack.json` and never contacts
the network for a pack that is already installed.

## Manifest format

Settings takes a URL returning JSON in this shape. Nothing is fetched until you enter one.

```json
{
  "version": 1,
  "packs": [
    {
      "id": "ja-en-base",
      "name": "Japanese → English (base)",
      "description": "DBNet + manga-ocr + NLLB-200 distilled 600M, int8",
      "source_languages": ["ja"],
      "target_languages": ["en"],
      "detector":            { "name": "detector.onnx",            "url": "https://…", "size_bytes": 12000000,  "sha256": "…" },
      "recognizer_encoder":  { "name": "recognizer_encoder.onnx",  "url": "https://…", "size_bytes": 86000000,  "sha256": "…" },
      "recognizer_decoder":  { "name": "recognizer_decoder.onnx",  "url": "https://…", "size_bytes": 64000000,  "sha256": "…" },
      "recognizer_vocab":    { "name": "recognizer_vocab.json",    "url": "https://…", "size_bytes": 120000,    "sha256": "…" },
      "translator_encoder":  { "name": "translator_encoder.onnx",  "url": "https://…", "size_bytes": 210000000, "sha256": "…" },
      "translator_decoder":  { "name": "translator_decoder.onnx",  "url": "https://…", "size_bytes": 340000000, "sha256": "…" },
      "translator_vocab":    { "name": "translator_vocab.json",    "url": "https://…", "size_bytes": 4800000,   "sha256": "…" },
      "config": { }
    }
  ]
}
```

`sha256` is required and enforced. A file whose hash does not match is deleted rather than used —
a corrupt model does not fail cleanly at inference time, it produces garbage translations.

## `config` block

Every key is optional; the defaults below match the reference pack. Tensor names are resolved
automatically where possible (`pixel_values` or `input`, `last_hidden_state` or `output`, and so
on), so most stock exports from Optimum need no configuration at all.

| Key | Default | Meaning |
|---|---|---|
| `detector_input_size` | `960` | Square side the page is letterboxed into. |
| `detector_input_name` | `"input"` | Detector input tensor. |
| `detector_output_name` | `"output"` | Probability-map output tensor. |
| `detector_threshold` | `0.3` | Binarisation threshold on the probability map. |
| `detector_box_expand` | `0.08` | Fraction each box grows by, the DB "unclip" step. |
| `detector_min_area_ratio` | `0.00015` | Boxes smaller than this fraction of the page are dropped. |
| `detector_mean` / `detector_std` | ImageNet | Per-channel normalisation. |
| `recognizer_input_size` | `224` | Square side each text crop is resized to. |
| `recognizer_max_tokens` | `64` | Decode cap per text block. |
| `recognizer_mean` / `recognizer_std` | `0.5` | Per-channel normalisation. |
| `translator_max_tokens` | `128` | Decode cap per translated string. |
| `translator_uses_language_token` | `true` | NLLB/M2M style: prepend the language token. Set `false` for opus-MT. |
| `translator_bos_token` etc. | `<s>`, `</s>`, `<pad>`, `<unk>` | Special token strings, looked up in the vocab. |

## Vocabulary formats

**Recogniser** (`recognizer_vocab.json`) — a flat JSON array of token strings in id order:

```json
["[PAD]", "[UNK]", "[CLS]", "[SEP]", "あ", "い", "##る", "…"]
```

**Translator** (`translator_vocab.json`) — a SentencePiece unigram model dumped to JSON. The app
implements Viterbi segmentation over it directly, which avoids linking the SentencePiece native
library:

```json
{
  "model_type": "unigram",
  "unk_id": 0, "bos_id": 1, "eos_id": 2, "pad_id": 3,
  "pieces": [
    { "piece": "<unk>", "score": 0.0 },
    { "piece": "▁the",  "score": -3.21 }
  ]
}
```

Language tokens (`jpn_Jpan`, `eng_Latn`, …) must appear as ordinary pieces for
`translator_uses_language_token` to find them.

## Performance notes

Decoding is greedy and runs without a KV cache, so step *t* re-attends over *t* tokens. That is
the right trade for bubble-length text and keeps the exported graphs simple, but it means long
strings cost quadratically — hence the `*_max_tokens` caps. Int8-quantised exports are strongly
recommended: they roughly quarter both file size and latency at a quality cost that is hard to
notice on manga dialogue.
