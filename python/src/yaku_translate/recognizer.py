"""Vision-encoder / text-decoder OCR, the manga-ocr architecture.

A port of `yaku.translation.engine.onnx.OnnxTextRecognizer`. The encoder turns a 224x224 crop
into a sequence of hidden states; the decoder autoregressively emits token ids conditioned on
those states. Decoding is plain greedy - beam search roughly triples the cost for a barely
measurable quality gain on short bubble text, and this runs once per bubble.
"""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path

import numpy as np
from PIL import Image

from . import image_tensors
from .manifest import PackConfig
from .model import BoxF
from .ort_model import OrtModel, long_tensor

BOS = "[CLS]"
EOS = "[SEP]"
SPECIAL_TOKENS = frozenset({"[CLS]", "[SEP]", "[PAD]", "[UNK]", "<s>", "</s>", "<pad>", "<unk>"})


class OnnxTextRecognizer:
    def __init__(
        self,
        encoder: OrtModel,
        decoder: OrtModel,
        vocab: Sequence[str],
        config: PackConfig,
    ) -> None:
        self._encoder = encoder
        self._decoder = decoder
        self._vocab = list(vocab)
        self._config = config

        self._encoder_input = encoder.resolve_input("pixel_values", "input", "image")
        self._encoder_output = encoder.resolve_output("last_hidden_state", "output", "hidden_states")
        self._decoder_ids_input = decoder.resolve_input("input_ids", "decoder_input_ids", "ids")
        state_input = decoder.first_input_matching("encoder_hidden_states", "encoder_outputs")
        if state_input is None:
            state_input = next((n for n in decoder.input_names if n != self._decoder_ids_input), None)
        if state_input is None:
            raise RuntimeError("Recogniser decoder has no encoder-state input")
        self._decoder_state_input = state_input
        self._decoder_output = decoder.resolve_output("logits", "output")

        self._bos_id = self._vocab.index(BOS) if BOS in self._vocab else 2
        self._eos_id = self._vocab.index(EOS) if EOS in self._vocab else 3

    def recognize(self, page: Image.Image, box: BoxF) -> str:
        crop = _crop_of(page, box)
        if crop is None:
            return ""
        pixels = image_tensors.resized(
            crop,
            self._config.recognizer_input_size,
            self._config.recognizer_mean,
            self._config.recognizer_std,
        )
        state = self._encoder.run({self._encoder_input: pixels}, self._encoder_output)
        return self._decode_greedy(state)

    def _decode_greedy(self, state: np.ndarray) -> str:
        ids: list[int] = [self._bos_id]
        # The encoder state is identical at every step, so build the array once.
        state = np.ascontiguousarray(state, dtype=np.float32)

        for _ in range(self._config.recognizer_max_tokens):
            logits = self._decoder.run(
                {
                    self._decoder_ids_input: long_tensor(ids, (1, len(ids))),
                    self._decoder_state_input: state,
                },
                self._decoder_output,
            )
            token = _argmax_last_step(logits)
            if token == self._eos_id:
                return self._detokenize(ids)
            ids.append(token)
        return self._detokenize(ids)

    def _detokenize(self, ids: Sequence[int]) -> str:
        out: list[str] = []
        for token_id in ids:
            if not 0 <= token_id < len(self._vocab):
                continue
            token = self._vocab[token_id]
            if token in SPECIAL_TOKENS:
                continue
            out.append(token[2:] if token.startswith("##") else token)
        return "".join(out).strip()

    def close(self) -> None:
        self._encoder.close()
        self._decoder.close()

    def __enter__(self) -> OnnxTextRecognizer:
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    @staticmethod
    def load_vocab(path: Path | str) -> list[str]:
        from .tokenizer import load_recognizer_vocab

        return load_recognizer_vocab(path)


def _argmax_last_step(logits: np.ndarray) -> int:
    """logits arrive as [1, steps, vocab]; we only care about the final step."""
    return int(np.asarray(logits).reshape(-1, logits.shape[-1])[-1].argmax())


def _crop_of(page: Image.Image, box: BoxF) -> Image.Image | None:
    left = min(max(int(box.left), 0), page.width - 1)
    top = min(max(int(box.top), 0), page.height - 1)
    right = min(max(int(box.right), left + 1), page.width)
    bottom = min(max(int(box.bottom), top + 1), page.height)
    if right - left < 2 or bottom - top < 2:
        return None
    return page.crop((left, top, right, bottom))
