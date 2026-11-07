"""Draws the FunnyLudo launcher icon (Pillow). Usage: gen_icon.py <res_dir>"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter

SS = 4  # supersampling


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


RED, GREEN, YELLOW, BLUE = (229, 57, 53), (67, 160, 71), (253, 216, 53), (30, 136, 229)


def background(size):
    s = size * SS
    img = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(img)
    h = s // 2
    quads = [(0, 0, RED), (h, 0, GREEN), (h, h, YELLOW), (0, h, BLUE)]
    for x, y, col in quads:
        for i in range(h):  # vertical gloss gradient
            t = i / h
            d.line([(x, y + i), (x + h, y + i)], fill=lerp(shade(col, 1.18), shade(col, 0.82), t))
    # white "yard" circles with a coloured pawn dot in each corner
    for (x, y, col), (cx, cy) in zip(quads, [(0.25, 0.25), (0.75, 0.25), (0.75, 0.75), (0.25, 0.75)]):
        cx, cy = cx * s, cy * s
        r = s * 0.13
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255))
        r2 = r * 0.55
        d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=shade(col, 0.9))
        d.ellipse([cx - r2 * 0.5, cy - r2 * 0.7, cx - r2 * 0.05, cy - r2 * 0.25], fill=shade(col, 1.5))
    # soft dark vignette towards the middle so the die pops
    glow = Image.new("L", (s, s), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([s * 0.18, s * 0.18, s * 0.82, s * 0.82], fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.07))
    img = Image.composite(Image.new("RGB", (s, s), (40, 18, 80)), img, glow)
    return img


def map_pt(o, u, v, a, b):
    return (o[0] + u[0] * a + v[0] * b, o[1] + u[1] * a + v[1] * b)


def draw_face(d, o, u, v, base, pips, pip_col, big_red=False):
    poly = [map_pt(o, u, v, 0, 0), map_pt(o, u, v, 1, 0), map_pt(o, u, v, 1, 1), map_pt(o, u, v, 0, 1)]
    d.polygon(poly, fill=base)
    for (a, b) in pips:
        r = 0.2 if big_red else 0.11
        pts = [map_pt(o, u, v, a + r * math.cos(t), b + r * math.sin(t))
               for t in [i * math.pi / 10 for i in range(20)]]
        d.polygon(pts, fill=(220, 30, 40) if big_red else pip_col)


def foreground(size, scale=1.0):
    """Isometric die on a transparent canvas; scale<1 keeps it inside the adaptive-icon safe zone."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    cx, cy = s / 2, s / 2 + s * 0.015
    k = s * 0.215 * scale          # edge length in px
    ux, uy = k * math.cos(math.radians(30)), k * math.sin(math.radians(30))
    top_o = (cx, cy - 2 * k * 0.5 - k * 0.0)
    # cube vertices (isometric): top vertex T, then left/right/bottom
    T = (cx, cy - k * 1.0 - 0.0)
    C = (cx, cy)                       # centre vertex where three faces meet
    L = (cx - ux, cy - uy)             # left-top
    R = (cx + ux, cy - uy)
    D = (cx, cy - 2 * uy)              # back vertex (top face)
    # shadow
    sh = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.ellipse([cx - k * 1.25, cy + k * 1.25, cx + k * 1.25, cy + k * 1.75], fill=(0, 0, 0, 120))
    sh = sh.filter(ImageFilter.GaussianBlur(s * 0.02))
    img = Image.alpha_composite(img, sh)
    d = ImageDraw.Draw(img)

    white = (255, 252, 244)
    pip = (35, 30, 50)
    # top face: origin at left corner, u toward back-right? use parallelogram L -> D -> R -> C
    top_o = L
    top_u = (D[0] - L[0], D[1] - L[1])
    top_v = (C[0] - L[0], C[1] - L[1])
    draw_face(d, top_o, top_u, top_v, white, [(0.25, 0.25), (0.75, 0.75)] + [(0.25, 0.75), (0.75, 0.25)] + [(0.5, 0.5)],
              pip)
    # left face: origin L, u -> C, v -> down
    down = (0, 2 * uy)
    left_o = L
    left_u = (C[0] - L[0], C[1] - L[1])
    draw_face(d, left_o, left_u, down, shade(white, 0.80), [(0.5, 0.5)], pip, big_red=True)
    # right face: origin C, u -> R, v -> down
    right_o = C
    right_u = (R[0] - C[0], R[1] - C[1])
    draw_face(d, right_o, right_u, down, shade(white, 0.62), [(0.25, 0.25), (0.75, 0.75), (0.5, 0.5),
                                                              (0.25, 0.75), (0.75, 0.25)], pip)
    # outline
    outline = (60, 40, 90)
    for a, b in [(L, D), (D, R), (R, (R[0], R[1] + 2 * uy)), ((R[0], R[1] + 2 * uy), (C[0], C[1] + 2 * uy)),
                 ((C[0], C[1] + 2 * uy), (L[0], L[1] + 2 * uy)), ((L[0], L[1] + 2 * uy), L), (L, C), (C, R),
                 (C, (C[0], C[1] + 2 * uy))]:
        d.line([a, b], fill=outline, width=max(2, int(s * 0.008)))
    # gold sparkle
    sp = ImageDraw.Draw(img)
    sx, sy, sr = cx + k * 1.55, cy - k * 1.55, k * 0.55
    star = []
    for i in range(8):
        r = sr if i % 2 == 0 else sr * 0.22
        a = i * math.pi / 4 - math.pi / 2
        star.append((sx + r * math.cos(a), sy + r * math.sin(a)))
    sp.polygon(star, fill=(255, 235, 120), outline=(255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


def rounded_mask(size, radius_frac, circle=False):
    m = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(m)
    if circle:
        d.ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * radius_frac), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def legacy(size, circle):
    bg = background(size).resize((size, size), Image.LANCZOS).convert("RGBA")
    fg = foreground(size, 1.12)
    out = Image.alpha_composite(bg, fg)
    out.putalpha(rounded_mask(size, 0.22, circle))
    return out


if __name__ == "__main__":
    res = sys.argv[1]
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in dens.items():
        d = os.path.join(res, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        legacy(px, False).save(os.path.join(d, "ic_launcher.png"))
        legacy(px, True).save(os.path.join(d, "ic_launcher_round.png"))
    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    background(432).resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, "ic_launcher_bg.png"))
    foreground(432, 0.85).save(os.path.join(nodpi, "ic_launcher_fg.png"))
    anyv = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anyv, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
           '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(anyv, n), "w").write(xml)
    # preview
    legacy(512, False).save("/home/claude/icon_preview.png")
    print("icon done")
