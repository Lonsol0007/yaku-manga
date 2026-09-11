#!/usr/bin/env python3
"""
Run a whole pack - detector, recogniser and translator - over a synthetic manga page.

`smoke_test.py` covers the translator, which is the half that needs no image. This covers the
other half: whether the detector finds bubbles, whether its boxes crop to something the
recogniser can read, and whether the three stages agree on coordinates.

It mirrors the Kotlin faithfully, including the proximity merge that reassembles a vertical
column of kana into one block, and it loads each model once - the app keeps its sessions for the
life of the reader, so creating one per box would measure ONNX Runtime startup instead of
inference.

The page is generated rather than downloaded so the expected text is known exactly and no
copyrighted artwork is needed. Printed manga has screen tone, irregular lettering and art
crossing the bubbles, so treat this as a connectivity and latency check, not an accuracy score.

    python page_test.py out/ja-en-base
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

from smoke_test import UnigramTokenizer, resolve

FONT = r"C:\Windows\Fonts\YuGothM.ttc"
PAGE = (1200, 1700)

# Mirrors OnnxTextDetector.
MERGE_SLOP = 3.5
MERGE_MAX_SIZE_RATIO = 3.0
MAX_BOX_AREA_RATIO = 0.12  # mirrors detector_max_box_area_ratio

BUBBLES = [
    ("おはよう", (300, 260), False),
    ("何を言っているんだ", (860, 420), True),
    ("ちょっと待って", (400, 900), True),
    ("この街には秘密が多い", (820, 1300), False),
]


def make_page() -> Image.Image:
    page = Image.new("RGB", PAGE, "white")
    draw = ImageDraw.Draw(page)
    for box in [(60, 60, 1140, 700), (60, 740, 1140, 1180), (60, 1220, 1140, 1640)]:
        draw.rectangle(box, outline="black", width=6)

    font = ImageFont.truetype(FONT, 46)
    for text, (cx, cy), vertical in BUBBLES:
        if vertical:
            glyphs = list(text)
            height = 56 * len(glyphs)
            draw.ellipse((cx - 70, cy - height // 2 - 40, cx + 70, cy + height // 2 + 40),
                         fill="white", outline="black", width=4)
            for i, ch in enumerate(glyphs):
                draw.text((cx, cy - height // 2 + i * 56), ch, font=font, fill="black", anchor="ma")
        else:
            width = font.getlength(text)
            draw.ellipse((cx - width // 2 - 50, cy - 60, cx + width // 2 + 50, cy + 60),
                         fill="white", outline="black", width=4)
            draw.text((cx, cy), text, font=font, fill="black", anchor="mm")
    return page


def letterbox(image: Image.Image, size: int, mean, std):
    scale = min(size / image.width, size / image.height)
    w, h = max(1, int(image.width * scale)), max(1, int(image.height * scale))
    pad_x, pad_y = (size - w) // 2, (size - h) // 2
    canvas = Image.new("RGB", (size, size), "white")
    canvas.paste(image.resize((w, h), Image.LANCZOS), (pad_x, pad_y))
    a = np.asarray(canvas).astype(np.float32) / 255.0
    a = (a - np.array(mean, np.float32)) / np.array(std, np.float32)
    return np.transpose(a, (2, 0, 1))[None], scale, pad_x, pad_y


def intersects(a, b, slop_x, slop_y) -> bool:
    return not (a[2] + slop_x < b[0] or b[2] + slop_x < a[0] or
                a[3] + slop_y < b[1] or b[3] + slop_y < a[1])


def merge_neighbours(boxes: list, max_area: float = float('inf')) -> list:
    """Port of OnnxTextDetector.mergeNeighbours - vertical kana arrive as separate blobs."""
    working = [list(b) for b in boxes]
    merged = True
    while merged:
        merged = False
        for i in range(len(working)):
            for j in range(i + 1, len(working)):
                a, b = working[i], working[j]
                aw, ah = a[2] - a[0], a[3] - a[1]
                bw, bh = b[2] - b[0], b[3] - b[1]
                # mirrors OnnxTextDetector: reach from the short side of each box
                reach = min(min(aw, ah), min(bw, bh)) * MERGE_SLOP
                if not intersects(a, b, reach, reach):
                    continue
                # mirrors OnnxTextDetector: compare lettering size, not line length
                ta, tb = min(aw, ah), min(bw, bh)
                if max(ta, tb) / max(1.0, min(ta, tb)) > MERGE_MAX_SIZE_RATIO:
                    continue
                union = [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])]
                # mirrors OnnxTextDetector: refuse a merge that would run away
                if (union[2] - union[0]) * (union[3] - union[1]) > max_area:
                    continue
                working[i] = union
                working.pop(j)
                merged = True
                break
            if merged:
                break
    return [tuple(b) for b in working]


def detect(session, config: dict, page: Image.Image):
    size = int(config.get("detector_input_size", 960))
    mean = config.get("detector_mean", [0.485, 0.456, 0.406])
    std = config.get("detector_std", [0.229, 0.224, 0.225])
    threshold = float(config.get("detector_threshold", 0.3))
    expand = float(config.get("detector_box_expand", 0.08))
    min_ratio = float(config.get("detector_min_area_ratio", 0.00015))

    tensor, scale, pad_x, pad_y = letterbox(page, size, mean, std)
    prob = np.squeeze(session.run([session.get_outputs()[0].name],
                                  {session.get_inputs()[0].name: tensor})[0])
    print(f"  probability map {prob.shape}, range {prob.min():.3f}..{prob.max():.3f}")

    labels, count = ndimage.label(prob > threshold)
    raw = []
    area = size * size
    for sl in ndimage.find_objects(labels):
        ys, xs = sl
        w, h = xs.stop - xs.start, ys.stop - ys.start
        if w * h < area * min_ratio:
            continue
        raw.append((float(xs.start), float(ys.start), float(xs.stop), float(ys.stop)))
    print(f"  {count} blobs -> {len(raw)} above min area")

    # These boxes are still in letterboxed model space, where the page occupies
    # (width * scale) x (height * scale) and the rest is padding. The Kotlin unmaps to page
    # coordinates before it filters, so the cap has to be expressed in this space to mean the
    # same fraction of the page.
    max_area = MAX_BOX_AREA_RATIO * (page.width * scale) * (page.height * scale)
    merged = [b for b in merge_neighbours(raw, max_area)
              if (b[2] - b[0]) * (b[3] - b[1]) <= max_area]
    print(f"  {len(merged)} after proximity merge")

    boxes = []
    for left, top, right, bottom in merged:
        # mirrors BoxF.expand: one distance, from the shorter side
        margin = min(right - left, bottom - top) * expand
        dx, dy = margin, margin
        boxes.append((
            max(0.0, (left - dx - pad_x) / scale),
            max(0.0, (top - dy - pad_y) / scale),
            min(float(page.width), (right + dx - pad_x) / scale),
            min(float(page.height), (bottom + dy - pad_y) / scale),
        ))
    boxes.sort(key=lambda b: (round(b[1] / 64), b[0]))
    return boxes


def recognize(enc, dec, vocab, config, page, box) -> str:
    size = int(config.get("recognizer_input_size", 224))
    mean = config.get("recognizer_mean", [0.5, 0.5, 0.5])
    std = config.get("recognizer_std", [0.5, 0.5, 0.5])

    crop = page.crop(tuple(int(v) for v in box)).resize((size, size), Image.LANCZOS)
    a = np.asarray(crop.convert("RGB")).astype(np.float32) / 255.0
    a = (a - np.array(mean, np.float32)) / np.array(std, np.float32)
    pixels = np.transpose(a, (2, 0, 1))[None]

    state = enc.run([resolve([o.name for o in enc.get_outputs()], "last_hidden_state", "output")],
                    {resolve([i.name for i in enc.get_inputs()], "pixel_values", "input"): pixels})[0]

    ids_name = resolve([i.name for i in dec.get_inputs()], "input_ids", "decoder_input_ids")
    state_name = resolve([i.name for i in dec.get_inputs()], "encoder_hidden_states", "encoder_outputs")
    out_name = resolve([o.name for o in dec.get_outputs()], "logits", "output")

    bos = vocab.index("[CLS]") if "[CLS]" in vocab else 2
    eos = vocab.index("[SEP]") if "[SEP]" in vocab else 3
    specials = {"[CLS]", "[SEP]", "[PAD]", "[UNK]"}

    ids = [bos]
    for _ in range(int(config.get("recognizer_max_tokens", 64))):
        logits = dec.run([out_name], {ids_name: np.array([ids], np.int64), state_name: state})[0]
        nxt = int(np.argmax(logits[0, -1]))
        if nxt == eos:
            break
        ids.append(nxt)
    return "".join(vocab[i].removeprefix("##") for i in ids[1:] if vocab[i] not in specials)


def translate(enc, dec, tok, config, text: str) -> str:
    if not text.strip():
        return ""
    enc_in = [i.name for i in enc.get_inputs()]
    dec_in = [i.name for i in dec.get_inputs()]
    ids_name = resolve(enc_in, "input_ids", "ids")
    mask_name = "attention_mask" if "attention_mask" in enc_in else None
    enc_out = resolve([o.name for o in enc.get_outputs()], "last_hidden_state", "output")
    d_ids = resolve(dec_in, "decoder_input_ids", "input_ids", "ids")
    d_state = resolve(dec_in, "encoder_hidden_states", "encoder_outputs")
    d_mask = "encoder_attention_mask" if "encoder_attention_mask" in dec_in else None
    d_out = resolve([o.name for o in dec.get_outputs()], "logits", "output")

    eos = tok.id_of(config.get("translator_eos_token", "</s>")) or tok.eos_id
    start = tok.id_of(config.get("translator_decoder_start_token") or "") or eos

    source = np.array([tok.encode(text) + [eos]], np.int64)
    mask = np.ones_like(source)
    feed = {ids_name: source}
    if mask_name:
        feed[mask_name] = mask
    state = enc.run([enc_out], feed)[0]

    ids = [start]
    for _ in range(int(config.get("translator_max_tokens", 128))):
        f = {d_ids: np.array([ids], np.int64), d_state: state}
        if d_mask:
            f[d_mask] = mask
        nxt = int(np.argmax(dec.run([d_out], f)[0][0, -1]))
        if nxt == eos:
            break
        ids.append(nxt)
    return tok.decode(ids[1:])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, nargs="?", default=Path("out/ja-en-base"))
    parser.add_argument("--save", type=Path, default=Path("page_test.png"))
    args = parser.parse_args()

    pack = args.pack.resolve()
    config = json.loads((pack / "pack.json").read_text(encoding="utf-8")).get("config", {})
    page = make_page()

    print(f"page {page.width}x{page.height}, {len(BUBBLES)} bubbles")
    print(f"expected: {[t for t, _, _ in BUBBLES]}")

    provider = ["CPUExecutionProvider"]
    t0 = time.time()
    det = ort.InferenceSession(str(pack / "detector.onnx"), providers=provider)
    r_enc = ort.InferenceSession(str(pack / "recognizer_encoder.onnx"), providers=provider)
    r_dec = ort.InferenceSession(str(pack / "recognizer_decoder.onnx"), providers=provider)
    t_enc = ort.InferenceSession(str(pack / "translator_encoder.onnx"), providers=provider)
    t_dec = ort.InferenceSession(str(pack / "translator_decoder.onnx"), providers=provider)
    print(f"\nsessions loaded in {time.time() - t0:.1f} s (once per reader, not per page)")

    r_vocab = json.loads((pack / "recognizer_vocab.json").read_text(encoding="utf-8"))
    tok = UnigramTokenizer(json.loads((pack / "translator_vocab.json").read_text(encoding="utf-8")))

    print("\n== detect ==")
    t0 = time.time()
    boxes = detect(det, config, page)
    detect_ms = (time.time() - t0) * 1000
    print(f"  {len(boxes)} boxes in {detect_ms:.0f} ms")

    overlay = page.copy()
    d = ImageDraw.Draw(overlay)
    for b in boxes:
        d.rectangle(b, outline=(220, 40, 40), width=4)
    overlay.save(args.save)
    print(f"  overlay -> {args.save}")

    print("\n== recognise + translate ==")
    rec_total = tr_total = 0.0
    for i, b in enumerate(boxes):
        t0 = time.time()
        ja = recognize(r_enc, r_dec, r_vocab, config, page, b)
        r_ms = (time.time() - t0) * 1000
        # Mirrors OnnxTranslationEngine: a box has to yield a letter, not merely
        # non-blank text, or a bubble outline read as "(" becomes a rendered overlay.
        if not any(ch.isalpha() for ch in ja):
            print(f"  [{i}] {tuple(int(v) for v in b)}")
            print(f"      skipped, no letters: {ja!r}  ({r_ms:.0f} ms)")
            rec_total += r_ms
            continue
        t0 = time.time()
        en = translate(t_enc, t_dec, tok, config, ja)
        t_ms = (time.time() - t0) * 1000
        rec_total += r_ms
        tr_total += t_ms
        print(f"  [{i}] {tuple(int(v) for v in b)}")
        print(f"      ja  {ja!r}  ({r_ms:.0f} ms)")
        print(f"      en  {en!r}  ({t_ms:.0f} ms)")

    n = max(1, len(boxes))
    print(f"\ndetect {detect_ms:.0f} ms | recognise {rec_total / n:.0f} ms/box"
          f" | translate {tr_total / n:.0f} ms/box")
    print(f"page total: {(detect_ms + rec_total + tr_total) / 1000:.1f} s for {len(boxes)} boxes")


if __name__ == "__main__":
    main()
