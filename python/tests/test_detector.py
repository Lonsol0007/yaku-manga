import numpy as np

from yaku_translate.detector import OnnxTextDetector, _reading_order_key, _smallest_containing
from yaku_translate.manifest import PackConfig
from yaku_translate.model import BoxF


def _detector(**config) -> OnnxTextDetector:
    detector = OnnxTextDetector.__new__(OnnxTextDetector)
    detector._config = PackConfig(**config)
    return detector


def _map(*rects, size=20, threshold_above=1.0):
    grid = np.zeros((size, size), dtype=np.float32)
    for top, left, bottom, right in rects:
        grid[top:bottom, left:right] = threshold_above
    return grid


def test_separate_blobs_become_separate_boxes():
    detector = _detector(detector_threshold=0.5)
    boxes = detector._extract_boxes(_map((1, 1, 3, 3), (1, 15, 3, 17)).reshape(-1), 20, 20, 20)
    assert len(boxes) == 2


def test_a_shape_connected_only_across_rows_is_one_component():
    detector = _detector(detector_threshold=0.5)
    grid = _map((1, 1, 3, 3), (3, 2, 5, 4))  # an L, touching diagonally-adjacent rows
    assert len(detector._extract_boxes(grid.reshape(-1), 20, 20, 20)) == 1


def test_diagonal_touching_alone_does_not_connect_4_connected_components():
    detector = _detector(detector_threshold=0.5)
    grid = np.zeros((20, 20), dtype=np.float32)
    grid[1, 1] = 1.0
    grid[2, 2] = 1.0
    assert len(detector._extract_boxes(grid.reshape(-1), 20, 20, 20)) == 2


def test_boxes_scale_up_when_the_map_is_smaller_than_the_input():
    detector = _detector(detector_threshold=0.5)
    boxes = detector._extract_boxes(_map((1, 1, 3, 3)).reshape(-1), 20, 20, 40)
    assert boxes[0] == BoxF(2.0, 2.0, 6.0, 6.0)


def test_pixels_below_the_threshold_are_not_text():
    detector = _detector(detector_threshold=0.5)
    grid = _map((1, 1, 3, 3), threshold_above=0.4)
    assert detector._extract_boxes(grid.reshape(-1), 20, 20, 20) == []


def test_columns_of_one_utterance_are_merged():
    # Two fragments of one vertical column, a full character of leading apart. Reach is the
    # smaller box's *thickness* times the slop - 33 x 3.5, or about 115px - which is what
    # closes a gap that scaling by the column's length never could.
    detector = _detector(detector_merge_slop=3.5, detector_merge_max_size_ratio=3.0)
    a = BoxF(100, 100, 133, 300)
    b = BoxF(100, 400, 133, 600)
    assert len(detector._merge_neighbours([a, b], max_area=1e9)) == 1


def test_fragments_too_far_apart_to_be_one_column_stay_apart():
    detector = _detector(detector_merge_slop=3.5, detector_merge_max_size_ratio=3.0)
    a = BoxF(100, 100, 133, 300)
    b = BoxF(100, 600, 133, 800)  # 300px of paper between them
    assert len(detector._merge_neighbours([a, b], max_area=1e9)) == 2


def test_a_caption_does_not_swallow_a_sound_effect_of_a_different_size():
    detector = _detector(detector_merge_slop=3.5, detector_merge_max_size_ratio=3.0)
    caption = BoxF(0, 0, 200, 20)
    effect = BoxF(0, 30, 200, 230)  # ten times as thick
    assert len(detector._merge_neighbours([caption, effect], max_area=1e9)) == 2


def test_a_merge_that_would_run_away_is_refused_before_it_happens():
    detector = _detector(detector_merge_slop=10.0, detector_merge_max_size_ratio=10.0)
    a = BoxF(0, 0, 50, 50)
    b = BoxF(0, 100, 50, 150)
    merged = detector._merge_neighbours([a, b], max_area=1000.0)
    assert len(merged) == 2


def test_reading_order_is_right_to_left_then_down():
    top_right = BoxF(400, 10, 500, 60)
    top_left = BoxF(10, 12, 110, 62)
    lower = BoxF(200, 400, 300, 450)
    ordered = sorted([top_left, lower, top_right], key=_reading_order_key)
    assert ordered == [top_right, top_left, lower]


def test_the_smallest_enclosing_balloon_wins():
    small = BoxF(90, 90, 130, 130)
    large = BoxF(0, 0, 200, 200)
    assert _smallest_containing([large, small], BoxF(100, 100, 120, 120)) is small


def test_text_outside_every_balloon_gets_none():
    assert _smallest_containing([BoxF(0, 0, 10, 10)], BoxF(100, 100, 120, 120)) is None
