import numpy as np
import pytest
from PIL import Image, ImageDraw

from yaku_translate.model import BoxF, PageTranslation, TextBlock, TranslationLanguage
from yaku_translate.render import (
    BalloonFinder,
    RenderStyle,
    TranslationRenderer,
    _horizontal_box,
    _wrap,
    default_font_path,
)

pytestmark = pytest.mark.skipif(default_font_path() is None, reason="no lettering font available")


# A balloon has to be a plausible fraction of its page: a flood larger than
# RenderStyle.max_balloon_page_fraction is treated as having escaped, so the page here is
# sized the way a real one is relative to a bubble on it.
BALLOON_TEXT = BoxF(460, 400, 540, 600)


def _balloon_page(width=1000, height=1400):
    image = Image.new("RGB", (width, height), (110, 110, 110))
    draw = ImageDraw.Draw(image)
    draw.ellipse([200, 300, 800, 700], fill=(255, 255, 255), outline=(0, 0, 0), width=6)
    draw.rectangle([480, 420, 520, 580], fill=(15, 15, 15))
    return image


def test_a_balloon_is_found_inside_its_outline():
    finder = BalloonFinder(_balloon_page(), RenderStyle())
    balloon = finder.around(BALLOON_TEXT, 1)
    assert balloon is not None
    # Inside the 6px outline, not outside it.
    assert balloon.bounds.left >= 200 and balloon.bounds.right <= 800
    assert balloon.contains(500, 500)
    assert not balloon.contains(50, 50)


def test_the_glyphs_inside_a_balloon_are_taken_in_by_closing_each_row():
    finder = BalloonFinder(_balloon_page(), RenderStyle())
    balloon = finder.around(BALLOON_TEXT, 1)
    # A point on a black stroke still belongs to the balloon, so a corner test lands.
    assert balloon.contains(500, 500)


def test_a_flood_that_escapes_is_abandoned_and_takes_its_claim_back():
    open_page = Image.new("RGB", (400, 400), (250, 250, 250))
    finder = BalloonFinder(open_page, RenderStyle())
    assert finder.around(BoxF(100, 100, 140, 140), 1) is None
    # Nothing is left claimed, or every later block would be discarded as a duplicate.
    assert not finder.claimed_by_another(BoxF(0, 0, 399, 399), 2)


def test_a_box_with_no_light_pixel_has_no_balloon():
    dark = Image.new("RGB", (200, 200), (10, 10, 10))
    assert BalloonFinder(dark, RenderStyle()).around(BoxF(50, 50, 100, 100), 1) is None


def test_erase_found_repaints_only_what_was_claimed():
    page = _balloon_page()
    finder = BalloonFinder(page, RenderStyle())
    finder.around(BALLOON_TEXT, 1)
    finder.erase_found(page, (255, 0, 0))
    pixels = np.asarray(page)
    assert tuple(pixels[500, 500]) == (255, 0, 0)  # inside the balloon, over a glyph
    assert tuple(pixels[20, 20]) == (110, 110, 110)  # artwork is untouched


def test_claimed_by_another_sees_a_neighbours_stamp_but_not_its_own():
    finder = BalloonFinder(_balloon_page(), RenderStyle())
    finder.around(BALLOON_TEXT, 1)
    inside = BoxF(480, 450, 520, 550)
    assert finder.claimed_by_another(inside, 2)
    assert not finder.claimed_by_another(inside, 1)


def test_wrap_balances_lines_rather_than_orphaning_the_last_word():
    from PIL import ImageFont

    font = ImageFont.truetype(str(default_font_path()), 24)
    text = "ONE TWO THREE FOUR FIVE SIX"
    greedy_measure = font.getlength("ONE TWO THREE FOUR FIVE")
    lines = _wrap(text, font, greedy_measure)
    lengths = [font.getlength(line) for line in lines]
    assert len(lines) >= 2
    # The balanced wrap keeps the shortest line within reach of the longest.
    assert min(lengths) > max(lengths) * 0.4


def test_wrap_never_loses_a_word():
    from PIL import ImageFont

    font = ImageFont.truetype(str(default_font_path()), 20)
    text = "ALPHA BRAVO CHARLIE DELTA"
    assert " ".join(_wrap(text, font, 120)).split() == text.split()


def test_a_vertical_column_is_reshaped_for_horizontal_setting():
    style = RenderStyle()
    column = BoxF(100, 100, 195, 740)  # 95 x 640, the usual vertical line
    reshaped = _horizontal_box(column, 1000.0, 1000.0, style)
    assert reshaped.width > column.width
    assert reshaped.width <= column.width * style.max_width_growth
    assert reshaped.height < column.height


def test_an_already_horizontal_box_is_left_alone():
    box = BoxF(0, 0, 300, 100)
    assert _horizontal_box(box, 1000.0, 1000.0, RenderStyle()) == box


def test_render_letters_inside_the_balloon_and_leaves_the_artwork():
    page = _balloon_page()
    translation = PageTranslation(
        page_width=page.width,
        page_height=page.height,
        blocks=[TextBlock(BALLOON_TEXT, 1.0, "こんにちは", "Hello there")],
        source_language=TranslationLanguage.JAPANESE,
        target_language=TranslationLanguage.ENGLISH,
        elapsed_millis=0,
    )
    rendered = TranslationRenderer().render(page, translation)
    out = np.asarray(rendered)

    assert tuple(out[20, 20]) == (110, 110, 110)  # artwork untouched
    assert (out[310:690, 210:790] == 0).all(axis=-1).any()  # lettering was drawn
    # The source column was cleared along with the rest of the balloon.
    assert not (out[420:580, 480:520] == 15).all()
    # The page handed in is never modified; the reader keeps its original.
    assert (np.asarray(page)[420:580, 480:520] == 15).all()


def test_a_page_with_nothing_translated_comes_back_unchanged():
    page = _balloon_page()
    translation = PageTranslation(
        page_width=page.width,
        page_height=page.height,
        blocks=[TextBlock(BALLOON_TEXT, 1.0, "こんにちは", None)],
        source_language=TranslationLanguage.JAPANESE,
        target_language=TranslationLanguage.ENGLISH,
        elapsed_millis=0,
    )
    assert np.array_equal(np.asarray(TranslationRenderer().render(page, translation)), np.asarray(page))


def test_text_with_no_balloon_gets_a_plaque():
    artwork = Image.new("RGB", (400, 400), (90, 90, 90))  # nothing light to flood
    translation = PageTranslation(
        page_width=400,
        page_height=400,
        blocks=[TextBlock(BoxF(150, 150, 250, 250), 1.0, "はい", "YES")],
        source_language=TranslationLanguage.JAPANESE,
        target_language=TranslationLanguage.ENGLISH,
        elapsed_millis=0,
    )
    out = np.asarray(TranslationRenderer().render(artwork, translation))
    assert (out == 255).all(axis=-1).any()  # a white plaque was drawn onto the artwork
