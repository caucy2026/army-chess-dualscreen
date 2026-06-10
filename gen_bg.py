# -*- coding: utf-8 -*-
"""生成 4 张棋盘背景场景图 (1920x1280) 到 res/drawable-nodpi/。
风格柔和低对比，保证覆盖其上的半透明玻璃面板与棋盘内容可读。
"""
import os
import random
from PIL import Image, ImageDraw, ImageFilter

W, H = 960, 640
OUT = r"E:\pad\app\src\main\res\drawable-nodpi"
os.makedirs(OUT, exist_ok=True)


def save(im, name):
    im.resize((1920, 1280), Image.LANCZOS).save(os.path.join(OUT, f"{name}.png"))
    print("OK", name)


def blob_camo(base, colors, seed):
    random.seed(seed)
    im = Image.new("RGB", (W, H), base)
    d = ImageDraw.Draw(im)
    for _ in range(70):
        x, y = random.randint(-120, W), random.randint(-120, H)
        w, h = random.randint(90, 280), random.randint(70, 200)
        d.ellipse([x, y, x + w, y + h], fill=random.choice(colors))
    return im.filter(ImageFilter.GaussianBlur(16))


def vgradient(top, bottom):
    im = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (W, y)], fill=c)
    return im


# 1. 沙漠迷彩
save(blob_camo("#E6D9C0", ["#D9C7A3", "#CDB88E", "#C0A878", "#E0D2B4"], 42), "bg_scene_1")

# 2. 丛林迷彩
save(blob_camo("#D8E2CC", ["#BCCCA6", "#A3BA8C", "#8CA876", "#CBD8B8"], 7), "bg_scene_2")

# 3. 蔚蓝海洋（渐变 + 柔光波纹）
im = vgradient((216, 233, 245), (148, 190, 224))
d = ImageDraw.Draw(im, "RGBA")
random.seed(3)
for _ in range(36):
    x, y = random.randint(-150, W), random.randint(0, H)
    w, h = random.randint(160, 420), random.randint(20, 50)
    d.ellipse([x, y, x + w, y + h], fill=(255, 255, 255, 46))
im = im.filter(ImageFilter.GaussianBlur(12))
save(im, "bg_scene_3")

# 4. 静谧星空（深蓝渐变 + 清晰星点后加）
im = vgradient((43, 58, 92), (24, 33, 60))
im = im.filter(ImageFilter.GaussianBlur(4))
d = ImageDraw.Draw(im, "RGBA")
random.seed(11)
for _ in range(160):
    x, y = random.randint(0, W), random.randint(0, H)
    r = random.choice([1, 1, 1, 2])
    a = random.randint(110, 230)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))
for _ in range(6):  # 几颗带光晕的亮星
    x, y = random.randint(40, W - 40), random.randint(40, H - 40)
    d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=(255, 255, 255, 40))
    d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(255, 255, 255, 240))
save(im, "bg_scene_4")
