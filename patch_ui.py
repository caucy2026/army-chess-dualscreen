import os

def update_colors_xml():
    colors_xml = r"E:\pad\app\src\main\res\values\colors.xml"
    with open(colors_xml, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 基础明亮现代主题背景 -->
    <color name="bg_dark">#F8FAFC</color>
    <color name="bg_panel">#FFFFFF</color>
    <color name="border_color">#E2E8F0</color>

    <!-- 玩家 A (主屏) 现代天蓝 -->
    <color name="color_a">#0EA5E9</color>
    <color name="color_a_glow">#BAE6FD</color>
    <color name="color_a_bg">#E0F2FE</color>

    <!-- 玩家 B (副屏) 现代翠绿 -->
    <color name="color_b">#10B981</color>
    <color name="color_b_glow">#D1FAE5</color>
    <color name="color_b_bg">#ECFDF5</color>

    <!-- 战力判定明丽红 -->
    <color name="color_vs">#F43F5E</color>

    <!-- 系统文字颜色 -->
    <color name="text_primary">#0F172A</color>
    <color name="text_secondary">#64748B</color>

    <!-- 棋盘网格特定颜色 -->
    <color name="cell_station">#FFFFFF</color>
    <color name="cell_railway">#94A3B8</color>
    <color name="cell_camp">#F1F5F9</color>
    <color name="cell_hq">#E2E8F0</color>
    
    <color name="black">#000000</color>
    <color name="white">#FFFFFF</color>
    <color name="gray">#888888</color>
</resources>
''')

def update_themes_xml():
    themes_xml = r"E:\pad\app\src\main\res\values\themes.xml"
    with open(themes_xml, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.DoubleScreenChess" parent="Theme.MaterialComponents.Light.NoActionBar">
        <item name="colorPrimary">@color/color_a</item>
        <item name="colorPrimaryVariant">@color/color_a_glow</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSecondary">@color/color_b</item>
        <item name="colorSecondaryVariant">@color/color_b_glow</item>
        <item name="colorOnSecondary">@color/white</item>
        <item name="android:statusBarColor">@color/bg_dark</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowBackground">@color/bg_dark</item>
    </style>
</resources>
''')

def update_drawables():
    import os
    drawables_dir = r"E:\pad\app\src\main\res\drawable"
    
    piece_a = os.path.join(drawables_dir, "piece_a_bg.xml")
    with open(piece_a, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:top="2dp" android:left="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <solid android:color="#0284C7"/>
        </shape>
    </item>
    <item android:bottom="2dp" android:right="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <gradient android:startColor="#38BDF8" android:endColor="#0EA5E9" android:angle="135"/>
            <stroke android:width="1dp" android:color="#7DD3FC"/>
        </shape>
    </item>
</layer-list>
''')

    piece_b = os.path.join(drawables_dir, "piece_b_bg.xml")
    with open(piece_b, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:top="2dp" android:left="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <solid android:color="#059669"/>
        </shape>
    </item>
    <item android:bottom="2dp" android:right="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <gradient android:startColor="#34D399" android:endColor="#10B981" android:angle="135"/>
            <stroke android:width="1dp" android:color="#6EE7B7"/>
        </shape>
    </item>
</layer-list>
''')

    piece_enemy = os.path.join(drawables_dir, "piece_enemy_bg.xml")
    with open(piece_enemy, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:top="2dp" android:left="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <solid android:color="#BE123C"/>
        </shape>
    </item>
    <item android:bottom="2dp" android:right="2dp">
        <shape android:shape="rectangle">
            <corners android:radius="8dp"/>
            <gradient android:startColor="#FB7185" android:endColor="#E11D48" android:angle="135"/>
            <stroke android:width="1dp" android:color="#FDA4AF"/>
        </shape>
    </item>
</layer-list>
''')

    glass_panel = os.path.join(drawables_dir, "glass_panel_bg.xml")
    with open(glass_panel, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#E6FFFFFF"/>
    <corners android:radius="16dp"/>
    <stroke android:width="1dp" android:color="#33000000"/>
</shape>
''')

def update_board_view():
    board_file = r"E:\pad\app\src\main\java\com\huanglong\pad\game\BoardBackgroundView.kt"
    with open(board_file, "r", encoding="utf-8") as f:
        content = f.read()

    replacements = {
        '"#5B7AA8"': '"#94A3B8"', 
        '"#0B0E17"': '"#CBD5E1"', 
        '"#E2E8F0"': '"#FFFFFF"', 
        '"#1E293B"': '"#E2E8F0"', 
        '"#0A1E293B"': '"#33FFFFFF"', 
        '"#16213A"': '"#E0F2FE"', 
        '"#64748B"': '"#94A3B8"', 
        '"#475569"': '"#64748B"', 
        '"#8FA3BF"': '"#38BDF8"', 
        '"#3300E5FF"': '"#1A0EA5E9"', 
        '"#3339FF14"': '"#1A10B981"', 
    }

    for old_color, new_color in replacements.items():
        content = content.replace(old_color, new_color)

    with open(board_file, "w", encoding="utf-8") as f:
        f.write(content)

if __name__ == "__main__":
    update_colors_xml()
    update_themes_xml()
    update_drawables()
    update_board_view()
    print("UI update successful.")
