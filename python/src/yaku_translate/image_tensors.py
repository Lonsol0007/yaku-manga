"""Shared image -> NCHW float tensor plumbing for the detector and the recogniser.

A port of `yaku.translation.engine.onnx.ImageTensors`. Android's Bitmap/Canvas become a
Pillow image and numpy; the arithmetic is the same, and `LetterboxResult.unmap` is what maps
model-space boxes back onto the page.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from PIL import Image

from .model import BoxF

_DEFAULT_MEAN = (0.5, 0.5, 0.5)
_DEFAULT_STD = (0.5, 0.5, 0.5)


@dataclass(frozen=True)
class LetterboxResult:
    data: np.ndarray
    size: int
    scale: float
    pad_x: float
    pad_y: float

    def unmap(self, box: BoxF, source_width: int, source_height: int) -> BoxF:
        """Maps a box in letterboxed model space back to original image coordinates."""

        def clamp(value: float, limit: float) -> float:
            return min(max(value, 0.0), limit)

        return BoxF(
            left=clamp((box.left - self.pad_x) / self.scale, float(source_width)),
            top=clamp((box.top - self.pad_y) / self.scale, float(source_height)),
            right=clamp((box.right - self.pad_x) / self.scale, float(source_width)),
            bottom=clamp((box.bottom - self.pad_y) / self.scale, float(source_height)),
        )


def letterbox(
    source: Image.Image,
    size: int,
    mean: Sequence[float],
    std: Sequence[float],
) -> LetterboxResult:
    """Letterboxes ``source`` into a ``size`` x ``size`` square and returns it as NCHW floats.

    Letterboxing rather than stretching matters here: manga panels are wildly non-square, and
    a stretched bubble makes vertical kana look like horizontal ones to the recogniser.
    """
    scale = min(size / source.width, size / source.height)
    scaled_width = max(int(source.width * scale), 1)
    scaled_height = max(int(source.height * scale), 1)
    pad_x = (size - scaled_width) // 2
    pad_y = (size - scaled_height) // 2

    canvas = Image.new("RGB", (size, size), (255, 255, 255))
    canvas.paste(source.resize((scaled_width, scaled_height), Image.BILINEAR), (pad_x, pad_y))

    return LetterboxResult(
        data=to_nchw(canvas, mean, std),
        size=size,
        scale=scale,
        pad_x=float(pad_x),
        pad_y=float(pad_y),
    )


def resized(
    source: Image.Image,
    size: int,
    mean: Sequence[float],
    std: Sequence[float],
) -> np.ndarray:
    """Straight resize with no padding - used for text crops, already tightly framed."""
    if source.size != (size, size):
        source = source.resize((size, size), Image.BILINEAR)
    return to_nchw(source, mean, std)


def to_nchw(image: Image.Image, mean: Sequence[float], std: Sequence[float]) -> np.ndarray:
    """Planar RGB, normalised, shaped [1, 3, H, W]."""
    if image.mode != "RGB":
        image = image.convert("RGB")
    pixels = np.asarray(image, dtype=np.float32) / 255.0  # HWC
    mean_v = np.asarray(_pad3(mean, _DEFAULT_MEAN), dtype=np.float32)
    std_v = np.asarray(_pad3(std, _DEFAULT_STD), dtype=np.float32)
    normalised = (pixels - mean_v) / std_v
    return np.ascontiguousarray(normalised.transpose(2, 0, 1)[None, ...], dtype=np.float32)


def planar_rgb(image: Image.Image, size: int) -> np.ndarray:
    """Planar RGB scaled to 0..1, shaped [1, 3, size, size], with no mean or std applied.

    The comic detector's export normalises inside the graph, unlike the segmentation
    detector, so applying the pack's mean and standard deviation here would shift every
    channel.
    """
    if image.mode != "RGB":
        image = image.convert("RGB")
    if image.size != (size, size):
        image = image.resize((size, size), Image.BILINEAR)
    pixels = np.asarray(image, dtype=np.float32) / 255.0
    return np.ascontiguousarray(pixels.transpose(2, 0, 1)[None, ...], dtype=np.float32)


def _pad3(values: Sequence[float], fallback: Sequence[float]) -> tuple[float, float, float]:
    out = list(values) + list(fallback)
    return (float(out[0]), float(out[1]), float(out[2]))
