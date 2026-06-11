# 陆战棋 (Army Chess) 项目文档

## 1. 架构与双屏显示 (Dual-Screen Architecture)
本项目为一个支持双屏显示的 Android 原生“陆战棋”应用：
- **主屏 (Player A)**: 由 `MainActivity.kt` 承载，处理游戏主逻辑、音效（SoundPool）、以及触控交互。
- **副屏 (Player B)**: 利用 Android `Presentation` API，由 `GamePresentation.kt` 将独立的视图（玩家 B 的翻转视角）投射至另一块物理屏幕。
- **视图同步**: 主副屏的数据状态存储于 `MainActivity`，并在发生交互后通过 `refreshUI()` 方法同步两端的显示。

## 2. 视觉与排版设计 (UI/UX)
- **全局风格**: 采用苹果极简风格（Apple Minimalist）。背景色使用纯净灰（`#F2F2F7`），卡片控件采用高透纯白弥散阴影（Frosted Glass/亚克力质感，见 `glass_panel_bg.xml`）。
- **主题字体**: 全局采用开源的中文字体「站酷快乐体 (ZCOOL KuaiLe)」，文件位于 `app/src/main/res/font/zcool_kuaile.ttf`，在 `themes.xml` 中被设定为全局默认字体，营造欢快、休闲的游玩氛围。由于副屏 (Presentation) 的独立 Window Context 默认不继承主屏 Theme 全局字体，因此已通过在棋子布局 (`view_board_cell.xml`)、战斗对决布局 (`dialog_battle.xml`、`presentation_game.xml`) 中显式指定 `android:fontFamily`，以及在主副屏托盘适配器 (`TrayAdapter`) 中通过代码动态应用 `ResourcesCompat.getFont`，来确保双屏所有棋子字体完全一致。
- **沉浸式全屏**: 在主屏与副屏的 `onCreate()` 中，通过 `WindowInsetsControllerCompat` 移除了状态栏与导航栏，实现了纯净的沉浸式全屏游戏体验。
- **应用图标**: 采用精简扁平化（Flat Vector）的军事元素陆战棋图标（替换了 `mipmap` 目录下的图标），并将应用更名为“陆战棋”。
- **背景场景切换**: 主副屏各有「更换背景」按钮（`btn_change_bg`），各自独立循环切换：沙漠迷彩 → 丛林迷彩 → 蔚蓝海洋 → 静谧星空 → 春意江南 → 桃林春晓 → 樱花漫舞 → 幽谷兰香 → 山水田园（**已移除"无背景"档**）。**启动背景持久化**: 用 SharedPreferences（文件 `game_prefs`，主屏 key `bg_index_a`、副屏 `bg_index_b`）保存上次所选背景；各屏 `applyInitialBackground()` 在 `onCreate` 读取并应用——有记录则恢复，无记录/读到无背景则随机选一张兜底，确保启动不再是极简灰。场景图位于 `res/drawable-nodpi/bg_scene_1..9.png`，由根目录 `gen_bg.py` 生成；半透明玻璃面板（`glass_panel_bg`）令背景透出。
- **棋子玉石陶瓷质感**: 棋子背景采用多层 `layer-list` 渲染，包含大范围弥散光晕、3D立座阴影、1dp菲涅尔边缘瓷釉描边、球面径向聚光 and 偏光高光，模拟温润逼真的陶瓷光影，统一采用白色系玉质瓷质背景。
  * **玩家 A (主屏)**: Cold白玉脂瓷 (`piece_a_bg.xml`)，配深天青蓝字 (`R.color.piece_text_a`)，选中时 (`piece_a_selected_bg.xml`) 散发冰蓝荧光。
  * **玩家 B (副屏)**: Warm白玉脂瓷 (`piece_b_bg.xml`)，配活力橙字 (`R.color.piece_text_b`)，选中时 (`piece_b_selected_bg.xml`) 散发翡翠绿/极光绿荧光。
  * **敌方暗棋**: Ice灰白玉瓷 (`piece_enemy_bg.xml`)，采用色调比明棋稍暗的灰色调陶瓷底色，无文字，高雅且极易识别。


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

## 6. 规则变体（房规，刻意偏离标准陆战棋）
裁决与走法实现于 `game/GameEngine.kt` 与 `game/Models.kt`。以下两条为**有意采用的房规**，请勿当作 bug 改回标准规则：
- **碰地雷同归于尽**: 除工兵外，任何棋子碰地雷均与地雷同归于尽（标准规则是"踩雷阵亡、地雷保留"）。实现于 `GameEngine.resolveBattle()` 的地雷分支返回 `BOTH_DIED`；工兵挖雷仍是工兵活、雷亡，炸弹碰雷亦同归。
- **大本营棋子可移动**: 进入大本营的非军旗棋子可正常移出（标准规则是"进入大本营即锁定守家死子"）。已移除 `GameEngine.isValidMove()` 中的 `if (isHq(from)) return false` 锁定判断；军旗本身仍因属 `FLAG` 类型不可移动。大本营无斜线连接，故只能正交直进直出。
- **仍保持标准的部分**: 地雷仅能布在己方后两排（含非军旗占据的那个大本营，属标准合法雷位）；夺旗即终局；行营避难不可被吃。
- **一键布阵生成器（`MainActivity.autoLayout`）**: 入口按己方已布子数分流——已布 1~24（部分布防）走 `incrementalLayout`，只把托盘剩余子合法填入空位、**不动已布子**；已布 0 或 25（空盘/满盘）走 `fullAutoLayout` 整盘收回托盘后重排（满盘即"再点一次换阵"）。`fullAutoLayout` 50% 取预设阵型（`getClassicFormations`，**铁壁/飞花/均衡**三套，按真实棋盘可用行 5/3/4/3/5/5 书写、所见即所得，列翻转 `c→4-c` 真·左右镜像），50% 取受约束随机 `randomTypeLayout`（底层统一为 `constrainedFill`，增量布防亦复用之）。**战术约束（预设与随机一致，见 `GameEngine.satisfiesAutoConstraints`）**：司令必须压最前两排（A: row6-7）、旅长/师长/军长禁第一排（A: row6）；外加军旗入大本营、地雷后两排、炸弹避前两排。生成后用 `verifyLayout` + `satisfiesAutoConstraints` + `hasForbiddenTrappedPiece` 三重兜底重试（增量无解则提示并保持原状）。**注意 `verifyLayout` 刻意不含上述战术约束**——它被玩家手动"完成布阵"共用，战术约束只作用于一键布阵生成器，以免限制玩家手动自由布子。**困死房规**：允许出现困死子，但困死子只能是营长及以下（营长/连长/排长）；工兵、炸弹、以及高于营长的高阶子（团长~司令）不得被己方地雷/军旗围死，详见 `GameEngine.isTrapSensitive`。