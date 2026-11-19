#!/usr/bin/env python3
"""Regenerate every app icon from art/icon-source.jpg. Run: python3 -I scripts/make-icons.py"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "icon-source.jpg"
DESKTOP_RES = ROOT / "desktop/src/main/resources"
ANDROID_RES = ROOT / "android/src/main/res"


def load_hammer() -> Image.Image:
    """Black RGBA hammer, cropped to its bounding box and padded to a centred square."""
    lum = Image.open(SRC).convert("L")
    alpha = lum.point(lambda v: 255 if v <= 96 else 0 if v >= 200 else round((200 - v) * 255 / 104))
    box = alpha.point(lambda v: 255 if v > 0 else 0).getbbox()
    alpha = alpha.crop(box)
    side = max(alpha.size)
    sq = Image.new("L", (side, side), 0)
    sq.paste(alpha, ((side - alpha.width) // 2, (side - alpha.height) // 2))
    img = Image.new("RGBA", sq.size, (0, 0, 0, 0))
    img.putalpha(sq)
    return img


def fit(hammer: Image.Image, size: int) -> Image.Image:
    return hammer.resize((size, size), Image.LANCZOS)


def tile(hammer: Image.Image, size: int, radius: float, inner: int, scale: int = 4) -> Image.Image:
    """White rounded-square tile with the hammer centred; supersampled mask for smooth corners."""
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1), radius=radius * scale, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.paste((255, 255, 255, 255), (0, 0), mask)
    h = fit(hammer, inner)
    off = (size - inner) // 2
    out.alpha_composite(h, (off, off))
    # keep tile corners transparent (hammer never reaches them)
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", out.size, 0), mask))
    return out


def main() -> None:
    hammer = load_hammer()

    tile(hammer, 64, 12, 52).save(DESKTOP_RES / "tray.png", optimize=True)

    sizes = [16, 24, 32, 48, 64, 128, 256]
    frames = [tile(hammer, s, s * 0.19, round(s * 0.8)) for s in sizes]
    frames[-1].save(DESKTOP_RES / "mjolnir.ico", format="ICO",
                    sizes=[(s, s) for s in sizes], append_images=frames[:-1])

    for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        inner = round(px * 66 / 108)
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        off = (px - inner) // 2
        canvas.alpha_composite(fit(hammer, inner), (off, off))
        d = ANDROID_RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        canvas.save(d / "ic_launcher_foreground.png", optimize=True)
        canvas.save(d / "ic_launcher_monochrome.png", optimize=True)


if __name__ == "__main__":
    main()
