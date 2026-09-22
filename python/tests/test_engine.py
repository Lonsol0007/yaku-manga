import numpy as np
import pytest

import fakepack
from yaku_translate.engine import (
    OnnxTranslationEngine,
    is_proportionate,
    tidy,
)
from yaku_translate.model import TranslationLanguage, TranslationProgress
from yaku_translate.render import TranslationRenderer, default_font_path

# -- the heuristics that decide whether a translation is printable ----------------------


def test_the_subtitle_dash_and_a_doubled_line_are_collapsed():
    assert tidy("- Good morning. - Good morning.") == "Good morning."


def test_several_distinct_sentences_survive_intact():
    assert tidy("- Hello there. I am fine.") == "Hello there. I am fine."


def test_a_single_sentence_is_untouched_but_for_its_dash():
    assert tidy("- Just one.") == "Just one."


def test_tidy_of_nothing_is_nothing():
    assert tidy("") == ""
    assert tidy("   ") == ""


def test_a_decoder_that_looped_is_rejected_by_length():
    # 63 characters of source could not honestly produce 379 of English.
    assert not is_proportionate("x" * 379, "y" * 63)
    assert is_proportionate("x" * 180, "y" * 63)


def test_a_short_source_still_gets_an_allowance():
    assert is_proportionate("Yes, of course!", "はい")


# -- the whole pipeline, over real ONNX Runtime sessions -------------------------------


def test_the_pipeline_detects_reads_translates_and_letters_a_page(installed_pack, repository, page):
    seen: list[object] = []

    with OnnxTranslationEngine(installed_pack, repository, threads=1) as engine:
        assert engine.is_ready()
        assert engine.id == "test-pack"
        result = engine.translate_page(
            page, TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH, seen.append
        )

    assert result.page_width == page.width and result.page_height == page.height
    assert result.blocks, "the detector found no text on a page that has some"
    assert result.has_content

    block = next(b for b in result.blocks if b.is_translated)
    assert block.source_text == fakepack.RECOGNIZED_TEXT
    assert block.translated_text == fakepack.TRANSLATED_TEXT
    # The box the detector reported covers the lettering drawn at x 300-340, y 280-520.
    assert block.box.left < 320 < block.box.right
    assert block.box.top < 400 < block.box.bottom

    stages = [type(p).__name__ for p in seen]
    assert stages[0] == "LoadingModels"
    assert "Detecting" in stages and "Recognizing" in stages and "Translating" in stages
    assert isinstance(seen[-1], TranslationProgress.Done)


@pytest.mark.skipif(default_font_path() is None, reason="no lettering font available")
def test_a_translated_page_renders_with_the_text_set_on_it(installed_pack, repository, page):
    with OnnxTranslationEngine(installed_pack, repository, threads=1) as engine:
        result = engine.translate_page(page, TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH)

    rendered = TranslationRenderer().render(page, result)
    assert rendered.size == page.size
    # Something was drawn: the page is no longer what it was.
    assert not np.array_equal(np.asarray(rendered), np.asarray(page.convert("RGB")))


def test_warm_up_is_idempotent_and_close_can_be_repeated(installed_pack, repository):
    engine = OnnxTranslationEngine(installed_pack, repository, threads=1)
    engine.warm_up()
    engine.warm_up()
    engine.close()
    engine.close()


def test_a_page_with_no_text_yields_no_blocks(installed_pack, repository):
    from PIL import Image

    blank = Image.new("RGB", (640, 960), (255, 255, 255))
    with OnnxTranslationEngine(installed_pack, repository, threads=1) as engine:
        result = engine.translate_page(blank, TranslationLanguage.JAPANESE, TranslationLanguage.ENGLISH)
    assert result.blocks == []
    assert not result.has_content


def test_an_engine_whose_pack_is_gone_is_not_ready(installed_pack, repository):
    repository.delete(installed_pack.id)
    assert not OnnxTranslationEngine(installed_pack, repository).is_ready()
