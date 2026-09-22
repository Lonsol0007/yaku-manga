from yaku_translate.model import BoxF, PageTranslation, TextBlock, TranslationLanguage


def test_thickness_is_the_short_side_whichever_way_the_line_runs():
    column = BoxF(0, 0, 95, 640)
    line = BoxF(0, 0, 640, 95)
    assert column.thickness == 95
    assert line.thickness == 95


def test_is_vertical_needs_a_margin_not_merely_taller():
    assert BoxF(0, 0, 10, 13).is_vertical
    assert not BoxF(0, 0, 10, 11).is_vertical


def test_expand_pads_by_the_short_side_so_a_column_is_not_over_extended():
    # 0.15 of the 95px width, both axes - not 0.15 of the 640px height vertically, which
    # would run past the text into the curved ends of the balloon.
    expanded = BoxF(100, 100, 195, 740).expand(0.15, 1000.0, 1000.0)
    assert expanded.left == 100 - 95 * 0.15
    assert expanded.top == 100 - 95 * 0.15
    assert expanded.right == 195 + 95 * 0.15
    assert expanded.bottom == 740 + 95 * 0.15


def test_expand_clamps_to_the_page():
    expanded = BoxF(2, 2, 40, 40).expand(0.5, 41.0, 41.0)
    assert (expanded.left, expanded.top) == (0.0, 0.0)
    assert (expanded.right, expanded.bottom) == (41.0, 41.0)


def test_intersects_honours_slop_on_each_axis():
    a = BoxF(0, 0, 10, 10)
    b = BoxF(20, 0, 30, 10)
    assert not a.intersects(b)
    assert a.intersects(b, slop_x=11)
    assert not a.intersects(b, slop_y=11)


def test_union_covers_both():
    assert BoxF(0, 0, 5, 5).union(BoxF(10, 10, 20, 20)) == BoxF(0, 0, 20, 20)


def test_language_lookup_and_model_codes():
    assert TranslationLanguage.from_code("ja") is TranslationLanguage.JAPANESE
    assert TranslationLanguage.JAPANESE.model_code == "jpn_Jpan"
    assert TranslationLanguage.from_code("klingon") is None


def test_has_content_ignores_blank_translations():
    box = BoxF(0, 0, 1, 1)
    blank = PageTranslation(
        1, 1, [TextBlock(box, 1.0, "x", "   ")], TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH, 0
    )
    filled = PageTranslation(
        1, 1, [TextBlock(box, 1.0, "x", "hi")], TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH, 0
    )
    assert not blank.has_content
    assert filled.has_content
