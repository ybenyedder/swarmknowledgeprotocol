#!/usr/bin/env python3
"""Play Store assets for Tree4Five OSP Bridge — reproducible via PIL only.

Regenerates the store graphics from the same brand tokens as the app
(colors.xml): dark gradient background (#0f0c29 → #24243e) and the mesh
glyph (three linked nodes) in the cyan/blue accents (#00f2fe / #4facfe).

  python3 make_assets.py
  → play_icon_512.png, feature_graphic_1024x500.png
"""

from PIL import Image, ImageDraw, ImageFont
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# brand tokens (ospbridge/src/main/res/values/colors.xml)
DARK = (15, 12, 41)        # #0f0c29
MID = (48, 43, 99)         # #302b63
SURFACE = (36, 36, 62)     # #24243e
CYAN = (0, 242, 254)       # #00f2fe
BLUE = (79, 172, 254)      # #4facfe

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def gradient(size, top, mid, bottom):
    """Vertical three-stop brand gradient, drawn per row."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        if t < 0.55:                       # dark → mid on the upper half
            k = t / 0.55
            c = tuple(int(a + (b - a) * k) for a, b in zip(top, mid))
        else:                              # mid → surface below
            k = (t - 0.55) / 0.45
            c = tuple(int(a + (b - a) * k) for a, b in zip(mid, bottom))
        for x in range(w):
            px[x, y] = c
    return img


def mesh_glyph(size):
    """The swarm: three linked nodes (viewport 108 from ic_launcher_foreground).
    Drawn 4x then downsampled for clean anti-aliasing."""
    S = 4
    img = Image.new("RGBA", (size * S, size * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size * S / 108.0                   # viewport → pixels

    top = (54, 32)
    left, right = (32, 72), (76, 72)
    for a, b in [(top, left), (top, right), (left, right)]:
        d.line([tuple(v * k for v in a), tuple(v * k for v in b)],
               fill=CYAN + (255,), width=int(4 * k))
    r = 10 * k
    for c, col in [(top, CYAN), (left, BLUE), (right, BLUE)]:
        x, y = c[0] * k, c[1] * k
        d.ellipse([x - r, y - r, x + r, y + r], fill=col + (255,))
    return img.resize((size, size), Image.LANCZOS)


def play_icon(path, size=512):
    img = gradient((size, size), DARK, MID, SURFACE).convert("RGBA")
    glyph = mesh_glyph(int(size * 0.78))
    img.alpha_composite(glyph, ((size - glyph.width) // 2,
                                (size - glyph.height) // 2))
    img.convert("RGB").save(path, "PNG")
    print("wrote", path)


def feature_graphic(path, size=(1024, 500)):
    img = gradient(size, DARK, MID, SURFACE).convert("RGBA")
    glyph = mesh_glyph(300)
    img.alpha_composite(glyph, (70, (size[1] - 300) // 2))

    d = ImageDraw.Draw(img)
    name = ImageFont.truetype(FONT, 64)
    tag = ImageFont.truetype(FONT, 26)
    d.text((430, 168), "Tree4Five", font=name, fill=CYAN + (255,))
    d.text((430, 244), "OSP Bridge", font=name, fill=(255, 255, 255, 255))
    d.text((432, 336), "Verified knowledge, device to device",
           font=tag, fill=(190, 195, 220, 255))
    img.convert("RGB").save(path, "PNG")
    print("wrote", path)


if __name__ == "__main__":
    play_icon(os.path.join(HERE, "play_icon_512.png"))
    feature_graphic(os.path.join(HERE, "feature_graphic_1024x500.png"))
