from yaku_translate.tokenizer import (
    SPACE_MARKER,
    SentencePieceVocab,
    UnigramTokenizer,
    VocabPiece,
    load_recognizer_vocab,
)


def _tokenizer(*pieces: tuple[str, float]) -> UnigramTokenizer:
    base = [("<unk>", 0.0), ("<s>", 0.0), ("</s>", 0.0), ("<pad>", 0.0)]
    return UnigramTokenizer(
        SentencePieceVocab(pieces=tuple(VocabPiece(p, s) for p, s in base + list(pieces)))
    )


def test_viterbi_prefers_the_higher_scoring_segmentation():
    tokenizer = _tokenizer((f"{SPACE_MARKER}hello", -1.0), (f"{SPACE_MARKER}he", -3.0), ("llo", -3.0))
    assert [tokenizer.piece_of(i) for i in tokenizer.encode("hello")] == [f"{SPACE_MARKER}hello"]


def test_viterbi_falls_back_to_pieces_when_the_whole_word_is_absent():
    tokenizer = _tokenizer((f"{SPACE_MARKER}he", -3.0), ("llo", -3.0))
    assert [tokenizer.piece_of(i) for i in tokenizer.encode("hello")] == [f"{SPACE_MARKER}he", "llo"]


def test_nfkc_folding_keeps_full_width_punctuation_out_of_unk():
    # Without the fold the full-width mark misses every piece and the line loses its
    # terminal punctuation.
    tokenizer = _tokenizer((f"{SPACE_MARKER}hi", -1.0), ("!", -1.0))
    encoded = tokenizer.encode("hi\uff01")  # a full-width mark, as printed in a bubble
    assert [tokenizer.piece_of(i) for i in encoded] == [f"{SPACE_MARKER}hi", "!"]


def test_unknown_characters_become_unk_rather_than_derailing_the_line():
    tokenizer = _tokenizer((f"{SPACE_MARKER}hi", -1.0))
    ids = tokenizer.encode("hi\U0001f600")
    assert ids[0] == tokenizer.id_of(f"{SPACE_MARKER}hi")
    assert ids[-1] == tokenizer.unk_id


def test_whitespace_is_collapsed_and_escaped():
    tokenizer = _tokenizer((f"{SPACE_MARKER}a", -1.0), (f"{SPACE_MARKER}b", -1.0))
    assert [tokenizer.piece_of(i) for i in tokenizer.encode("  a \n b  ")] == [
        f"{SPACE_MARKER}a",
        f"{SPACE_MARKER}b",
    ]


def test_empty_input_encodes_to_nothing():
    assert _tokenizer(("x", -1.0)).encode("   ") == []


def test_decode_drops_specials_and_restores_spaces():
    tokenizer = _tokenizer((f"{SPACE_MARKER}Hello", -1.0), (f"{SPACE_MARKER}there", -1.0))
    ids = [
        tokenizer.bos_id,
        tokenizer.id_of(f"{SPACE_MARKER}Hello"),
        tokenizer.id_of(f"{SPACE_MARKER}there"),
        tokenizer.eos_id,
    ]
    assert tokenizer.decode(ids) == "Hello there"


def test_recognizer_vocab_reads_both_dump_shapes(tmp_path):
    flat = tmp_path / "flat.json"
    flat.write_text('["a","b","c"]', encoding="utf-8")
    mapping = tmp_path / "map.json"
    mapping.write_text('{"c":2,"a":0,"b":1}', encoding="utf-8")
    assert load_recognizer_vocab(flat) == ["a", "b", "c"]
    assert load_recognizer_vocab(mapping) == ["a", "b", "c"]
