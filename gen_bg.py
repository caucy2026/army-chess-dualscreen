# -*- coding: utf-8 -*-
"""生成 6 张棋盘背景场景图 (1920x1280) 到 res/drawable-nodpi/。
风格柔和低对比，保证覆盖其上的半透明玻璃面板与棋盘内容可读。
"""
import os
import random
import math
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


def draw_mountain(draw, width, height, y_base, amplitude, color, seed, num_points=35):
    random.seed(seed)
    points = []
    points.append((0, height))
    for i in range(num_points + 1):
        x = (width * i) / num_points
        # 结合正弦波与微小随机起伏生成山脊线
        sine = math.sin(i * 0.4) * 0.4 + math.sin(i * 0.15) * 0.3
        y = y_base + (sine + random.uniform(-0.3, 0.3)) * amplitude
        points.append((x, y))
    points.append((width, height))
    draw.polygon(points, fill=color)


def generate_ink_landscape(top_color, mountain_colors, sun_color, seed):
    # top_color: RGBA 宣纸背景底色
    im = Image.new("RGBA", (W, H), top_color)
    d = ImageDraw.Draw(im, "RGBA")
    
    # 1. 绘制落日/朝阳
    if sun_color:
        random.seed(seed)
        sun_x = random.randint(int(W * 0.55), int(W * 0.75))
        sun_y = random.randint(int(H * 0.18), int(H * 0.32))
        r = random.randint(35, 50)
        for i in range(r, 0, -1):
            alpha = int(sun_color[3] * (1.0 - (i / r) * 0.8))
            d.ellipse([sun_x - i, sun_y - i, sun_x + i, sun_y + i], fill=(sun_color[0], sun_color[1], sun_color[2], alpha))
            
    # 2. 远山绘制与分层模糊（叠合雾气）
    y_bases = [240, 330, 420, 510]
    amplitudes = [90, 75, 55, 40]
    blur_radii = [7.0, 4.0, 2.0, 0.8]
    
    for i in range(4):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ldraw = ImageDraw.Draw(layer, "RGBA")
        draw_mountain(ldraw, W, H, y_bases[i], amplitudes[i], mountain_colors[i], seed + i * 17, num_points=35)
        
        if blur_radii[i] > 0:
            layer = layer.filter(ImageFilter.GaussianBlur(blur_radii[i]))
        im = Image.alpha_composite(im, layer)
        
    # 3. 绘制飞鸟
    d = ImageDraw.Draw(im, "RGBA")
    random.seed(seed + 88)
    bird_color = (mountain_colors[-1][0], mountain_colors[-1][1], mountain_colors[-1][2], 160)
    for _ in range(random.randint(4, 7)):
        bx = random.randint(int(W * 0.15), int(W * 0.45))
        by = random.randint(int(H * 0.12), int(H * 0.38))
        size = random.randint(4, 7)
        d.line([(bx - size, by - size // 2), (bx, by), (bx + size, by - size // 2)], fill=bird_color, width=1)
        
    # 4. 绘制小舟渔翁
    boat_x = random.randint(int(W * 0.6), int(W * 0.78))
    boat_y = random.randint(int(H * 0.72), int(H * 0.82))
    boat_color = (mountain_colors[-1][0], mountain_colors[-1][1], mountain_colors[-1][2], 180)
    d.polygon([(boat_x - 12, boat_y), (boat_x + 12, boat_y), (boat_x + 8, boat_y + 3), (boat_x - 8, boat_y + 3)], fill=boat_color)
    d.ellipse([boat_x - 1, boat_y - 4, boat_x + 2, boat_y - 1], fill=boat_color)
    d.line([(boat_x + 1, boat_y - 2), (boat_x + 9, boat_y - 6)], fill=boat_color, width=1)
    
    return im.convert("RGB")


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

# 4. 静谧星空（深蓝渐变 + 清晰星点）
im = vgradient((43, 58, 92), (24, 33, 60))
im = im.filter(ImageFilter.GaussianBlur(4))
d = ImageDraw.Draw(im, "RGBA")
random.seed(11)
for _ in range(160):
    x, y = random.randint(0, W), random.randint(0, H)
    r = random.choice([1, 1, 1, 2])
    a = random.randint(110, 230)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))
for _ in range(6):
    x, y = random.randint(40, W - 40), random.randint(40, H - 40)
    d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=(255, 255, 255, 40))
    d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(255, 255, 255, 240))
save(im, "bg_scene_4")

# 5. 烟雨江南（水墨黛青）
bg5 = generate_ink_landscape(
    (230, 234, 230, 255), 
    [
        (175, 186, 175, 180),
        (135, 148, 134, 210),
        (92, 105, 91, 235),
        (54, 66, 53, 255)
    ], 
    (208, 100, 80, 100), 
    28
)
save(bg5, "bg_scene_5")

# 6. 泼墨金辉（金秋水墨）
bg6 = generate_ink_landscape(
    (245, 239, 224, 255), 
    [
        (196, 185, 168, 180),
        (158, 146, 126, 210),
        (115, 103, 85, 235),
        (72, 62, 47, 255)
    ], 
    (229, 162, 51, 100), 
    45
)
save(bg6, "bg_scene_6")

