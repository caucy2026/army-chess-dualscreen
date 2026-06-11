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


def generate_ink_landscape(top_color, mountain_colors, sun_color, seed, draw_petals=False):
    # top_color: RGBA 宣纸背景底色
    im = Image.new("RGBA", (W, H), top_color)
    d = ImageDraw.Draw(im, "RGBA")
    
    # 1. 绘制落日/朝阳 (移到左侧，避免棋盘中心重合影响下棋)
    if sun_color:
        random.seed(seed)
        sun_x = random.randint(int(W * 0.1), int(W * 0.25))
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
        
    # 3. 绘制飞鸟 (移到中右侧)
    d = ImageDraw.Draw(im, "RGBA")
    random.seed(seed + 88)
    bird_color = (mountain_colors[-1][0], mountain_colors[-1][1], mountain_colors[-1][2], 160)
    for _ in range(random.randint(4, 7)):
        bx = random.randint(int(W * 0.35), int(W * 0.65))
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
    
    # 5. 绘制飘落的桃花瓣（为春意增色）
    if draw_petals:
        random.seed(seed + 77)
        pd = ImageDraw.Draw(im, "RGBA")
        for _ in range(40):
            px = random.randint(0, W)
            py = random.randint(0, int(H * 0.75))
            pw = random.randint(4, 7)
            ph = random.randint(3, 5)
            # 桃花瓣颜色：粉嫩色系
            p_color = (
                random.randint(240, 255),
                random.randint(145, 185),
                random.randint(165, 195),
                random.randint(140, 200)
            )
            # 绘制小花瓣多边形（呈斜水滴形）
            pd.polygon([
                (px, py),
                (px + pw, py + ph // 2),
                (px + pw // 2, py + ph),
                (px - pw // 3, py + ph // 2)
            ], fill=p_color)

    return im.convert("RGB")


def generate_flower_scene(base_color, flower_colors, seed, is_cherry=True):
    # base_color: RGBA 纸张底色
    im = Image.new("RGBA", (W, H), base_color)
    random.seed(seed)
    
    # 1. 绘制一些远景大片模糊的花丛 (Bokeh 散景效果)
    bokeh_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bokeh_layer, "RGBA")
    
    for _ in range(40):
        # 倾向于在边缘和四个角放置，避免中间过浓
        if random.random() < 0.75:
            x = random.choice([random.randint(-60, 180), random.randint(W - 280, W + 60)])
            y = random.choice([random.randint(-60, 150), random.randint(H - 220, H + 60)])
        else:
            x = random.randint(0, W)
            y = random.randint(0, H)
        r = random.randint(45, 100)
        color = random.choice(flower_colors)
        bd.ellipse([x - r, y - r, x + r, y + r], fill=(color[0], color[1], color[2], random.randint(15, 38)))
        
    bokeh_layer = bokeh_layer.filter(ImageFilter.GaussianBlur(28))
    im = Image.alpha_composite(im, bokeh_layer)
    
    # 2. 绘制中景中等模糊的花簇，在边缘形成花枝的轮廓
    mid_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mid_layer, "RGBA")
    for _ in range(35):
        x = random.choice([random.randint(-30, 220), random.randint(W - 300, W + 30)])
        y = random.choice([random.randint(-30, 120), random.randint(H - 200, H + 30)])
        r = random.randint(20, 50)
        color = random.choice(flower_colors)
        md.ellipse([x - r, y - r, x + r, y + r], fill=(color[0], color[1], color[2], random.randint(35, 75)))
        
    mid_layer = mid_layer.filter(ImageFilter.GaussianBlur(10))
    im = Image.alpha_composite(im, mid_layer)
    
    # 3. 绘制较为清晰的花瓣和花朵点缀在中央和半空中（特效感）
    detail_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dd = ImageDraw.Draw(detail_layer, "RGBA")
    
    # 飘落的花瓣 / 花朵小图标
    for _ in range(50):
        px = random.randint(0, W)
        py = random.randint(0, H)
        pw = random.randint(6, 12)
        ph = random.randint(4, 8)
        color = random.choice(flower_colors)
        p_color = (color[0], color[1], color[2], random.randint(110, 175))
        
        # 绘制花瓣状的斜向多边形
        dd.polygon([
            (px, py),
            (px + pw, py + ph // 2),
            (px + pw // 2, py + ph),
            (px - pw // 3, py + ph // 2)
        ], fill=p_color)
        
    # 如果是兰花，我们可以在角落画几条优美纤细的绿萝藤蔓
    if not is_cherry:
        for _ in range(5):
            start_x = random.randint(-15, 60)
            start_y = random.randint(H - 60, H + 15)
            ctrl_x = random.randint(90, 220)
            ctrl_y = random.randint(H - 180, H - 90)
            end_x = random.randint(160, 320)
            end_y = random.randint(H - 90, H - 15)
            points = []
            for t in range(20):
                t_val = t / 19.0
                x = (1-t_val)**2 * start_x + 2*(1-t_val)*t_val * ctrl_x + t_val**2 * end_x
                y = (1-t_val)**2 * start_y + 2*(1-t_val)*t_val * ctrl_y + t_val**2 * end_y
                points.append((x, y))
            dd.line(points, fill=(100, 155, 100, 110), width=2)
            
    detail_layer = detail_layer.filter(ImageFilter.GaussianBlur(1.2))
    im = Image.alpha_composite(im, detail_layer)
    
    return im.convert("RGB")





def generate_pastoral_scene(base_color, mountain_colors, field_colors, seed):
    # base_color: RGBA 底色
    im = Image.new("RGBA", (W, H), base_color)
    d = ImageDraw.Draw(im, "RGBA")
    random.seed(seed)
    
    # 1. 绘制两层模糊远山
    y_bases = [260, 350]
    amplitudes = [75, 55]
    blur_radii = [5.5, 2.5]
    
    for i in range(2):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ldraw = ImageDraw.Draw(layer, "RGBA")
        draw_mountain(ldraw, W, H, y_bases[i], amplitudes[i], mountain_colors[i], seed + i * 31, num_points=25)
        if blur_radii[i] > 0:
            layer = layer.filter(ImageFilter.GaussianBlur(blur_radii[i]))
        im = Image.alpha_composite(im, layer)
        
    # 2. 绘制波浪起伏的层层梯田
    field_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    fd = ImageDraw.Draw(field_layer, "RGBA")
    
    # 三层梯田
    fy_bases = [440, 490, 550]
    famplitudes = [25, 20, 15]
    for i in range(3):
        pts = []
        pts.append((0, H))
        num_pts = 15
        random.seed(seed + i * 41)
        # 田埂波线
        for j in range(num_pts + 1):
            x = (W * j) / num_pts
            y = fy_bases[i] + math.sin(j * 0.4 + i * 1.2) * famplitudes[i] + random.uniform(-4, 4)
            pts.append((x, y))
        pts.append((W, H))
        fd.polygon(pts, fill=field_colors[i])
        
        # 勾勒田埂亮线
        wpts = pts[1:-1]
        fd.line(wpts, fill=(255, 255, 255, 110), width=1)
        
    field_layer = field_layer.filter(ImageFilter.GaussianBlur(2.0))
    im = Image.alpha_composite(im, field_layer)
    
    # 3. 绘制角落写意茅舍 (画在左下角，避开中间)
    cottage_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cd = ImageDraw.Draw(cottage_layer, "RGBA")
    
    cx, cy = int(W * 0.22), 530
    cw, ch = 35, 22
    # 茅屋墙面 (深灰褐)
    cd.rectangle([cx - cw//2, cy - ch//2, cx + cw//2, cy + ch//2], fill=(100, 90, 80, 240))
    # 茅草屋顶 (金草黄)
    cd.polygon([(cx - cw//2 - 4, cy - ch//2), (cx + cw//2 + 4, cy - ch//2), (cx, cy - ch - 2)], fill=(210, 180, 120, 255))
    
    # 旁边再画个小树丛
    cd.ellipse([cx + 30 - 15, cy - 20, cx + 30 + 15, cy + 10], fill=(70, 120, 85, 220))
    cd.ellipse([cx + 42 - 12, cy - 30, cx + 42 + 12, cy - 6], fill=(90, 145, 105, 220))
    
    cottage_layer = cottage_layer.filter(ImageFilter.GaussianBlur(0.8))
    im = Image.alpha_composite(im, cottage_layer)
    
    # 4. 绘制天空中飞燕
    detail_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dd = ImageDraw.Draw(detail_layer, "RGBA")
    random.seed(seed + 99)
    bird_color = (60, 55, 50, 180)
    for _ in range(4):
        bx = random.randint(int(W * 0.45), int(W * 0.8))
        by = random.randint(int(H * 0.18), int(H * 0.35))
        size = random.randint(5, 8)
        dd.line([(bx - size, by - size // 2), (bx, by), (bx + size, by - size // 2)], fill=bird_color, width=1)
        
    detail_layer = detail_layer.filter(ImageFilter.GaussianBlur(0.6))
    im = Image.alpha_composite(im, detail_layer)
    
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

# 5. 春意江南 (新绿山水 - 去除太阳以防阻挡下棋)
bg5 = generate_ink_landscape(
    (240, 246, 242, 255), 
    [
        (200, 222, 200, 180),
        (160, 195, 160, 210),
        (110, 162, 110, 235),
        (65, 122, 65, 255)
    ], 
    None, 
    28,
    draw_petals=False
)
save(bg5, "bg_scene_5")

# 6. 桃林春晓 (朝晖春色 - 去除太阳以防阻挡下棋)
bg6 = generate_ink_landscape(
    (254, 250, 242, 255), 
    [
        (232, 210, 215, 170),
        (195, 170, 185, 200),
        (142, 168, 130, 230),
        (90, 128, 80, 255)
    ], 
    None, 
    45,
    draw_petals=True
)
save(bg6, "bg_scene_6")

# 7. 樱花漫舞 (浪漫樱粉鲜花特效)
bg7 = generate_flower_scene(
    (255, 244, 246, 255),
    [
        (255, 192, 203),
        (255, 182, 193),
        (244, 143, 177),
        (255, 228, 225)
    ],
    77,
    is_cherry=True
)
save(bg7, "bg_scene_7")

# 8. 幽谷兰香 (清新紫萝鲜花特效)
bg8 = generate_flower_scene(
    (246, 245, 250, 255),
    [
        (216, 191, 216),
        (221, 160, 221),
        (186, 85, 211),
        (147, 112, 219)
    ],
    88,
    is_cherry=False
)
save(bg8, "bg_scene_8")

# 9. 山水田园 (金秋/春耕梯田田园)
bg9 = generate_pastoral_scene(
    (248, 245, 236, 255),
    [
        (210, 225, 200, 160),
        (175, 198, 162, 210)
    ],
    [
        (195, 215, 168, 190),
        (232, 224, 176, 210),
        (138, 175, 120, 240)
    ],
    72
)
save(bg9, "bg_scene_9")



