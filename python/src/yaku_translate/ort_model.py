"""Thin wrapper over an ONNX Runtime session.

A port of `yaku.translation.engine.onnx.OrtModel`. Sessions are expensive to create (tens to
hundreds of ms) and hold a large native allocation, so the pipeline creates them once and
keeps them alive for as long as translation is switched on, then closes them at the end.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import onnxruntime as ort


def default_threads() -> int:
    """Half the machine's cores, capped - the same shape as the Android default.

    The cap is not about politeness. ONNX Runtime's intra-op pool spends more time
    synchronising than computing on the small tensors this pipeline runs.
    """
    cores = os.cpu_count() or 2
    return min(max(cores // 2, 1), 4)


class OrtModel:
    def __init__(self, session: ort.InferenceSession) -> None:
        self._session = session
        self.input_names: tuple[str, ...] = tuple(i.name for i in session.get_inputs())
        self.output_names: tuple[str, ...] = tuple(o.name for o in session.get_outputs())

    @classmethod
    def open(cls, path: Path | str, threads: int) -> OrtModel:
        path = Path(path)
        if not path.exists():
            raise FileNotFoundError(f"Model file missing: {path.name}")

        options = ort.SessionOptions()
        options.intra_op_num_threads = max(threads, 1)
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        # The CPU arena keeps every block it has ever allocated, so a run of pages never
        # gives memory back: measured on Android across one chapter the native heap went from
        # 141MB two minutes in to 593MB at four, and the process was killed at six while
        # still in the foreground. Pages differ in size, so the arena cannot reuse much of
        # what it holds anyway, and the memory pattern planner has little to plan for. Both
        # cost some allocation speed per page and keep the footprint flat, which is what lets
        # a long run finish at all.
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False

        return cls(ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"]))

    # -- name resolution ---------------------------------------------------------------

    def resolve_input(self, *candidates: str) -> str:
        """Picks the first of ``candidates`` the graph actually exposes, falling back to the
        first declared input.

        Export toolchains disagree on naming (`pixel_values` vs `input`), and a pack author
        should not have to spell every name out in the manifest to use a stock export.
        """
        return next((c for c in candidates if c in self.input_names), self.input_names[0])

    def resolve_output(self, *candidates: str) -> str:
        return next((c for c in candidates if c in self.output_names), self.output_names[0])

    def has_input(self, name: str) -> bool:
        return name in self.input_names

    def first_input_matching(self, *names: str) -> str | None:
        return next((n for n in names if n in self.input_names), None)

    # -- inference ---------------------------------------------------------------------

    def run_all(self, inputs: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
        """Runs the session and returns every output by name.

        The float path below carries one tensor, which suits a detector whose whole answer is
        a probability map. One that returns boxes has three - labels, boxes and scores - and
        the labels are integers, so they cannot come back through a float array at all.
        """
        results = self._session.run(None, inputs)
        return dict(zip(self.output_names, results, strict=True))

    def run(self, inputs: dict[str, np.ndarray], output_name: str | None = None) -> np.ndarray:
        """Runs the session and returns the named output.

        Kotlin hands back a flat float array plus its shape because it has to copy out of the
        native buffer before it is released; numpy already owns its array, so the shape rides
        along on it.
        """
        if output_name is not None and output_name in self.output_names:
            requested = [output_name]
        else:
            requested = [self.output_names[0]]
        return self._session.run(requested, inputs)[0]

    def close(self) -> None:
        self._session = None  # type: ignore[assignment]

    def __enter__(self) -> OrtModel:
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


def float_tensor(data: np.ndarray, shape: tuple[int, ...]) -> np.ndarray:
    return np.ascontiguousarray(data, dtype=np.float32).reshape(shape)


def long_tensor(data, shape: tuple[int, ...]) -> np.ndarray:
    return np.ascontiguousarray(data, dtype=np.int64).reshape(shape)
