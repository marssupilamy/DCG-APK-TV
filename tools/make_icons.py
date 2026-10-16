# Gera o banner da Fire TV e os ícones do app.
# Uso: python3 tools/make_icons.py app/src/main/res <pasta para prévias>
import os, sys
from PIL import Image, ImageDraw, ImageFont

RES = sys.argv[1]
OUT = sys.argv[2]
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
NAVY_TOP = (27, 42, 74)
NAVY_BOTTOM = (14, 19, 32)
GOLD = (245, 183, 0)
WHITE = (255, 255, 255)
S = 4  # desenha 4x maior e reduz, para bordas suaves

def gradient(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(h - 1, 1)
        c = tuple(int(NAVY_TOP[i] * (1 - t) + NAVY_BOTTOM[i] * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c)
    return img

def tv(d, x, y, w):
    """TV dourada com o símbolo de play. (x, y) é o canto superior esquerdo da tela."""
    h = w * 0.66
    r = w * 0.09
    d.rounded_rectangle([x, y, x + w, y + h], radius=r, fill=GOLD)
    # pé da TV
    fw, fh = w * 0.34, w * 0.07
    d.rounded_rectangle([x + (w - fw) / 2, y + h + w * 0.06, x + (w + fw) / 2, y + h + w * 0.06 + fh], radius=fh / 2, fill=GOLD)
    # play
    cx, cy, t = x + w / 2, y + h / 2, w * 0.2
    d.polygon([(cx - t * 0.75, cy - t), (cx - t * 0.75, cy + t), (cx + t * 1.05, cy)], fill=NAVY_BOTTOM)
    return y + h + w * 0.06 + fh

def banner(path, w, h):
    W, H = w * S, h * S
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(BOLD, int(H * 0.21))
    tvw = H * 0.40
    gap = W * 0.06
    dcg = d.textlength("DCG ", font=font)
    total = tvw + gap + dcg + d.textlength("TV", font=font)
    x = (W - total) / 2
    tv_h = tvw * 0.66 + tvw * 0.13
    tv(d, x, (H - tv_h) / 2, tvw)
    tx = x + tvw + gap
    d.text((tx, H * 0.5), "DCG", font=font, fill=WHITE, anchor="lm")
    d.text((tx + dcg, H * 0.5), "TV", font=font, fill=GOLD, anchor="lm")
    img.resize((w, h), Image.LANCZOS).save(path)

def icon(path, size):
    W = size * S
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    bg = gradient(W, W).convert("RGBA")
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=W * 0.22, fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)
    tvw = W * 0.58
    bottom = tv(d, (W - tvw) / 2, W * 0.17, tvw)
    font = ImageFont.truetype(BOLD, int(W * 0.17))
    d.text((W / 2, bottom + W * 0.11), "DCG", font=font, fill=WHITE, anchor="mm")
    img.resize((size, size), Image.LANCZOS).save(path)

os.makedirs(f"{RES}/drawable-xhdpi", exist_ok=True)
banner(f"{RES}/drawable-xhdpi/banner.png", 640, 360)
for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    os.makedirs(f"{RES}/mipmap-{name}", exist_ok=True)
    icon(f"{RES}/mipmap-{name}/ic_launcher.png", size)
# prévias para mostrar
banner(f"{OUT}/banner_preview.png", 640, 360)
icon(f"{OUT}/icon_preview.png", 512)
