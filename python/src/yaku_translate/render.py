"""Draws a `PageTranslation` onto a copy of the page image.

A port of `yaku.translation.render.TranslationRenderer`. On Android, baking the text into the
bitmap rather than overlaying the viewer means zoom, pan, double-page splitting and the
cropped-borders path all keep working untouched; here it simply means the output is an
ordinary image file.

The lettering follows the conventions a typesetter uses: the translation goes *inside* the
speech balloon rather than onto a panel laid over it, set in one size for the whole page,
centred, upper case, and broken into lines of similar length.

Android's Canvas/Paint become Pillow's ImageDraw and a FreeType font; the flood fill that
finds a balloon walks the same 4-connected region of light pixels, in runs rather than one
pixel at a time so that numpy can do the scanning.
"""

from __future__ import annotations

import os
from collections.abc import Sequence
from dataclasses import dataclass
from functools import lru_cache
from math import sqrt
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .model import BoxF, PageTranslation

RGB = tuple[int, int, int]

_FIT_ITERATIONS = 9
_BALANCE_ITERATIONS = 12
_BALLOON_MEASURE = 0.80
"""Fraction of a balloon's width a line may use; the shape narrows away from the middle."""

_FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
)

LETTERING_FONT = "fonts/ComicNeue-Bold.ttf"
"""What the Android app letters with, under `app/src/main/assets`. Found automatically when
this package is run from a checkout of the repository."""


def default_font_path() -> Path | None:
    """The app's lettering font when running from a checkout, else something serviceable.

    `YAKU_TRANSLATE_FONT` overrides. Nothing is bundled with this package: the font is
    already in the repository, and shipping a second copy would only let the two drift.
    """
    override = os.environ.get("YAKU_TRANSLATE_FONT")
    if override and Path(override).exists():
        return Path(override)

    for parent in Path(__file__).resolve().parents:
        candidate = parent / "app" / "src" / "main" / "assets" / LETTERING_FONT
        if candidate.exists():
            return candidate

    return next((Path(p) for p in _FONT_CANDIDATES if Path(p).exists()), None)


@dataclass(frozen=True)
class RenderStyle:
    bubble_color: RGB = (255, 255, 255)
    text_color: RGB = (0, 0, 0)

    corner_radius: float = 18.0
    """Sized for a manga page, which is 1200-2000px across - not for screen density. Only the
    fallback plaque uses these; lettering inside a balloon needs no panel of its own."""
    padding: float = 16.0

    min_text_size: float = 12.0
    """The floor for the page-wide size. Every block shares one size, so the tightest balloon
    sets it for the rest; below this the page stops being worth reading and clipping the odd
    cramped block is the better trade."""
    max_text_size: float = 72.0
    line_spacing_multiplier: float = 1.06

    horizontal_aspect: float = 2.5
    """Width-to-height ratio aimed for when re-setting a vertical column horizontally."""
    max_width_growth: float = 3.0
    """Ceiling on that widening, so a narrow column cannot become a banner across the page."""

    uppercase: bool = True
    """Comics set dialogue in capitals, and it is what the eye expects to find in a balloon."""

    light_threshold: int = 200
    """Above this luma a pixel counts as balloon paper rather than ink or artwork."""
    max_balloon_page_fraction: float = 0.25
    """A flood larger than this escaped the balloon, and is discarded."""
    balloon_clearance: float = 8.0
    """How far the lettering stays clear of the balloon outline, in page pixels."""

    font_path: Path | None = None
    """Overrides the font resolved by `default_font_path`."""


@lru_cache(maxsize=256)
def _load_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


class _Fonts:
    """Hands out a font at a given size.

    Android changes one Paint's `textSize`; FreeType wants a distinct face per size, so these
    are cached. Sizes are taken to whole pixels, which is the granularity a rasteriser works
    at anyway and keeps the cache from filling with near-duplicates during the size search.
    """

    def __init__(self, path: Path) -> None:
        self._path = str(path)

    def at(self, size: float) -> ImageFont.FreeTypeFont:
        return _load_font(self._path, max(round(size), 1))


@dataclass(frozen=True)
class _Placement:
    source: BoxF
    balloon: Balloon | None
    text: str
    inside_claimed: bool


@dataclass(frozen=True)
class _Layout:
    lines: list[str]
    line_height: float
    center_x: float
    top: float
    width: float
    height: float
    plaque: bool


class TranslationRenderer:
    def __init__(self, style: RenderStyle = RenderStyle()) -> None:
        self._style = style
        path = style.font_path or default_font_path()
        if path is None:
            raise RuntimeError(
                "No lettering font found. Pass RenderStyle(font_path=...), set "
                "YAKU_TRANSLATE_FONT, or run from a checkout that has "
                f"app/src/main/assets/{LETTERING_FONT}."
            )
        self._fonts = _Fonts(Path(path))

    def render(self, page: Image.Image, translation: PageTranslation) -> Image.Image:
        style = self._style
        output = page.convert("RGB").copy()

        texts = [
            (block.box, block.translated_text)
            for block in translation.blocks
            if block.translated_text and block.translated_text.strip()
        ]
        if not texts:
            return output

        # Search the page before painting anything on it. Clearing the source text first
        # seems tidier - it saves filling the glyph-shaped holes in the region - but the
        # detector's box is a rectangle around lettering that the balloon curves away from,
        # so wiping it paints through the outline and lets the flood escape into the panel.
        balloons = BalloonFinder(output, style)
        placements = [
            _Placement(
                source=box,
                balloon=balloons.around(box, index + 1),
                text=text.upper() if style.uppercase else text,
                inside_claimed=balloons.claimed_by_another(box, index + 1),
            )
            for index, (box, text) in enumerate(texts)
        ]
        # A block with no balloon of its own, sitting in one another block already letters,
        # is a fragment the detector split off rather than a second line of dialogue. Left
        # alone it takes the plaque path, and that plaque is drawn from a box wide enough to
        # paint straight through the balloon outline - a stray word and a broken balloon.
        placements = [p for p in placements if not (p.balloon is None and p.inside_claimed)]

        # A balloon is cleared in full, which removes the source text along with it. A block
        # with no balloon has only its own box to go on.
        balloons.erase_found(output, style.bubble_color)

        draw = ImageDraw.Draw(output)
        for placement in placements:
            if placement.balloon is None:
                draw.rounded_rectangle(
                    _to_xy(placement.source),
                    radius=style.corner_radius,
                    fill=style.bubble_color,
                )

        size = self._shared_text_size(placements, output.width, output.height)
        font = self._fonts.at(size)
        for placement in placements:
            self._draw(draw, placement, font, output.width, output.height)
        return output

    # -- sizing ------------------------------------------------------------------------

    def _shared_text_size(self, placements: Sequence[_Placement], page_width: int, page_height: int) -> float:
        """The largest size at which every block on the page still fits where it has to go.

        Sizing each balloon independently maximises each in isolation and produces a page
        where a three-word retort is set twice as large as the sentence beside it - the eye
        reads the size as emphasis the story never intended. Letterers set a page in one size
        for that reason. The cost is that the tightest balloon governs the rest, which is
        what the floor is for.
        """
        low = self._style.min_text_size
        high = self._style.max_text_size

        for _ in range(_FIT_ITERATIONS):
            mid = (low + high) / 2.0
            font = self._fonts.at(mid)
            if all(self._primary(p, font, page_width, page_height) is not None for p in placements):
                low = mid
            else:
                high = mid
        return low

    def _primary(
        self, placement: _Placement, font: ImageFont.FreeTypeFont, page_width: int, page_height: int
    ) -> _Layout | None:
        """Where a block belongs: inside its balloon, or on a plaque when it has none.

        This is what the size search asks, and it deliberately offers no second chance.
        Letting a block fall through to a plaque *while sizing* lets the page grow until the
        lettering no longer fits the balloons - every balloon still passes, on a plaque - and
        the result is a line set wider than the shape it is supposed to sit in.
        """
        return self._layout(
            placement,
            font,
            page_width,
            page_height,
            use_balloon=placement.balloon is not None,
            strict=True,
        )

    def _layout(
        self,
        placement: _Placement,
        font: ImageFont.FreeTypeFont,
        page_width: int,
        page_height: int,
        *,
        use_balloon: bool,
        strict: bool,
    ) -> _Layout | None:
        """Where this block's lines go at ``font``'s size, or None if they will not fit.

        Inside a balloon the limit is the shape itself, tested at the corners of the text
        against the interior that was flooded. Without one, the block falls back to a plaque
        and the limit is that rectangle.
        """
        style = self._style
        balloon = placement.balloon if (use_balloon and placement.balloon is not None) else None
        area = (
            balloon.bounds
            if balloon is not None
            else _horizontal_box(placement.source, float(page_width), float(page_height), style)
        )

        # Balloons are round: a line as wide as the bounding box only fits across the middle.
        # Starting narrower costs nothing, because the size search widens the text either way.
        measure = area.width * _BALLOON_MEASURE if balloon is not None else area.width - style.padding * 2
        if measure <= 0:
            return None

        lines = _wrap(placement.text, font, measure)
        ascent, descent = font.getmetrics()
        line_height = (ascent + descent) * style.line_spacing_multiplier
        width = max(font.getlength(line) for line in lines)
        height = line_height * len(lines)

        left = area.center_x - width / 2.0
        top = area.center_y - height / 2.0

        if strict:
            if balloon is not None:
                # Test a slightly larger rectangle than will be drawn, so the lettering keeps
                # clear of the outline instead of merely avoiding it.
                clear = style.balloon_clearance
                fits = (
                    balloon.contains(left - clear, top - clear)
                    and balloon.contains(left + width + clear, top - clear)
                    and balloon.contains(left - clear, top + height + clear)
                    and balloon.contains(left + width + clear, top + height + clear)
                )
                if not fits:
                    return None
            elif width > measure or height > area.height - style.padding * 2:
                return None

        return _Layout(
            lines=lines,
            line_height=line_height,
            center_x=area.center_x,
            top=top,
            width=width,
            height=height,
            plaque=balloon is None,
        )

    def _draw(
        self,
        draw: ImageDraw.ImageDraw,
        placement: _Placement,
        font: ImageFont.FreeTypeFont,
        page_width: int,
        page_height: int,
    ) -> None:
        style = self._style
        # At the size floor a cramped block may fit nowhere. Drawing it slightly over its
        # plaque is recoverable; dropping it deletes a line of dialogue from the page
        # silently.
        laid = (
            self._primary(placement, font, page_width, page_height)
            or self._layout(placement, font, page_width, page_height, use_balloon=False, strict=True)
            or self._layout(placement, font, page_width, page_height, use_balloon=False, strict=False)
        )
        if laid is None:
            return

        # Without a balloon there is nothing behind the text but artwork, so it needs a panel
        # of its own. Inside a balloon the interior has already been cleared, and a panel
        # would draw a box around lettering that is meant to look like it belongs there.
        if laid.plaque:
            draw.rounded_rectangle(
                [
                    laid.center_x - laid.width / 2.0 - style.padding,
                    laid.top - style.padding,
                    laid.center_x + laid.width / 2.0 + style.padding,
                    laid.top + laid.height + style.padding,
                ],
                radius=style.corner_radius,
                fill=style.bubble_color,
            )

        ascent, _descent = font.getmetrics()
        baseline = laid.top + ascent
        for line in laid.lines:
            # "ms" is horizontally centred on the baseline: Paint.Align.CENTER with the
            # baseline y that Android's drawText takes.
            draw.text((laid.center_x, baseline), line, font=font, fill=style.text_color, anchor="ms")
            baseline += laid.line_height


# --------------------------------------------------------------------------------------
# Line breaking
# --------------------------------------------------------------------------------------


def _wrap(text: str, font: ImageFont.FreeTypeFont, measure: float) -> list[str]:
    """Greedy wrap, then the narrowest measure that keeps the same number of lines.

    A greedy wrap fills each line to the limit and leaves the remainder on the last one, so a
    balloon ends with a full line above a single orphaned word. Re-wrapping as narrow as the
    line count allows evens them out, which is what gives set dialogue its symmetrical shape.
    """
    words = [w for w in text.split(" ") if w]
    if not words:
        return [text]

    greedy = _wrap_at(words, font, measure)
    low = 0.0
    high = measure
    best = greedy

    for _ in range(_BALANCE_ITERATIONS):
        mid = (low + high) / 2.0
        candidate = _wrap_at(words, font, mid)
        if len(candidate) <= len(greedy) and all(font.getlength(line) <= mid for line in candidate):
            best = candidate
            high = mid
        else:
            low = mid
    return best


def _wrap_at(words: Sequence[str], font: ImageFont.FreeTypeFont, measure: float) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if not current or font.getlength(candidate) <= measure:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def _horizontal_box(box: BoxF, page_width: float, page_height: float, style: RenderStyle) -> BoxF:
    """Reshapes a vertical source box into one the target language can be set in.

    Only reached when no balloon was found. The detector reports where the *source* text is,
    and Japanese is usually set vertically: a nine-character line arrives as a column about
    95px wide and 640px tall. Setting an English sentence in a measure that narrow breaks
    words mid-syllable and stacks them into a ragged ribbon.
    """
    if not box.is_vertical:
        return box

    width = min(sqrt(box.area * style.horizontal_aspect), box.width * style.max_width_growth, page_width)
    height = min(box.area / width, box.height)

    left = min(max(box.center_x - width / 2.0, 0.0), max(page_width - width, 0.0))
    top = min(max(box.center_y - height / 2.0, 0.0), max(page_height - height, 0.0))
    return BoxF(left, top, left + width, top + height)


def _to_xy(box: BoxF) -> list[float]:
    return [box.left, box.top, box.right, box.bottom]


# --------------------------------------------------------------------------------------
# Balloons
# --------------------------------------------------------------------------------------


class Balloon:
    """The interior of one speech balloon, held as a stamp in `BalloonFinder`'s ownership map."""

    def __init__(self, bounds: BoxF, stamp: int, owner: np.ndarray) -> None:
        self.bounds = bounds
        self._stamp = stamp
        self._owner = owner
        self._height, self._width = owner.shape

    def contains(self, x: float, y: float) -> bool:
        px, py = int(x), int(y)
        if px < 0 or py < 0 or px >= self._width or py >= self._height:
            return False
        return bool(self._owner[py, px] == self._stamp)


class BalloonFinder:
    """Finds the balloon a block of text sits in by flooding the light area around it.

    A balloon is a pale region closed by an outline, so its interior is exactly the light
    pixels reachable from the text without crossing that outline. Nothing here understands
    what a balloon *is*; when the flood escapes - an open-ended caption, lettering laid
    straight over artwork, a page too dark to threshold - it is abandoned and the caller
    falls back to drawing a panel.
    """

    def __init__(self, image: Image.Image, style: RenderStyle) -> None:
        self._style = style
        self._pixels = np.array(image.convert("RGB"), dtype=np.uint8)
        self._height, self._width = self._pixels.shape[:2]

        luma = self._pixels.astype(np.int32)
        self._light = (
            luma[:, :, 0] * 299 + luma[:, :, 1] * 587 + luma[:, :, 2] * 114
        ) // 1000 > style.light_threshold

        # Which balloon claimed each pixel, 0 for none. Doubles as the visited set. Kotlin
        # packs this into a ByteArray to keep it small on a phone, which caps a page at 127
        # blocks; there is no reason to accept that limit here.
        self._owner = np.zeros((self._height, self._width), dtype=np.int32)

        # Horizontal extent of the flood on each row, used to close it over its glyphs.
        self._row_min = np.empty(self._height, dtype=np.int64)
        self._row_max = np.empty(self._height, dtype=np.int64)

        self._max_area = int(self._width * self._height * style.max_balloon_page_fraction)
        self._found: set[int] = set()

    def around(self, box: BoxF, stamp: int) -> Balloon | None:
        seed = self._seed_in(box)
        if seed is None:
            return None

        self._row_min[:] = np.iinfo(np.int64).max
        self._row_max[:] = -1

        owner, light = self._owner, self._light
        width, height = self._width, self._height

        area = 0
        min_x, max_x = width, -1
        min_y, max_y = height, -1

        stack: list[tuple[int, int]] = [seed]
        while stack:
            x, y = stack.pop()
            if owner[y, x] != 0 or not light[y, x]:
                continue

            # The whole maximal run of free pixels through (x, y) is claimed at once. It is
            # the same 4-connected region Kotlin walks a pixel at a time, found by scanning
            # rows instead, which is what lets numpy do the work.
            free = light[y] & (owner[y] == 0)
            blocked = np.flatnonzero(~free[:x])
            x1 = int(blocked[-1]) + 1 if blocked.size else 0
            blocked = np.flatnonzero(~free[x + 1 :])
            x2 = x + int(blocked[0]) if blocked.size else width - 1

            owner[y, x1 : x2 + 1] = stamp
            area += x2 - x1 + 1
            # A flood loose in open paper claims the page; the balloon it was supposed to be
            # in is not that large, so past this it is not in a balloon any more.
            if area > self._max_area:
                return self._abandon(stamp)

            if x1 < min_x:
                min_x = x1
            if x2 > max_x:
                max_x = x2
            if y < min_y:
                min_y = y
            if y > max_y:
                max_y = y
            if x1 < self._row_min[y]:
                self._row_min[y] = x1
            if x2 > self._row_max[y]:
                self._row_max[y] = x2

            for neighbour_y in (y - 1, y + 1):
                if not 0 <= neighbour_y < height:
                    continue
                row = light[neighbour_y, x1 : x2 + 1] & (owner[neighbour_y, x1 : x2 + 1] == 0)
                if not row.any():
                    continue
                set_at = np.flatnonzero(row)
                starts = set_at[np.concatenate(([True], np.diff(set_at) > 1))]
                stack.extend((x1 + int(s), neighbour_y) for s in starts)

        # The flood follows the paper *around* the lettering, so every glyph is a hole in it.
        # Closing each row between its two extremes takes them in, which both cleans the
        # whole balloon and stops a corner test failing merely because it landed on a stroke.
        for y in range(min_y, max_y + 1):
            if self._row_max[y] < 0:
                continue
            span = slice(int(self._row_min[y]), int(self._row_max[y]) + 1)
            row = owner[y, span]
            owner[y, span] = np.where(row == 0, stamp, row)

        self._found.add(stamp)
        return Balloon(
            bounds=BoxF(float(min_x), float(min_y), float(max_x), float(max_y)),
            stamp=stamp,
            owner=owner,
        )

    def erase_found(self, image: Image.Image, color: RGB) -> None:
        """Repaints every interior that was found, clearing whatever the source erase missed."""
        if not self._found:
            return
        mask = np.isin(self._owner, np.fromiter(self._found, dtype=np.int32))
        self._pixels[mask] = np.asarray(color, dtype=np.uint8)
        image.paste(Image.fromarray(self._pixels, mode="RGB"))

    def claimed_by_another(self, box: BoxF, stamp: int) -> bool:
        """True when some other balloon already covers this box, so its text is already set."""
        left, top, right, bottom = self._clamped(box)
        region = self._owner[top : bottom + 1, left : right + 1]
        return bool(np.any((region != 0) & (region != stamp)))

    def _abandon(self, stamp: int) -> None:
        """Gives up on a flood and takes its claim back.

        The stamps are written as the flood advances, so a run that is abandoned half way
        still owns everything it reached. Left behind, `claimed_by_another` reads them as a
        balloon that was lettered - and every later block inside that area, which after an
        escape can be most of the page, is discarded as a duplicate. The dialogue would go
        missing with nothing to show why.
        """
        self._owner[self._owner == stamp] = 0
        return None

    def _clamped(self, box: BoxF) -> tuple[int, int, int, int]:
        left = min(max(int(box.left), 0), self._width - 1)
        right = min(max(int(box.right), 0), self._width - 1)
        top = min(max(int(box.top), 0), self._height - 1)
        bottom = min(max(int(box.bottom), 0), self._height - 1)
        return left, top, right, bottom

    def _seed_in(self, box: BoxF) -> tuple[int, int] | None:
        left, top, right, bottom = self._clamped(box)
        # Work outwards from the centre. The corners of a text box frequently fall outside
        # the balloon that encloses it - on a wide flat balloon the top-left corner lands on
        # the panel - and seeding there floods the panel instead, which is large enough to
        # look plausible and wrong enough to set the line outside the balloon entirely.
        center_x = (left + right) // 2
        center_y = (top + bottom) // 2
        for dy in range((bottom - top) // 2 + 2):
            for y in (center_y - dy, center_y + dy):
                if y < top or y > bottom:
                    continue
                for dx in range((right - left) // 2 + 2):
                    for x in (center_x - dx, center_x + dx):
                        if x < left or x > right:
                            continue
                        if self._owner[y, x] == 0 and self._light[y, x]:
                            return (x, y)
        return None
