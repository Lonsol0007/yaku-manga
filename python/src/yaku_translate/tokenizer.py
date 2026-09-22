"""A SentencePiece unigram tokenizer, and the vocabulary file format it reads.

A port of `yaku.translation.engine.onnx.tokenizer`. The Kotlin implements this by hand to
avoid linking the SentencePiece native library into an APK; Python could import
`sentencepiece` instead, but the vocabulary files a pack ships are the dumped JSON form, not
`.model` protos, so the same Viterbi segmentation is what reads them.
"""

from __future__ import annotations

import json
import re
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

SPACE_MARKER = "▁"
"""Lower one eighth block, SentencePiece's space escape."""

_UNK_PENALTY = -10.0
_SPECIAL_PREFIX = "<"
_SPECIAL_SUFFIX = ">"
_WHITESPACE_RUN = re.compile(r"\s+")


@dataclass(frozen=True)
class VocabPiece:
    piece: str
    score: float = 0.0


@dataclass(frozen=True)
class SentencePieceVocab:
    """Vocabulary file format expected next to a translator model.

    This is the shape produced by dumping a SentencePiece unigram model to JSON - a flat list
    of ``[piece, log_probability]`` pairs in id order, plus the ids of the special tokens.
    """

    pieces: tuple[VocabPiece, ...]
    model_type: str = "unigram"
    unk_id: int = 0
    bos_id: int = 1
    eos_id: int = 2
    pad_id: int = 3

    @classmethod
    def from_dict(cls, raw: dict) -> SentencePieceVocab:
        pieces = []
        for entry in raw.get("pieces", ()):
            if isinstance(entry, dict):
                pieces.append(VocabPiece(entry["piece"], float(entry.get("score", 0.0))))
            else:
                # Some dumps write `[piece, score]` pairs rather than objects.
                piece, *rest = entry
                pieces.append(VocabPiece(piece, float(rest[0]) if rest else 0.0))
        return cls(
            pieces=tuple(pieces),
            model_type=raw.get("model_type", "unigram"),
            unk_id=int(raw.get("unk_id", 0)),
            bos_id=int(raw.get("bos_id", 1)),
            eos_id=int(raw.get("eos_id", 2)),
            pad_id=int(raw.get("pad_id", 3)),
        )

    @classmethod
    def load(cls, path: Path | str) -> SentencePieceVocab:
        return cls.from_dict(json.loads(Path(path).read_text(encoding="utf-8")))


class UnigramTokenizer:
    """Encoding runs the standard Viterbi segmentation: for each prefix of the (space-escaped)
    input, find the highest-scoring path of vocabulary pieces reaching it.

    Characters that no piece covers fall back to the unknown token so a stray emoji in a
    speech bubble cannot derail a whole page.
    """

    def __init__(self, vocab: SentencePieceVocab) -> None:
        self._vocab = vocab
        # First id wins, matching Kotlin's `associate` over `withIndex` only where ids are
        # unique; duplicates in a dumped vocabulary are aliases of the same piece.
        self._piece_to_id: dict[str, int] = {}
        for index, piece in enumerate(vocab.pieces):
            self._piece_to_id.setdefault(piece.piece, index)
        self._scores = [p.score for p in vocab.pieces]
        self._max_piece_length = max((len(p.piece) for p in vocab.pieces), default=1)

    @property
    def unk_id(self) -> int:
        return self._vocab.unk_id

    @property
    def bos_id(self) -> int:
        return self._vocab.bos_id

    @property
    def eos_id(self) -> int:
        return self._vocab.eos_id

    @property
    def pad_id(self) -> int:
        return self._vocab.pad_id

    @property
    def size(self) -> int:
        return len(self._vocab.pieces)

    def id_of(self, piece: str) -> int | None:
        return self._piece_to_id.get(piece)

    def piece_of(self, token_id: int) -> str | None:
        if 0 <= token_id < len(self._vocab.pieces):
            return self._vocab.pieces[token_id].piece
        return None

    def encode(self, text: str) -> list[int]:
        normalized = self._normalize(text)
        if not normalized:
            return []

        n = len(normalized)
        # best[i] = score of the best segmentation of normalized[0:i]
        best = [float("-inf")] * (n + 1)
        back_piece = [-1] * (n + 1)
        back_start = [-1] * (n + 1)
        best[0] = 0.0

        for end in range(1, n + 1):
            min_start = max(0, end - self._max_piece_length)
            for start in range(min_start, end):
                if best[start] == float("-inf"):
                    continue
                token_id = self._piece_to_id.get(normalized[start:end])
                if token_id is None:
                    continue
                candidate = best[start] + self._scores[token_id]
                if candidate > best[end]:
                    best[end] = candidate
                    back_piece[end] = token_id
                    back_start[end] = start
            if best[end] == float("-inf"):
                # No piece ends here; consume a single character as <unk>.
                start = end - 1
                if best[start] != float("-inf"):
                    best[end] = best[start] + _UNK_PENALTY
                    back_piece[end] = self._vocab.unk_id
                    back_start[end] = start

        ids: list[int] = []
        cursor = n
        while cursor > 0:
            token_id = back_piece[cursor]
            start = back_start[cursor]
            if token_id < 0 or start < 0:
                break
            ids.append(token_id)
            cursor = start
        ids.reverse()
        return ids

    def decode(self, ids: Iterable[int]) -> str:
        out: list[str] = []
        for token_id in ids:
            if token_id in (self._vocab.eos_id, self._vocab.bos_id, self._vocab.pad_id):
                continue
            piece = self.piece_of(token_id)
            if piece is None:
                continue
            if piece.startswith(_SPECIAL_PREFIX) and piece.endswith(_SPECIAL_SUFFIX):
                continue
            out.append(piece)
        return "".join(out).replace(SPACE_MARKER, " ").strip()

    def _normalize(self, text: str) -> str:
        """NFKC-fold, collapse whitespace, then escape spaces the way SentencePiece does.

        The NFKC pass is not cosmetic. SentencePiece vocabularies are trained on normalised
        text, so full-width punctuation is absent from them: the full-width forms of `!?~...-()`
        all miss and fall through to `<unk>`, while their ASCII forms are present. The
        recogniser emits full-width punctuation because that is what is printed in the bubble,
        which means without this nearly every line of dialogue loses its terminal mark.

        Japanese-specific punctuation (corner brackets, the ideographic comma and full stop,
        the long vowel mark) is in the vocabulary already and NFKC leaves it alone, so nothing
        is lost by folding.
        """
        folded = unicodedata.normalize("NFKC", text)
        collapsed = _WHITESPACE_RUN.sub(" ", folded.strip())
        if not collapsed:
            return ""
        return SPACE_MARKER + collapsed.replace(" ", SPACE_MARKER)


def load_recognizer_vocab(path: Path | str) -> list[str]:
    """Recogniser vocabularies are a flat JSON array of token strings in id order."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict):
        # A `{token: id}` mapping is the other common dump; invert it into id order.
        ordered = sorted(data.items(), key=lambda kv: kv[1])
        return [token for token, _ in ordered]
    return list(data)
