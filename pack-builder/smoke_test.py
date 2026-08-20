#!/usr/bin/env python3
"""
Run a built pack through the *app's* algorithm, on the desktop.

This is deliberately not a HuggingFace `generate()` call. It re-implements what
`UnigramTokenizer` and `OnnxTranslator` do in Kotlin - the same Viterbi segmentation, the same
decoder priming, the same greedy loop - so that a mismatch between the pack and the app shows up
here rather than as garbled text on a phone.

    python smoke_test.py out/ja-en-base

If the English coming out of this is sensible, the pack and the app agree.
"""

from __future__ import annotations

import argparse
import json
import math
import unicodedata
from pathlib import Path

import numpy as np
import onnxruntime as ort

SPACE_MARKER = "▁"  # SentencePiece's space escape
UNK_PENALTY = -10.0

SAMPLES = [
    "おはようございます",
    "ちょっと待って！",
    "何を言っているんだ？",
    "この街には秘密が多すぎる。",
    "ありがとう、本当に助かったよ。",
]


class UnigramTokenizer:
    """Mirror of translation/.../tokenizer/UnigramTokenizer.kt."""

    def __init__(self, vocab: dict) -> None:
        self.pieces: list[str] = [p["piece"] for p in vocab["pieces"]]
        self.scores: list[float] = [float(p.get("score", 0.0)) for p in vocab["pieces"]]
        self.piece_to_id = {p: i for i, p in enumerate(self.pieces)}
        self.max_len = max((len(p) for p in self.pieces), default=1)
        self.unk_id = vocab.get("unk_id", 0)
        self.bos_id = vocab.get("bos_id", 1)
        self.eos_id = vocab.get("eos_id", 2)
        self.pad_id = vocab.get("pad_id", 3)

    def id_of(self, piece: str) -> int | None:
        return self.piece_to_id.get(piece)

    def normalize(self, text: str) -> str:
        # Mirrors the Kotlin: NFKC first, so full-width punctuation resolves to real pieces.
        folded = unicodedata.normalize("NFKC", text)
        collapsed = " ".join(folded.strip().split())
        if not collapsed:
            return ""
        return SPACE_MARKER + collapsed.replace(" ", SPACE_MARKER)

    def encode(self, text: str) -> list[int]:
        s = self.normalize(text)
        if not s:
            return []
        n = len(s)
        best = [-math.inf] * (n + 1)
        back_piece = [-1] * (n + 1)
        back_start = [-1] * (n + 1)
        best[0] = 0.0

        for end in range(1, n + 1):
            for start in range(max(0, end - self.max_len), end):
                if best[start] == -math.inf:
                    continue
                pid = self.piece_to_id.get(s[start:end])
                if pid is None:
                    continue
                candidate = best[start] + self.scores[pid]
                if candidate > best[end]:
                    best[end] = candidate
                    back_piece[end] = pid
                    back_start[end] = start
            if best[end] == -math.inf:
                start = end - 1
                if best[start] != -math.inf:
                    best[end] = best[start] + UNK_PENALTY
                    back_piece[end] = self.unk_id
                    back_start[end] = start

        ids: list[int] = []
        cursor = n
        while cursor > 0:
            pid, start = back_piece[cursor], back_start[cursor]
            if pid < 0 or start < 0:
                break
            ids.append(pid)
            cursor = start
        ids.reverse()
        return ids

    def decode(self, ids: list[int]) -> str:
        out = []
        for i in ids:
            if i in (self.eos_id, self.bos_id, self.pad_id):
                continue
            piece = self.pieces[i] if 0 <= i < len(self.pieces) else None
            if piece is None:
                continue
            if piece.startswith("<") and piece.endswith(">"):
                continue
            out.append(piece)
        return "".join(out).replace(SPACE_MARKER, " ").strip()


def resolve(names: list[str], *candidates: str) -> str:
    for c in candidates:
        if c in names:
            return c
    raise SystemExit(f"none of {candidates} in {names}")


def translate(pack: Path, config: dict, text: str, tok: UnigramTokenizer) -> str:
    enc = ort.InferenceSession(str(pack / "translator_encoder.onnx"), providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(pack / "translator_decoder.onnx"), providers=["CPUExecutionProvider"])

    enc_in = [i.name for i in enc.get_inputs()]
    dec_in = [i.name for i in dec.get_inputs()]

    ids_name = resolve(enc_in, "input_ids", "ids")
    mask_name = "attention_mask" if "attention_mask" in enc_in else None
    enc_out = resolve([o.name for o in enc.get_outputs()], "last_hidden_state", "output")

    dec_ids = resolve(dec_in, "decoder_input_ids", "input_ids", "ids")
    dec_state = resolve(dec_in, "encoder_hidden_states", "encoder_outputs")
    dec_mask = "encoder_attention_mask" if "encoder_attention_mask" in dec_in else None
    dec_out = resolve([o.name for o in dec.get_outputs()], "logits", "output")

    eos = tok.id_of(config.get("translator_eos_token", "</s>")) or tok.eos_id
    start_token = config.get("translator_decoder_start_token")
    start = tok.id_of(start_token) if start_token else None
    if start is None:
        start = eos

    source = tok.encode(text) + [eos]
    arr = np.array([source], dtype=np.int64)
    mask = np.ones_like(arr)

    feed = {ids_name: arr}
    if mask_name:
        feed[mask_name] = mask
    state = enc.run([enc_out], feed)[0]

    generated = [start]
    for _ in range(int(config.get("translator_max_tokens", 128))):
        feed = {dec_ids: np.array([generated], dtype=np.int64), dec_state: state}
        if dec_mask:
            feed[dec_mask] = mask
        logits = dec.run([dec_out], feed)[0]
        nxt = int(np.argmax(logits[0, -1]))
        if nxt == eos:
            break
        generated.append(nxt)

    return tok.decode(generated[1:])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, nargs="?", default=Path("out/ja-en-base"))
    args = parser.parse_args()

    pack = args.pack.resolve()
    meta = json.loads((pack / "pack.json").read_text(encoding="utf-8"))
    config = meta.get("config", {})
    vocab = json.loads((pack / "translator_vocab.json").read_text(encoding="utf-8"))
    tok = UnigramTokenizer(vocab)

    print(f"pack   {meta['id']}")
    print(f"start  {config.get('translator_decoder_start_token')!r} -> id "
          f"{tok.id_of(config.get('translator_decoder_start_token', '')) }")
    print(f"vocab  {len(tok.pieces)} pieces")
    print()

    for text in SAMPLES:
        ids = tok.encode(text)
        pieces = [tok.pieces[i] for i in ids]
        english = translate(pack, config, text, tok)
        print(f"  ja  {text}")
        print(f"  seg {' '.join(pieces)}")
        print(f"  en  {english}")
        print()


if __name__ == "__main__":
    main()
