# 陆战棋 (Army Chess) 项目文档

## 1. 架构与双屏显示 (Dual-Screen Architecture)
本项目为一个支持双屏显示的 Android 原生“陆战棋”应用：
- **主屏 (Player A)**: 由 `MainActivity.kt` 承载，处理游戏主逻辑、音效（SoundPool）、以及触控交互。
- **副屏 (Player B)**: 利用 Android `Presentation` API，由 `GamePresentation.kt` 将独立的视图（玩家 B 的翻转视角）投射至另一块物理屏幕。
- **视图同步**: 主副屏的数据状态存储于 `MainActivity`，并在发生交互后通过 `refreshUI()` 方法同步两端的显示。

## 2. 视觉与排版设计 (UI/UX)
- **全局风格**: 采用苹果极简风格（Apple Minimalist）。背景色使用纯净灰（`#F2F2F7`），卡片控件采用高透纯白弥散阴影（Frosted Glass/亚克力质感，见 `glass_panel_bg.xml`）。
- **主题字体**: 全局采用开源的中文字体「站酷快乐体 (ZCOOL KuaiLe)」，文件位于 `app/src/main/res/font/zcool_kuaile.ttf`，在 `themes.xml` 中被设定为全局默认字体，营造欢快、休闲的游玩氛围。
- **沉浸式全屏**: 在主屏与副屏的 `onCreate()` 中，通过 `WindowInsetsControllerCompat` 移除了状态栏与导航栏，实现了纯净的沉浸式全屏游戏体验。
- **应用图标**: 采用精简扁平化（Flat Vector）的军事元素陆战棋图标（替换了 `mipmap` 目录下的图标），并将应用更名为“陆战棋”。
- **背景场景切换**: 主副屏各有「更换背景」按钮（`btn_change_bg`），各自独立循环切换：无背景 → 沙漠迷彩 → 丛林迷彩 → 蔚蓝海洋 → 静谧星空。场景图位于 `res/drawable-nodpi/bg_scene_1..4.png`，由根目录 `gen_bg.py` 生成；半透明玻璃面板（`glass_panel_bg`）令背景透出。

## 3. 核心游戏状态与已知修复
- **行军轨迹残留问题**: `MainActivity.kt` 内的 `lastMoveFrom` 和 `lastMoveTo` 负责记录并高亮上一回合的棋子移动轨迹。在每次游戏重置 (`initGameData()`) 时，必须确保将其清空为 `null`，否则上一局的残影会遗留在棋盘上（已修复）。
- **旋转锁定**: 为确保对战体验不受重力感应影响，应用在启动时会自动锁定系统的传感器旋转行为。

## 4. 裁决语音 (TTS) 音色映射
- `res/raw/tts_*.mp3`（每类 10 个）由 edge-tts 生成，索引 0..4 为玩家 A 音色（男声 `zh-CN-YunxiNeural`），索引 5..9 为玩家 B 音色（女声 `zh-CN-XiaoxiaoNeural`）。
- `MainActivity.kt` 的 `textIndex` 按攻方归属选择区间（A 取 0..4，B 取 5..9），实现"谁走子就播谁的声音"。
- 文案列表位于 `MainActivity.kt` 的 companion object，与项目根目录 `generate_tts.py` 中的 `TEXTS` 必须保持一致；改文案后运行 `python generate_tts.py` 重新生成全部音频。

## 5. 构建与测试说明
- 项目可以直接通过 Android Studio 点击 Run 运行。
- 如果通过命令行，请使用 Gradle 构建命令：`gradle assembleDebug`，随后使用 `adb install` 推送到双屏 Android 设备。