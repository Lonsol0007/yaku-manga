"""Seq2seq machine translation over ONNX (NLLB-200 distilled and opus-MT both fit this shape).

A port of `yaku.translation.engine.onnx.OnnxTranslator`. The encoder runs once per source
string; the decoder runs autoregressively. As with the recogniser this is greedy decoding
without a KV cache, which means step *t* re-attends over *t* tokens. For bubble-length text
(rarely over 40 tokens) that is the right trade against carrying cache tensors through the
graph, which roughly doubles the exported model's input surface.
"""

from __future__ import annotations

import numpy as np

from .manifest import PackConfig
from .model import TranslationLanguage
from .ort_model import OrtModel, long_tensor
from .tokenizer import UnigramTokenizer


class OnnxTranslator:
    def __init__(
        self,
        encoder: OrtModel,
        decoder: OrtModel,
        tokenizer: UnigramTokenizer,
        config: PackConfig,
    ) -> None:
        self._encoder = encoder
        self._decoder = decoder
        self._tokenizer = tokenizer
        self._config = config

        self._encoder_ids_input = encoder.resolve_input("input_ids", "ids")
        self._encoder_mask_input = encoder.first_input_matching("attention_mask")
        self._encoder_output = encoder.resolve_output("last_hidden_state", "output", "encoder_outputs")

        self._decoder_ids_input = decoder.resolve_input("decoder_input_ids", "input_ids", "ids")
        state_input = decoder.first_input_matching("encoder_hidden_states", "encoder_outputs")
        if state_input is None:
            state_input = next((n for n in decoder.input_names if n != self._decoder_ids_input), None)
        if state_input is None:
            raise RuntimeError("Translator decoder has no encoder-state input")
        self._decoder_state_input = state_input
        self._decoder_mask_input = decoder.first_input_matching("encoder_attention_mask")
        self._decoder_output = decoder.resolve_output("logits", "output")

        self._eos_id = tokenizer.id_of(config.translator_eos_token)
        if self._eos_id is None:
            self._eos_id = tokenizer.eos_id
        self._bos_id = tokenizer.id_of(config.translator_bos_token)
        if self._bos_id is None:
            self._bos_id = tokenizer.bos_id
        start = (
            tokenizer.id_of(config.translator_decoder_start_token)
            if config.translator_decoder_start_token
            else None
        )
        self._decoder_start_id = start if start is not None else self._eos_id

    def translate(
        self,
        text: str,
        source: TranslationLanguage,
        target: TranslationLanguage,
    ) -> str:
        if not text.strip():
            return ""

        source_ids = self._build_source_ids(text, source)
        if not source_ids:
            return ""

        length = len(source_ids)
        mask = [1] * length

        inputs = {self._encoder_ids_input: long_tensor(source_ids, (1, length))}
        if self._encoder_mask_input is not None:
            inputs[self._encoder_mask_input] = long_tensor(mask, (1, length))
        state = self._encoder.run(inputs, self._encoder_output)

        return self._decode_greedy(state, mask, target)

    def _build_source_ids(self, text: str, source: TranslationLanguage) -> list[int]:
        encoded = self._tokenizer.encode(text)
        if not encoded:
            return []

        ids: list[int] = []
        # NLLB expects the *source* language token to lead the encoder input.
        if self._config.translator_uses_language_token:
            language_id = self._tokenizer.id_of(source.model_code)
            if language_id is not None:
                ids.append(language_id)
        ids.extend(encoded)
        ids.append(self._eos_id)
        return ids

    def _decode_greedy(
        self,
        state: np.ndarray,
        encoder_mask: list[int],
        target: TranslationLanguage,
    ) -> str:
        ids: list[int] = [self._decoder_start_id]
        if self._config.translator_uses_language_token:
            language_id = self._tokenizer.id_of(target.model_code)
            ids.append(language_id if language_id is not None else self._bos_id)
        prompt_length = len(ids)

        state = np.ascontiguousarray(state, dtype=np.float32)
        mask_tensor = (
            long_tensor(encoder_mask, (1, len(encoder_mask)))
            if self._decoder_mask_input is not None
            else None
        )

        for _ in range(self._config.translator_max_tokens):
            inputs = {
                self._decoder_ids_input: long_tensor(ids, (1, len(ids))),
                self._decoder_state_input: state,
            }
            if self._decoder_mask_input is not None and mask_tensor is not None:
                inputs[self._decoder_mask_input] = mask_tensor
            logits = self._decoder.run(inputs, self._decoder_output)
            token = _argmax_last_step(logits)
            if token == self._eos_id:
                return self._tokenizer.decode(ids[prompt_length:])
            ids.append(token)
        return self._tokenizer.decode(ids[prompt_length:])

    def close(self) -> None:
        self._encoder.close()
        self._decoder.close()

    def __enter__(self) -> OnnxTranslator:
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


def _argmax_last_step(logits: np.ndarray) -> int:
    return int(np.asarray(logits).reshape(-1, logits.shape[-1])[-1].argmax())
