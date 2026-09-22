"""Builds a complete, tiny translation pack out of hand-written ONNX graphs.

The point is to exercise the real pipeline - real ONNX Runtime sessions, the real detector
post-process, the real greedy decoders, the real tokenizer and the real renderer - without a
few hundred megabytes of weights. The graphs have the same input and output signatures as the
exports a real pack ships, and answer deterministically:

  detector          dark pixels are text, so the boxes follow whatever the test page draws
  recogniser        emits a fixed Japanese string, one token per step, then EOS
  translator        emits "Hello there", then EOS

That is enough for a page to come out of `translate_page` with boxes, source text and a
translation, and be lettered onto the image.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

DETECTOR_INPUT_SIZE = 64
"""The real packs use 960; a test that only has to find rectangles does not need to."""

ENCODER_STATES = 4
ENCODER_DIM = 8

# -- recogniser vocabulary, in id order ------------------------------------------------
RECOGNIZER_VOCAB = ["[CLS]", "[SEP]", "[PAD]", "[UNK]", "こ", "ん", "に", "ち", "は"]
RECOGNIZED_TEXT = "こんにちは"

# -- translator vocabulary -------------------------------------------------------------
TRANSLATOR_PIECES = [
    "<unk>",  # 0
    "<s>",  # 1
    "</s>",  # 2
    "<pad>",  # 3
    "こ",  # 4
    "ん",  # 5
    "に",  # 6
    "ち",  # 7
    "は",  # 8
    "こんにちは",  # 9  - the whole greeting, which Viterbi should prefer
    "jpn_Jpan",  # 10
    "eng_Latn",  # 11
    "▁Hello",  # 12
    "▁there",  # 13
]
TRANSLATED_TEXT = "Hello there"

_EOS_ID = 2
_HELLO_ID = 12
_THERE_ID = 13


def build_pack(directory: Path) -> dict:
    """Writes every file of a pack into ``directory`` and returns its manifest entry."""
    directory.mkdir(parents=True, exist_ok=True)

    files = {
        "detector": _write(directory / "detector.onnx", _detector_model()),
        "recognizer_encoder": _write(directory / "rec_enc.onnx", _image_encoder_model()),
        "recognizer_decoder": _write(
            directory / "rec_dec.onnx",
            _decoder_model(
                ids_input="input_ids",
                state_input="encoder_hidden_states",
                mask_input=None,
                vocab_size=len(RECOGNIZER_VOCAB),
                # step T emits: 1->こ, 2->ん, 3->に, 4->ち, 5->は, 6->[SEP]
                next_token=[0, 4, 5, 6, 7, 8, 1] + [1] * 64,
            ),
        ),
        "recognizer_vocab": _write_json(directory / "rec_vocab.json", RECOGNIZER_VOCAB),
        "translator_encoder": _write(directory / "tr_enc.onnx", _text_encoder_model()),
        "translator_decoder": _write(
            directory / "tr_dec.onnx",
            _decoder_model(
                ids_input="decoder_input_ids",
                state_input="encoder_hidden_states",
                mask_input="encoder_attention_mask",
                vocab_size=len(TRANSLATOR_PIECES),
                # the prompt is [</s>, eng_Latn], so decoding starts at T=2
                next_token=[_EOS_ID, _EOS_ID, _HELLO_ID, _THERE_ID, _EOS_ID] + [_EOS_ID] * 64,
            ),
        ),
        "translator_vocab": _write_json(
            directory / "tr_vocab.json",
            {
                "model_type": "unigram",
                "unk_id": 0,
                "bos_id": 1,
                "eos_id": 2,
                "pad_id": 3,
                "pieces": [
                    # The whole greeting scores better than the sum of its characters, so
                    # Viterbi has something to actually choose between.
                    {"piece": piece, "score": -1.0 if len(piece) == 1 else -0.5}
                    for piece in TRANSLATOR_PIECES
                ],
            },
        ),
    }

    return {
        "id": "test-pack",
        "name": "Test pack",
        "description": "Hand-built ONNX graphs for the test suite.",
        "source_languages": ["ja"],
        "target_languages": ["en"],
        "config": {
            "config_version": 1,
            "detector_kind": "dbnet",
            "detector_input_size": DETECTOR_INPUT_SIZE,
            "detector_input_name": "input",
            "detector_output_name": "output",
            "detector_threshold": 0.5,
            "detector_box_expand": 0.15,
            "detector_min_area_ratio": 0.0005,
            "detector_max_box_area_ratio": 0.35,
            "detector_merge_slop": 1.5,
            # The graph takes pixels straight, so neither shifts nor scales them.
            "detector_mean": [0.0, 0.0, 0.0],
            "detector_std": [1.0, 1.0, 1.0],
            "recognizer_input_size": 224,
            "recognizer_max_tokens": 16,
            "translator_max_tokens": 16,
            "translator_uses_language_token": True,
            "translator_eos_token": "</s>",
            "translator_bos_token": "<s>",
        },
        **files,
    }


def write_manifest(directory: Path, pack: dict) -> Path:
    path = directory / "manifest.json"
    path.write_text(json.dumps({"version": 1, "packs": [pack]}, ensure_ascii=False), encoding="utf-8")
    return path


# --------------------------------------------------------------------------------------
# Graphs
# --------------------------------------------------------------------------------------


def _detector_model() -> onnx.ModelProto:
    """`input` [1,3,S,S] -> `output` [1,1,S,S]: a probability map that is high where dark.

    With the pack's mean at 0 and std at 1 the tensor is the image in 0..1, so one minus its
    channel mean is "how dark is this pixel" - which makes the test page's black rectangles
    exactly the regions the detector reports.
    """
    size = DETECTOR_INPUT_SIZE
    nodes = [
        helper.make_node("ReduceMean", ["input", "channel_axis"], ["grey"], keepdims=1),
        helper.make_node("Sub", ["one", "grey"], ["output"]),
    ]
    graph = helper.make_graph(
        nodes,
        "detector",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, size, size])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 1, size, size])],
        [
            numpy_helper.from_array(np.array([1], dtype=np.int64), "channel_axis"),
            numpy_helper.from_array(np.array(1.0, dtype=np.float32), "one"),
        ],
    )
    return _finish(graph)


def _image_encoder_model() -> onnx.ModelProto:
    """`pixel_values` [1,3,224,224] -> `last_hidden_state` [1,4,8]."""
    nodes = [
        helper.make_node("ReduceMean", ["pixel_values", "all_axes"], ["pooled"], keepdims=1),
        # Back to rank 3 before broadcasting: a real encoder's state is [1, S, D], and
        # Expand would otherwise leave the pooled tensor's rank 4 in place.
        helper.make_node("Reshape", ["pooled", "scalar_shape"], ["pooled_3d"]),
        helper.make_node("Expand", ["pooled_3d", "state_shape"], ["last_hidden_state"]),
    ]
    graph = helper.make_graph(
        nodes,
        "image_encoder",
        [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, [1, 3, 224, 224])],
        [
            helper.make_tensor_value_info(
                "last_hidden_state", TensorProto.FLOAT, [1, ENCODER_STATES, ENCODER_DIM]
            )
        ],
        [
            numpy_helper.from_array(np.array([1, 2, 3], dtype=np.int64), "all_axes"),
            numpy_helper.from_array(np.array([1, 1, 1], dtype=np.int64), "scalar_shape"),
            numpy_helper.from_array(
                np.array([1, ENCODER_STATES, ENCODER_DIM], dtype=np.int64), "state_shape"
            ),
        ],
    )
    return _finish(graph)


def _text_encoder_model() -> onnx.ModelProto:
    """`input_ids` [1,L] + `attention_mask` [1,L] -> `last_hidden_state` [1,L,8]."""
    nodes = [
        helper.make_node("Cast", ["input_ids"], ["as_float"], to=TensorProto.FLOAT),
        helper.make_node("Unsqueeze", ["as_float", "last_axis"], ["column"]),
        helper.make_node("Shape", ["input_ids"], ["ids_shape"]),
        helper.make_node("Concat", ["ids_shape", "dim"], ["state_shape"], axis=0),
        helper.make_node("Expand", ["column", "state_shape"], ["last_hidden_state"]),
    ]
    graph = helper.make_graph(
        nodes,
        "text_encoder",
        [
            helper.make_tensor_value_info("input_ids", TensorProto.INT64, [1, "L"]),
            helper.make_tensor_value_info("attention_mask", TensorProto.INT64, [1, "L"]),
        ],
        [helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, [1, "L", ENCODER_DIM])],
        [
            numpy_helper.from_array(np.array([2], dtype=np.int64), "last_axis"),
            numpy_helper.from_array(np.array([ENCODER_DIM], dtype=np.int64), "dim"),
        ],
    )
    return _finish(graph)


def _decoder_model(
    *,
    ids_input: str,
    state_input: str,
    mask_input: str | None,
    vocab_size: int,
    next_token: list[int],
) -> onnx.ModelProto:
    """ids [1,T] (+ state, + mask) -> `logits` [1,T,V], peaked at ``next_token[T]``.

    Only the last step is ever read, so every step carries the same distribution. Keying it
    on T is what makes the greedy loop walk a fixed script and stop where it should.
    """
    nodes = [
        helper.make_node("Shape", [ids_input], ["ids_shape"]),
        helper.make_node("Gather", ["ids_shape", "one_index"], ["steps"], axis=0),
        helper.make_node("Gather", ["script", "steps"], ["chosen"], axis=0),
        helper.make_node("OneHot", ["chosen", "vocab", "onehot_values"], ["row"], axis=-1),
        helper.make_node("Reshape", ["row", "row_shape"], ["row_3d"]),
        helper.make_node("Concat", ["ids_shape", "vocab_1d"], ["logits_shape"], axis=0),
        helper.make_node("Expand", ["row_3d", "logits_shape"], ["logits"]),
    ]
    inputs = [
        helper.make_tensor_value_info(ids_input, TensorProto.INT64, [1, "T"]),
        helper.make_tensor_value_info(state_input, TensorProto.FLOAT, [1, "S", ENCODER_DIM]),
    ]
    if mask_input is not None:
        inputs.append(helper.make_tensor_value_info(mask_input, TensorProto.INT64, [1, "S"]))

    graph = helper.make_graph(
        nodes,
        "decoder",
        inputs,
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, "T", vocab_size])],
        [
            numpy_helper.from_array(np.array([1], dtype=np.int64), "one_index"),
            numpy_helper.from_array(np.array(next_token, dtype=np.int64), "script"),
            numpy_helper.from_array(np.array(vocab_size, dtype=np.int64), "vocab"),
            numpy_helper.from_array(np.array([vocab_size], dtype=np.int64), "vocab_1d"),
            numpy_helper.from_array(np.array([0.0, 1.0], dtype=np.float32), "onehot_values"),
            numpy_helper.from_array(np.array([1, 1, vocab_size], dtype=np.int64), "row_shape"),
        ],
    )
    return _finish(graph)


def _finish(graph: onnx.GraphProto) -> onnx.ModelProto:
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 18)])
    model.ir_version = 9
    onnx.checker.check_model(model)
    return model


# --------------------------------------------------------------------------------------
# Files
# --------------------------------------------------------------------------------------


def _write(path: Path, model: onnx.ModelProto) -> dict:
    path.write_bytes(model.SerializeToString())
    return _entry(path)


def _write_json(path: Path, payload) -> dict:
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return _entry(path)


def _entry(path: Path) -> dict:
    data = path.read_bytes()
    return {
        "name": path.name,
        "url": path.resolve().as_uri(),
        "size_bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
