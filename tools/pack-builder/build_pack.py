#!/usr/bin/env python3
"""
Build a Yaku Manga translation pack.

Exports a text detector, a manga text recogniser and a machine translation model to ONNX,
quantises them to int8, dumps their vocabularies in the formats the app reads, verifies the
result actually loads, and writes both a `pack.json` (sideload) and a `manifest.json` (hosted).

    python build_pack.py --preset ja-en --out ./out/ja-en-base

Nothing here runs on a phone - this is a desktop build step. The output directory is what the
app consumes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------------------
# Presets
# --------------------------------------------------------------------------------------


@dataclass
class Preset:
    pack_id: str
    name: str
    description: str
    source_languages: list[str]
    target_languages: list[str]
    detector_arch: str
    detector_size: int
    recognizer_model: str
    translator_model: str
    translator_uses_language_token: bool
    translator_decoder_start_token: str
    extra_config: dict = field(default_factory=dict)


PRESETS: dict[str, Preset] = {
    # The practical default. opus-MT ja-en is ~75 MB in fp32 and ~20 MB int8, which is the only
    # size class that makes sense to ship to a phone.
    "ja-en": Preset(
        pack_id="ja-en-base",
        name="Japanese to English (base)",
        description="DBNet detector + manga-ocr recogniser + opus-MT ja-en, int8",
        source_languages=["ja"],
        target_languages=["en"],
        detector_arch="db_mobilenet_v3_large",
        detector_size=1024,
        recognizer_model="kha-white/manga-ocr-base",
        translator_model="Helsinki-NLP/opus-mt-ja-en",
        translator_uses_language_token=False,
        translator_decoder_start_token="<pad>",
    ),
    # Multilingual, and much heavier: NLLB-600M is ~2.4 GB fp32 and still ~600 MB at int8.
    # Only worth it if you need more than one language pair from a single pack.
    "ja-multi": Preset(
        pack_id="ja-multi-nllb",
        name="Japanese to many (NLLB-600M)",
        description="DBNet detector + manga-ocr recogniser + NLLB-200 distilled 600M, int8",
        source_languages=["ja"],
        target_languages=["en", "es", "fr", "de", "pt", "ru", "zh"],
        detector_arch="db_mobilenet_v3_large",
        detector_size=1024,
        recognizer_model="kha-white/manga-ocr-base",
        translator_model="facebook/nllb-200-distilled-600M",
        translator_uses_language_token=True,
        translator_decoder_start_token="</s>",
    ),
}

FILE_ROLES = [
    "detector",
    "recognizer_encoder",
    "recognizer_decoder",
    "recognizer_vocab",
    "translator_encoder",
    "translator_decoder",
    "translator_vocab",
]


def lan_address() -> str:
    """Best-effort LAN IP: the address this machine would use to reach the outside world."""
    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def log(message: str) -> None:
    print(f"  {message}", flush=True)


def step(message: str) -> None:
    print(f"\n==> {message}", flush=True)


# --------------------------------------------------------------------------------------
# Detector
# --------------------------------------------------------------------------------------


def export_detector(preset: Preset, work: Path) -> Path:
    """
    Export a DBNet text detector whose single output is a probability map.

    The app thresholds that map directly, so the sigmoid has to be inside the graph rather than
    left to the caller - an export that emits raw logits looks fine but detects nothing at the
    default 0.3 threshold.
    """
    import torch
    from doctr.models import detection

    step(f"Exporting detector ({preset.detector_arch})")

    model = getattr(detection, preset.detector_arch)(pretrained=True, exportable=True).eval()

    class ProbabilityMap(torch.nn.Module):
        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, x: "torch.Tensor") -> "torch.Tensor":
            out = self.inner(x)
            logits = out["logits"] if isinstance(out, dict) else out
            return torch.sigmoid(logits)

    wrapped = ProbabilityMap(model).eval()
    size = preset.detector_size
    dummy = torch.randn(1, 3, size, size)

    path = work / "detector.onnx"
    torch.onnx.export(
        wrapped,
        dummy,
        str(path),
        input_names=["input"],
        output_names=["output"],
        opset_version=17,
        do_constant_folding=True,
    )
    log(f"wrote {path.name}")
    return path


# --------------------------------------------------------------------------------------
# Seq2seq exports (recogniser + translator both go through Optimum)
# --------------------------------------------------------------------------------------


def export_onnx_model(model_id: str, task: str, out_dir: Path) -> Path:
    """
    Export a HuggingFace model to ONNX without KV-cache branches.

    The app decodes greedily and re-runs the full prefix each step, so it wants the plain
    `decoder_model.onnx`. Exporting with past-key-values produces a decoder whose input surface
    the app cannot satisfy.
    """
    from optimum.exporters.onnx import main_export

    out_dir.mkdir(parents=True, exist_ok=True)
    main_export(
        model_name_or_path=model_id,
        output=out_dir,
        task=task,
        no_post_process=True,
        do_validation=False,
        opset=17,
    )
    return out_dir


def pick(directory: Path, *candidates: str) -> Path:
    for candidate in candidates:
        path = directory / candidate
        if path.exists():
            return path
    available = ", ".join(sorted(p.name for p in directory.glob("*.onnx")))
    raise SystemExit(
        f"None of {candidates} found in {directory}.\nAvailable ONNX files: {available or 'none'}"
    )


# --------------------------------------------------------------------------------------
# Vocabularies
# --------------------------------------------------------------------------------------


def dump_recognizer_vocab(model_id: str, target: Path) -> None:
    """Flat JSON array of token strings in id order, which is what the recogniser reads."""
    from transformers import AutoTokenizer

    step("Dumping recogniser vocabulary")
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    size = len(tokenizer)
    tokens = tokenizer.convert_ids_to_tokens(list(range(size)))
    tokens = [t if t is not None else "" for t in tokens]

    for required in ("[CLS]", "[SEP]"):
        if required not in tokens:
            log(f"WARNING: {required} missing - the recogniser keys BOS/EOS off it")

    target.write_text(json.dumps(tokens, ensure_ascii=False), encoding="utf-8")
    log(f"{size} tokens -> {target.name}")


def dump_translator_vocab(model_id: str, model_dir: Path, target: Path, preset: Preset) -> None:
    """
    Dump a SentencePiece unigram vocabulary as JSON.

    The app runs Viterbi segmentation over `(piece, score)` pairs itself rather than linking the
    SentencePiece native library. Ids must match the model's own vocabulary exactly, so the id
    order comes from the tokenizer and the scores are looked up from the SentencePiece model -
    the two do not share an indexing scheme and cannot be zipped together blindly.
    """
    from transformers import AutoTokenizer

    step("Dumping translator vocabulary")
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    size = len(tokenizer)
    pieces = tokenizer.convert_ids_to_tokens(list(range(size)))
    pieces = [p if p is not None else "" for p in pieces]

    scores = load_sentencepiece_scores(model_dir)
    if scores:
        fallback = min(scores.values()) - 1.0
        resolved = [scores.get(p, fallback) for p in pieces]
        missing = sum(1 for p in pieces if p not in scores)
        log(f"scores from SentencePiece; {missing}/{size} pieces fell back to {fallback:.2f}")
    else:
        # Without scores Viterbi degenerates to "prefer fewer pieces", which still segments
        # correctly for most text but loses the model's own preferences.
        resolved = [0.0] * size
        log("WARNING: no SentencePiece model found; scores default to 0.0")

    def id_of(token: str | None, default: int) -> int:
        if token is None:
            return default
        found = tokenizer.convert_tokens_to_ids(token)
        return found if isinstance(found, int) and found >= 0 else default

    payload = {
        "model_type": "unigram",
        "unk_id": id_of(tokenizer.unk_token, 0),
        "bos_id": id_of(tokenizer.bos_token or tokenizer.eos_token, 1),
        "eos_id": id_of(tokenizer.eos_token, 2),
        "pad_id": id_of(tokenizer.pad_token, 3),
        "pieces": [{"piece": p, "score": float(s)} for p, s in zip(pieces, resolved)],
    }
    target.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    log(f"{size} pieces -> {target.name}")

    if preset.translator_uses_language_token:
        vocabulary = set(pieces)
        for code in ("jpn_Jpan", "eng_Latn"):
            if code not in vocabulary:
                log(f"WARNING: language token {code} absent; translation will skip it")


def load_sentencepiece_scores(model_dir: Path) -> dict[str, float]:
    """Read `(piece -> log probability)` out of whichever .spm/.model file the export produced."""
    candidates = sorted(model_dir.glob("*.spm")) + sorted(model_dir.glob("*.model"))
    if not candidates:
        return {}
    try:
        import sentencepiece as spm
    except ImportError:
        log("sentencepiece not installed; cannot read piece scores")
        return {}

    # Marian ships source.spm and target.spm. The source side is what gets segmented.
    source = next((c for c in candidates if "source" in c.name), candidates[0])
    processor = spm.SentencePieceProcessor()
    processor.Load(str(source))
    log(f"reading scores from {source.name}")
    return {processor.IdToPiece(i): processor.GetScore(i) for i in range(processor.GetPieceSize())}


# --------------------------------------------------------------------------------------
# Quantisation
# --------------------------------------------------------------------------------------


def quantize(source: Path, target: Path) -> None:
    """Dynamic int8 quantisation - roughly quarters size and latency on ARM."""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(
        model_input=str(source),
        model_output=str(target),
        weight_type=QuantType.QInt8,
    )
    before = source.stat().st_size / 1e6
    after = target.stat().st_size / 1e6
    log(f"{source.name}: {before:.0f} MB -> {after:.0f} MB")


# --------------------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------------------

EXPECTED_IO = {
    "detector.onnx": (["input"], ["output"]),
    "recognizer_encoder.onnx": (["pixel_values"], ["last_hidden_state"]),
    "recognizer_decoder.onnx": (["input_ids", "encoder_hidden_states"], ["logits"]),
    "translator_encoder.onnx": (["input_ids"], ["last_hidden_state"]),
    "translator_decoder.onnx": (["encoder_hidden_states"], ["logits"]),
}


def verify(pack_dir: Path) -> bool:
    """
    Load every graph and check its IO names against what the app resolves.

    Worth doing here because the failure mode on-device is silent: a tensor name the app cannot
    resolve surfaces as an exception inside the reader, long after the pack was built.
    """
    import onnxruntime as ort

    step("Verifying exports")
    ok = True
    for name, (want_inputs, want_outputs) in EXPECTED_IO.items():
        path = pack_dir / name
        if not path.exists():
            log(f"FAIL {name}: missing")
            ok = False
            continue

        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        inputs = [i.name for i in session.get_inputs()]
        outputs = [o.name for o in session.get_outputs()]

        for wanted in want_inputs:
            if wanted not in inputs:
                log(f"FAIL {name}: no input '{wanted}' (has {inputs})")
                ok = False
        for wanted in want_outputs:
            if wanted not in outputs:
                log(f"FAIL {name}: no output '{wanted}' (has {outputs})")
                ok = False
        if ok:
            log(f"ok   {name}  in={inputs} out={outputs}")

    for vocab in ("recognizer_vocab.json", "translator_vocab.json"):
        path = pack_dir / vocab
        if not path.exists():
            log(f"FAIL {vocab}: missing")
            ok = False
    return ok


# --------------------------------------------------------------------------------------
# Manifest
# --------------------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_metadata(pack_dir: Path, preset: Preset, base_url: str) -> dict:
    step("Hashing and writing metadata")

    names = {
        "detector": "detector.onnx",
        "recognizer_encoder": "recognizer_encoder.onnx",
        "recognizer_decoder": "recognizer_decoder.onnx",
        "recognizer_vocab": "recognizer_vocab.json",
        "translator_encoder": "translator_encoder.onnx",
        "translator_decoder": "translator_decoder.onnx",
        "translator_vocab": "translator_vocab.json",
    }

    config = {
        "detector_input_size": preset.detector_size,
        "detector_input_name": "input",
        "detector_output_name": "output",
        "translator_uses_language_token": preset.translator_uses_language_token,
        "translator_decoder_start_token": preset.translator_decoder_start_token,
        **preset.extra_config,
    }

    pack = {
        "id": preset.pack_id,
        "name": preset.name,
        "description": preset.description,
        "source_languages": preset.source_languages,
        "target_languages": preset.target_languages,
        "config": config,
    }

    total = 0
    for role, filename in names.items():
        path = pack_dir / filename
        size = path.stat().st_size
        total += size
        pack[role] = {
            "name": filename,
            "url": f"{base_url.rstrip('/')}/{preset.pack_id}/{filename}",
            "size_bytes": size,
            "sha256": sha256_of(path),
        }
        log(f"{filename}: {size / 1e6:.1f} MB")

    (pack_dir / "pack.json").write_text(json.dumps(pack, indent=2), encoding="utf-8")

    manifest = {"version": 1, "packs": [pack]}
    (pack_dir.parent / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    log(f"total pack size: {total / 1e6:.0f} MB")
    return pack


# --------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------


def build(preset: Preset, out: Path, base_url: str, skip_quantize: bool) -> None:
    pack_dir = out
    pack_dir.mkdir(parents=True, exist_ok=True)
    work = pack_dir / ".work"
    work.mkdir(exist_ok=True)

    detector_raw = export_detector(preset, work)

    step(f"Exporting recogniser ({preset.recognizer_model})")
    recognizer_dir = export_onnx_model(preset.recognizer_model, "image-to-text", work / "recognizer")

    step(f"Exporting translator ({preset.translator_model})")
    translator_dir = export_onnx_model(
        preset.translator_model, "text2text-generation", work / "translator"
    )

    exports = [
        (detector_raw, pack_dir / "detector.onnx"),
        (pick(recognizer_dir, "encoder_model.onnx"), pack_dir / "recognizer_encoder.onnx"),
        (pick(recognizer_dir, "decoder_model.onnx"), pack_dir / "recognizer_decoder.onnx"),
        (pick(translator_dir, "encoder_model.onnx"), pack_dir / "translator_encoder.onnx"),
        (pick(translator_dir, "decoder_model.onnx"), pack_dir / "translator_decoder.onnx"),
    ]

    if skip_quantize:
        step("Copying exports (quantisation skipped)")
        for source, target in exports:
            shutil.copyfile(source, target)
            log(f"{target.name}: {target.stat().st_size / 1e6:.0f} MB")
    else:
        step("Quantising to int8")
        for source, target in exports:
            quantize(source, target)

    dump_recognizer_vocab(preset.recognizer_model, pack_dir / "recognizer_vocab.json")
    dump_translator_vocab(
        preset.translator_model, translator_dir, pack_dir / "translator_vocab.json", preset
    )

    if not verify(pack_dir):
        raise SystemExit(
            "\nVerification failed. The pack would not load on device.\n"
            "Tensor names vary between Optimum versions - set the *_name keys in the pack's\n"
            "config block to whatever the report above says the graphs actually expose."
        )

    write_metadata(pack_dir, preset, base_url)

    shutil.rmtree(work, ignore_errors=True)

    print(f"\nPack built: {pack_dir}")
    print("Install it with either:")
    print(f"  python serve_pack.py {pack_dir.parent}      (then point Settings at the manifest)")
    print(f"  adb push / run-as                            (see README)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preset", choices=sorted(PRESETS), default="ja-en")
    parser.add_argument("--out", type=Path, help="output pack directory")
    parser.add_argument(
        "--base-url",
        default=None,
        help="URL prefix baked into manifest.json (default: this machine's LAN address, "
        "which is what serve_pack.py will hand out)",
    )
    parser.add_argument(
        "--skip-quantize",
        action="store_true",
        help="keep fp32 weights (bigger and slower, useful when checking quality regressions)",
    )
    args = parser.parse_args()

    preset = PRESETS[args.preset]
    out = args.out or Path("out") / preset.pack_id
    # The URLs in the manifest are fetched by the phone, so localhost is never the right
    # default - it would resolve to the phone itself.
    base_url = args.base_url or f"http://{lan_address()}:8770"

    print(f"Building '{preset.pack_id}' -> {out}")
    print(f"  detector   {preset.detector_arch} @ {preset.detector_size}px")
    print(f"  recogniser {preset.recognizer_model}")
    print(f"  translator {preset.translator_model}")

    build(preset, out, base_url, args.skip_quantize)


if __name__ == "__main__":
    sys.exit(main())
