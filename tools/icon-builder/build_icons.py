#!/usr/bin/env python3
"""
Generate Yaku Manga's launcher and splash icons from the source artwork.

The source is a finished square icon: a red rounded plate carrying a logo mark above a
"yaku manga" wordmark. That whole image cannot simply become the launcher icon - Android's
adaptive icon crops the foreground layer to a circle 66dp across out of 108dp, so a wordmark
sitting near the bottom edge is guaranteed to be sliced off. Worse, the *plate* would be
double-rounded: the launcher applies its own mask on top of the corners already drawn in.

So the plate becomes a flat background colour, and only the mark - scaled into the safe zone -
becomes the foreground. The wordmark is dropped, which is the right call regardless: it is
illegible at 48dp.

    python build_icons.py
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO = Path(__file__).resolve().parents[2]
RES = REPO / "app" / "src" / "main" / "res"

# Adaptive icon geometry, in the 108dp layer's own units.
LAUNCHER_DP = 108
SAFE_FRACTION = 0.60  # mark occupies 60% of the layer; the guaranteed-visible circle is 66/108
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Android 12 splash: 288dp canvas, inner two-thirds visible.
SPLASH_DP = 288
SPLASH_FRACTION = 0.42


def load_plate(path: Path) -> tuple[np.ndarray, np.ndarray, str]:
    """Return (rgb, artwork mask, plate colour) for the source icon."""
    rgb = np.array(Image.open(path).convert("RGB"))

    # Background is the near-white surround, reachable from the image border. Flood-filling from
    # the corners separates it from the white *artwork*, which is the same colour but enclosed.
    light = (rgb > 200).all(axis=2)
    labels, _ = ndimage.label(light)
    border = set(labels[0, :]) | set(labels[-1, :]) | set(labels[:, 0]) | set(labels[:, -1])
    border.discard(0)
    background = np.isin(labels, list(border))

    plate = ~background
    artwork = light & plate

    reddish = rgb[plate & ~artwork]
    colour = reddish.reshape(-1, 3).mean(axis=0).astype(int)
    hex_colour = f"#{colour[0]:02X}{colour[1]:02X}{colour[2]:02X}"
    return rgb, artwork, hex_colour


def split_mark(artwork: np.ndarray) -> np.ndarray:
    """
    Isolate the logo mark from the wordmark beneath it.

    They are separated by a band of rows containing no artwork at all, so the widest empty run
    in the middle of the plate is the divider. Splitting on that rather than a hardcoded y keeps
    this working if the source art is ever re-exported at a different size or padding.
    """
    rows = artwork.sum(axis=1)
    filled = np.where(rows > 0)[0]
    top, bottom = filled.min(), filled.max()

    best_run, best_start, run_start = 0, None, None
    for y in range(top, bottom + 1):
        if rows[y] == 0:
            run_start = y if run_start is None else run_start
        else:
            if run_start is not None and y - run_start > best_run:
                best_run, best_start = y - run_start, run_start
            run_start = None

    if best_start is None:
        return artwork

    mark = artwork.copy()
    mark[best_start:, :] = False
    print(f"  mark/wordmark divider at y={best_start} (gap of {best_run}px)")
    return mark


def crop(mask: np.ndarray) -> tuple[int, int, int, int]:
    ys, xs = np.where(mask)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def render(mask: np.ndarray, canvas_px: int, fraction: float) -> Image.Image:
    """White artwork on transparent, scaled to `fraction` of the canvas and centred."""
    x0, y0, x1, y1 = crop(mask)
    cut = mask[y0:y1, x0:x1]

    alpha = Image.fromarray((cut * 255).astype(np.uint8), mode="L")
    target = int(canvas_px * fraction)
    scale = target / max(cut.shape)
    size = (max(1, int(cut.shape[1] * scale)), max(1, int(cut.shape[0] * scale)))
    alpha = alpha.resize(size, Image.LANCZOS)

    layer = Image.new("RGBA", (canvas_px, canvas_px), (255, 255, 255, 0))
    white = Image.new("RGBA", size, (255, 255, 255, 255))
    layer.paste(white, ((canvas_px - size[0]) // 2, (canvas_px - size[1]) // 2), alpha)
    return layer


def write_density_set(mask: np.ndarray, name: str, base_dp: int, fraction: float) -> None:
    for density, factor in DENSITIES.items():
        px = int(base_dp * factor)
        out_dir = RES / f"drawable-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)
        image = render(mask, px, fraction)
        path = out_dir / f"{name}.png"
        image.save(path, optimize=True)
        print(f"    {path.relative_to(REPO)}  {px}x{px}  {path.stat().st_size / 1024:.0f} KB")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()

    print(f"Reading {args.source}")
    _, artwork, plate_colour = load_plate(args.source)
    print(f"  plate colour: {plate_colour}")

    mark = split_mark(artwork)

    print("\nLauncher foreground (adaptive, safe-zone scaled):")
    write_density_set(mark, "ic_launcher_foreground", LAUNCHER_DP, SAFE_FRACTION)

    print("\nLauncher monochrome (themed icons):")
    write_density_set(mark, "ic_launcher_monochrome", LAUNCHER_DP, SAFE_FRACTION)

    print("\nSplash mark:")
    write_density_set(mark, "ic_yaku_splash_mark", SPLASH_DP, SPLASH_FRACTION)

    # Notification small icons are drawn as a silhouette from the alpha channel, so the same
    # white-on-transparent mark serves both them and the in-app header. Rendered at 96dp rather
    # than the 24dp notifications use, because the More screen shows it several times larger.
    print("\nApp mark (notifications + More header):")
    write_density_set(mark, "ic_yaku", 96, 0.92)

    print(f"\nPlate colour for colors.xml: {plate_colour}")


if __name__ == "__main__":
    main()
