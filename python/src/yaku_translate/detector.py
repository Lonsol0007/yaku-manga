"""Finds the regions of a page that hold text.

Ports `yaku.translation.engine.TextDetector` and its two implementations. The two kinds
answer the same question and nothing downstream can tell them apart, but they arrive at it
so differently that they cannot share an implementation: a segmentation detector returns a
probability map that has to be thresholded and grouped into boxes and knows nothing about
speech balloons, while a detection model trained on comics returns the boxes directly - and
the balloons alongside them, which is worth far more, because the balloon is where the
translation has to be set.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Sequence

import numpy as np
from PIL import Image

from . import image_tensors
from .manifest import PackConfig
from .model import BoxF, TextBlock
from .ort_model import OrtModel, long_tensor

_ROW_BUCKET = 64.0


class TextDetector(ABC):
    @abstractmethod
    def detect(self, page: Image.Image) -> list[TextBlock]: ...

    @abstractmethod
    def close(self) -> None: ...

    def __enter__(self) -> TextDetector:
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


def _reading_order_key(box: BoxF) -> tuple[int, float]:
    """Right-to-left, top-to-bottom: the order a Japanese page is read in."""
    return (int(box.top / _ROW_BUCKET), -box.right)


class OnnxTextDetector(TextDetector):
    """Segmentation-based text detector (DBNet family).

    The model emits a per-pixel text probability map at the input resolution. We binarise it,
    label connected components, and turn each component into a box. This is the cheap half of
    the usual DB post-process - we skip polygon fitting because the recogniser only ever gets
    an axis-aligned crop anyway.
    """

    def __init__(self, model: OrtModel, config: PackConfig) -> None:
        self._model = model
        self._config = config

    def detect(self, page: Image.Image) -> list[TextBlock]:
        config = self._config
        size = config.detector_input_size
        letterboxed = image_tensors.letterbox(page, size, config.detector_mean, config.detector_std)

        probability = self._model.run(
            {config.detector_input_name: letterboxed.data}, config.detector_output_name
        )
        map_height = probability.shape[-2]
        map_width = probability.shape[-1]
        boxes = self._extract_boxes(
            probability.reshape(-1)[: map_width * map_height], map_width, map_height, size
        )

        page_area = float(page.width) * page.height
        min_area = config.detector_min_area_ratio * page_area
        max_area = config.detector_max_box_area_ratio * page_area

        mapped = [
            letterboxed.unmap(box, page.width, page.height).expand(
                config.detector_box_expand, float(page.width), float(page.height)
            )
            for box in boxes
        ]
        kept = [b for b in mapped if b.area >= min_area and b.width > 4.0 and b.height > 4.0]
        merged = self._merge_neighbours(kept, max_area)
        # A blob can be oversized on its own, without any merging to blame.
        final = [b for b in merged if b.area <= max_area]
        final.sort(key=_reading_order_key)
        return [TextBlock(box=b, confidence=1.0) for b in final]

    def _extract_boxes(
        self, prob: np.ndarray, map_width: int, map_height: int, input_size: int
    ) -> list[BoxF]:
        """Binarise the probability map and label 4-connected components.

        Kotlin walks the map with an explicit queue, one pixel at a time. Here the same
        components are found by scanning rows into runs and merging runs that touch across
        rows - it visits the same pixels and produces the same bounding boxes, in a form
        numpy can do most of the work for. The map may be emitted at a lower resolution than
        the input, so boxes scale back up.
        """
        mask = (
            np.asarray(prob, dtype=np.float32).reshape(map_height, map_width)
            >= self._config.detector_threshold
        )
        if not mask.any():
            return []

        scale_x = input_size / map_width
        scale_y = input_size / map_height

        # Union-find over horizontal runs: a run is a maximal span of set pixels in one row.
        parent: list[int] = []

        def find(node: int) -> int:
            while parent[node] != node:
                parent[node] = parent[parent[node]]
                node = parent[node]
            return node

        def union(a: int, b: int) -> None:
            root_a, root_b = find(a), find(b)
            if root_a != root_b:
                parent[max(root_a, root_b)] = min(root_a, root_b)

        runs: list[tuple[int, int, int]] = []  # (y, x_start, x_end) inclusive
        previous: list[int] = []  # run indices on the row above

        for y in range(map_height):
            row = mask[y]
            if not row.any():
                previous = []
                continue
            padded = np.concatenate(([False], row, [False]))
            edges = np.flatnonzero(padded[1:] != padded[:-1])
            starts, ends = edges[0::2], edges[1::2] - 1

            current: list[int] = []
            for x_start, x_end in zip(starts.tolist(), ends.tolist(), strict=True):
                index = len(runs)
                runs.append((y, x_start, x_end))
                parent.append(index)
                current.append(index)
                # 4-connectivity: touching means overlapping columns on adjacent rows.
                for above in previous:
                    _, a_start, a_end = runs[above]
                    if a_start <= x_end and x_start <= a_end:
                        union(index, above)
            previous = current

        bounds: dict[int, list[int]] = {}
        for index, (y, x_start, x_end) in enumerate(runs):
            root = find(index)
            box = bounds.get(root)
            if box is None:
                bounds[root] = [x_start, y, x_end, y]
            else:
                box[0] = min(box[0], x_start)
                box[1] = min(box[1], y)
                box[2] = max(box[2], x_end)
                box[3] = max(box[3], y)

        return [
            BoxF(
                left=min_x * scale_x,
                top=min_y * scale_y,
                right=(max_x + 1) * scale_x,
                bottom=(max_y + 1) * scale_y,
            )
            for min_x, min_y, max_x, max_y in bounds.values()
        ]

    def _merge_neighbours(self, boxes: Sequence[BoxF], max_area: float) -> list[BoxF]:
        """Joins boxes that are almost certainly one utterance.

        Vertical Japanese text comes out of the detector as one component per column, so a
        three-column bubble would otherwise be recognised as three unrelated fragments and
        translated out of order. Columns inside a bubble sit close together, so a small
        proximity merge recovers the whole bubble.
        """
        if len(boxes) < 2:
            return list(boxes)

        config = self._config
        working = list(boxes)
        merged = True
        while merged:
            merged = False
            for i in range(len(working)):
                for j in range(i + 1, len(working)):
                    a, b = working[i], working[j]
                    # Reach follows how thick the lettering is, not how long the line is.
                    # Scaling by width gave a 200px-wide caption 700px of reach at slop 3.5 -
                    # most of a panel - and a caption and a sound effect at opposite ends of
                    # the same panel became one box, which read as the sound effect alone and
                    # lost the caption entirely. A column of single glyphs, which is what the
                    # generous slop exists for, is as thick as one character either way.
                    reach = min(a.thickness, b.thickness) * config.detector_merge_slop
                    if not a.intersects(b, reach, reach):
                        continue
                    # Only merge lettering of a similar size; a caption should not swallow a
                    # nearby sound effect. Comparing heights asks the wrong question, because
                    # the two ends of one vertical column differ in length by however much of
                    # the sentence each holds.
                    ratio = max(a.thickness, b.thickness) / max(min(a.thickness, b.thickness), 1.0)
                    if ratio > config.detector_merge_max_size_ratio:
                        continue
                    # Refuse the merge that would run away rather than dropping the result
                    # afterwards; letting it happen first would swallow its neighbours on the
                    # way and take their dialogue with it.
                    union = a.union(b)
                    if union.area > max_area:
                        continue

                    working[i] = union
                    del working[j]
                    merged = True
                    break
                if merged:
                    break
        return working

    def close(self) -> None:
        self._model.close()


class OnnxComicDetector(TextDetector):
    """A detector trained on comics, which answers with boxes rather than a probability map.

    The segmentation detector reports where ink is and leaves everything else to be worked
    out: which blobs belong to the same line, which are artwork rather than lettering, and
    where the balloon around them might be. On a page of real artwork that guesswork fails -
    measured on one scan it returned twenty-two boxes covering most of the sheet, several of
    them single characters torn off a column.

    This model returns three kinds of thing directly: the balloons, the text inside them, and
    the text that stands on the artwork. The balloon is the valuable part, because that is
    where a translation has to be set and it cannot be derived from the text's own box.
    """

    INPUT_SIZE = 640

    INPUT_IMAGES = "images"
    INPUT_SIZES = "orig_target_sizes"
    OUTPUT_LABELS = "labels"
    OUTPUT_BOXES = "boxes"
    OUTPUT_SCORES = "scores"

    CLASS_BUBBLE = 0
    CLASS_TEXT_IN_BUBBLE = 1
    CLASS_TEXT_FREE = 2

    def __init__(self, model: OrtModel, config: PackConfig) -> None:
        self._model = model
        self._config = config

    def detect(self, page: Image.Image) -> list[TextBlock]:
        detections = self._infer(page)
        bubbles = [box for label, _score, box in detections if label == self.CLASS_BUBBLE]

        blocks = [
            TextBlock(
                box=box,
                confidence=score,
                # Only text the model put inside a balloon gets one. A sound effect lying
                # across the artwork has no balloon to be set in, and handing it the nearest
                # one would letter it into a balloon it has nothing to do with.
                bubble=_smallest_containing(bubbles, box) if label == self.CLASS_TEXT_IN_BUBBLE else None,
            )
            for label, score, box in detections
            if label in (self.CLASS_TEXT_IN_BUBBLE, self.CLASS_TEXT_FREE)
        ]
        blocks.sort(key=lambda block: _reading_order_key(block.box))
        return blocks

    def _infer(self, page: Image.Image) -> list[tuple[int, float, BoxF]]:
        images = image_tensors.planar_rgb(page, self.INPUT_SIZE)
        # The graph maps its own boxes back onto the page, so it has to be told the page
        # size; the boxes come out in page coordinates rather than in the 640px input's.
        sizes = long_tensor([page.width, page.height], (1, 2))

        outputs = self._model.run_all({self.INPUT_IMAGES: images, self.INPUT_SIZES: sizes})
        labels = _required(outputs, self.OUTPUT_LABELS).reshape(-1)
        boxes = _required(outputs, self.OUTPUT_BOXES).reshape(-1, 4)
        scores = _required(outputs, self.OUTPUT_SCORES).reshape(-1)

        width = float(page.width)
        height = float(page.height)
        found: list[tuple[int, float, BoxF]] = []
        for i in range(scores.shape[0]):
            score = float(scores[i])
            if score < self._config.detector_min_confidence:
                continue
            left, top, right, bottom = (float(v) for v in boxes[i])
            found.append(
                (
                    int(labels[i]),
                    score,
                    BoxF(
                        left=min(max(left, 0.0), width),
                        top=min(max(top, 0.0), height),
                        right=min(max(right, 0.0), width),
                        bottom=min(max(bottom, 0.0), height),
                    ),
                )
            )
        return found

    def close(self) -> None:
        self._model.close()


def _required(outputs: dict[str, np.ndarray], name: str) -> np.ndarray:
    if name not in outputs:
        raise RuntimeError(f"Detector has no output '{name}'")
    return outputs[name]


def _smallest_containing(bubbles: Sequence[BoxF], box: BoxF) -> BoxF | None:
    """The smallest balloon holding this text.

    Balloons overlap where characters talk over one another, and the enclosing one is the one
    the line belongs to. Taking the first match would sometimes set a line into the larger
    balloon lying behind its own.
    """
    holding = [b for b in bubbles if b.left <= box.center_x <= b.right and b.top <= box.center_y <= b.bottom]
    return min(holding, key=lambda b: b.area) if holding else None
