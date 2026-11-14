#!/usr/bin/env python3
"""Draws the Google Play listing graphics from the app mark (drawable/ic_launcher_foreground.xml).

    python3 scripts/store-graphics.py [out dir]   (default: fastlane/metadata/android/en-GB/images)

Writes icon.png (512x512), featureGraphic.png (1024x500) and tvBanner.png (1280x720).
Needs Pillow. The mark is drawn with the same geometry and colours as the vector drawables.
"""
import pathlib
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

BACKGROUND = (0x0E, 0x0F, 0x13)
ACCENT = (0x8A, 0xB4, 0xFF)
WHITE = (0xEC, 0xED, 0xEF)
MUTED = (0x9A, 0xA0, 0xAB)
SS = 4  # supersampling factor for smooth edges


def mark(draw: ImageDraw.ImageDraw, cx: float, cy: float, unit: float) -> None:
    """The mark in the launcher's 108-unit viewport, centred on (cx, cy), `unit` pixels per viewport unit."""
    def p(x: float, y: float) -> tuple[float, float]:
        return cx + (x - 54) * unit, cy + (y - 54) * unit

    # Screen outline: the stroke centre runs on the 30..78 x 38..70 rectangle with radius 6, width 4.
    half = 2 * unit
    x0, y0 = p(30, 38)
    x1, y1 = p(78, 70)
    draw.rounded_rectangle((x0 - half, y0 - half, x1 + half, y1 + half), radius=8 * unit, fill=ACCENT)
    draw.rounded_rectangle((x0 + half, y0 + half, x1 - half, y1 - half), radius=4 * unit, fill=BACKGROUND)
    draw.polygon([p(49, 46), p(61, 54), p(49, 62)], fill=WHITE)


def canvas(w: int, h: int) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGB", (w * SS, h * SS), BACKGROUND)
    return img, ImageDraw.Draw(img)


def save(img: Image.Image, w: int, h: int, path: pathlib.Path) -> None:
    img.resize((w, h), Image.LANCZOS).save(path, optimize=True)
    print(path)


def font(size: int, bold: bool) -> ImageFont.FreeTypeFont:
    name = subprocess.run(["fc-match", "-f", "%{file}", "sans:bold" if bold else "sans"],
                          capture_output=True, text=True).stdout
    f = ImageFont.truetype(name, size)
    if bold:
        try:  # variable fonts: pick the bold instance
            f.set_variation_by_name("Bold")
        except Exception:
            pass
    return f


def main() -> None:
    root = pathlib.Path(__file__).resolve().parent.parent
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "fastlane/metadata/android/en-GB/images"
    out.mkdir(parents=True, exist_ok=True)

    # Store icon: the adaptive icon's visible 72-unit square, full bleed (Play rounds the corners itself).
    img, d = canvas(512, 512)
    mark(d, 256 * SS, 256 * SS, 512 * SS / 72)
    save(img, 512, 512, out / "icon.png")

    # TV banner: drawable/tv_banner.xml (320x180 viewport) at 1280x720.
    img, d = canvas(1280, 720)
    u = 1280 * SS / 320
    half = 3 * u  # stroke width 6
    d.rounded_rectangle((108 * u - half, 52 * u - half, 212 * u + half, 128 * u + half), radius=13 * u, fill=ACCENT)
    d.rounded_rectangle((108 * u + half, 52 * u + half, 212 * u - half, 128 * u - half), radius=7 * u, fill=BACKGROUND)
    d.polygon([(148 * u, 70 * u), (178 * u, 90 * u), (148 * u, 110 * u)], fill=WHITE)
    save(img, 1280, 720, out / "tvBanner.png")

    # Feature graphic: mark on the left, name and one plain line on the right.
    img, d = canvas(1024, 500)
    mark(d, 240 * SS, 250 * SS, 6.4 * SS)
    d.text((470 * SS, 250 * SS), "PureIPTV", font=font(96 * SS, True), fill=WHITE, anchor="ls")
    d.text((474 * SS, 312 * SS), "A player for your own playlists", font=font(34 * SS, False), fill=MUTED, anchor="ls")
    save(img, 1024, 500, out / "featureGraphic.png")


if __name__ == "__main__":
    main()
