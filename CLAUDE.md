# CLAUDE.md

## 📌 项目概述
本项目为通用便签本与草稿工作区（Pad），主要面向**双屏 Android 平板应用开发**（详细设备硬件与屏幕配置参考 [device_info.md](file:///E:/pad/device_info.md)），同时也用于临时代码编写、小工具实验及测试。

## 🛠️ 构建与测试命令
本项目为标准的 Android Gradle 项目，因环境限制，编译命令已固化本地 PyCharm JBR 17 指针。常用指令如下：

- **一键编译并无线安装启动**：
  ```bash
  & "C:\Users\Laomo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug; adb connect 172.21.16.133:5555; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am start -n com.huanglong.pad/com.huanglong.pad.MainActivity
  ```
- **实机截取双屏画面至电脑桌面**：
  ```bash
  adb connect 172.21.16.133:5555; adb shell screencap -p /sdcard/screenshot_main.png; adb shell screencap -d 2 -p /sdcard/screenshot_sub.png; adb pull /sdcard/screenshot_main.png C:\Users\Laomo\Desktop\screenshot_main.png; adb pull /sdcard/screenshot_sub.png C:\Users\Laomo\Desktop\screenshot_sub.png
  ```
- **详细技术设计与踩坑手册**：参考项目根目录下的 [DEVELOPMENT.md](file:///E:/pad/DEVELOPMENT.md)。

## 🎮 双屏军棋核心技术决策与约定
1. **物理旋转 180° 与控制栏同序声明（勿倒序，已修正）**：副屏界面在 [presentation_game.xml](file:///E:/pad/app/src/main/res/layout/presentation_game.xml) 中通过根布局 `android:rotation="180"` 校正 B 背靠背读屏的文字方向。由于"XML 布局 → 旋转 180° 入帧缓冲 → B 从背面观看再翻 180°"**双重翻转相互抵消，B 感知到的顺序就是 XML 声明顺序**（与决策 2 的棋盘链路同理）。因此副屏控制面板必须与 [activity_main.xml](file:///E:/pad/app/src/main/res/layout/activity_main.xml) **完全同序声明**（Title 最上、状态、轮次、按钮 一键布阵|完成布阵|重置、托盘最下），A/B 两屏风格才一致（标题都在左上角）。历史上曾按"完全倒序排列"编写——那是错误物理推理，导致 B 看到标题在底部、托盘在顶部，勿回退。
2. **副屏 B 视角渲染（关键，勿退回直连映射）**：副屏是背靠背双屏的另一面，B 从背面观看等效于再叠加一次 180°。单靠 `android:rotation="180"` 会让文字正向、但 B 的布防区落到 B 眼里的**上方**（旋转与背面观看两次翻转相互抵消位置、只剩文字校正）。正确做法是把 B 的棋子画到**画布下半区**：①背景 `binding.boardBackgroundView.perspective = Player.B`；②[GamePresentation.kt](file:///E:/pad/app/src/main/java/com/huanglong/pad/GamePresentation.kt) `initBoardGrid` 中格子按 `logicPos.reverse()` 摆放到物理网格（`cells` 仍以 `logicPos` 为键、点击回传 `logicPos`，逻辑判定不受影响）；③保留 XML 的 `rotation="180"` 仅用于校正 B 读屏文字/控件方向。链路：B 逻辑子(Row0-5)→grid 视觉底部→旋转到帧缓冲顶部→B 背面观看落到底部。主屏(A)仍为 `perspective=A` 直连映射、无旋转。
3. **敌方暗棋红色"天窗"填充突出（非描边）**：敌方暗棋使用 [piece_enemy_bg.xml](file:///E:/pad/app/src/main/res/drawable/piece_enemy_bg.xml)，采用醒目的**霓虹红渐变填充**（`#E6F05252`→`#E6B91C1C`）整片"天窗"染红、不再靠细描边；锁头 `🔒` 颜色相应改为 `R.color.white`，在红底上保持高对比清晰可见。
4. **行军高亮小圆点染色**：行军路径可达指示点不可覆盖纯色背景（会导致 10% 透明度接近隐形），必须在代码中对已有的 oval drawable 进行 `setTint` 染色（主屏冰蓝 `color_a`，副屏极光绿 `color_b`）以保持圆形发光质感。
5. **全程暗棋（碰子不翻明）**：棋子交锋后不公开身份，攻守双方存活子均保持暗棋（红锁），仅在游戏结束（`GameStage.FINISHED`）才翻开看真身。`ChessPiece` 已移除 `isRevealed` 字段，`moveOrAttack` 不再有任何亮明赋值。**唯一例外（标准规则）**：司令阵亡后该方军旗强制亮明，见第 6 条。
6. **标准陆战棋规则修正（依据维基/竞赛规则，勿回退）**：
   - **铁路拓扑（依标准棋盘图，勿改回整列铁路）**：横向铁路位于 Row 1/5/6/10 全宽；纵向铁路仅左右两侧 Col 0/4 纵贯 Row 1..10；中央 Col 2 **只有跨山界的 Row 5-6 一小段**是铁路，半场内为普通公路（`initRailways` 不再把 Col 2 整列加入）。因此所有行营都不在铁路上，旧的 `&& !isCamp(from)` 行营铁路补丁已删除。
   - **界河跨越**：仅 Col 0/2/4 三条通道可直线跨界河，Col 1/Col 3 为"山界"阻断、无斜线跨河（`isRiverCrossValid` 仅放行 `from.col==to.col && col∈{0,2,4}`）。
   - **大本营死子**：进入大本营的棋子不可再移动，`isValidMove` 中 `isHq(from)` 直接返回 false（军旗及第二大本营的守家子均不可动）。

   - **炸弹规则**：炸弹不可放置在最前两排，可自由放置于后排格；无论碰谁或被碰均同归于尽。
   - **大本营无斜线**：已移除 `isRoadConnected` 中大本营对角线判定，大本营只能从正前方同列直线（或同排横向）进出，敌方仅能正面攻入/夺旗。
   - **地雷裁决**：非工兵碰雷 → 碰雷者阵亡、**地雷保留**（`resolveBattle` 返回 `DEFENDER_WON`）；工兵挖雷活；炸弹碰雷同归于尽。勿改回旧的"碰雷同归于尽"。
   - **司令阵亡亮军旗**：司令战死时其阵营加入 `MainActivity.revealedFlagOwners`，敌方军旗改为亮明渲染（红底显示"军旗"文字），主副屏同步。
   - **无棋可走判负**：每次回合切换后用 `GameEngine.hasAnyValidMove` 检查轮到的一方，无合法走法即判负；`MainActivity.winReason` 记录胜负原因（"军旗被夺"/"xx 无棋可走"），双屏结束文案展示。
   - **经典阵法一键布阵**：移除了原有的无序纯随机布阵算法，重构为从“乌龟阵 (重防)”、“飞花阵 (重攻)”、“均衡阵 (攻守兼备)”三套标准布防序列中随机抽取。抽取后有 50% 概率进行左右镜像翻转，从而演化出 6 种实战经典阵型，并会在屏幕上 Toast 提示当前启用的阵法名称。
7. **棋盘路线视觉（公路提亮 + 棋子缩小 + 界河美化）**：公路连线 `roadPaint` 用 `#5B7AA8`、宽 4.5f 提亮；棋子 `view_board_cell.xml` 的 `tv_piece_text` 加 `layout_margin=5dp`、字号 13sp 缩小一圈，露出底层铁路/公路。中央 Col 2 竖线在半场内是公路细线、仅跨山界一段画铁路枕木（与第 6 条拓扑一致）。界河带画横向渐变底色垫底，Col 1/3 处画"山界"圆圈（`mountainFillPaint`/`mountainStrokePaint` + `riverTextPaint` 印章字），Col 0/2/4 保留三个"前线"格；整个棋盘以视角主题色加圆角霓虹外框（`frameGlowPaint`）收束。
8. **双屏启动防呆（主屏强制迁回）**：从副屏侧桌面点图标启动时，系统会把 MainActivity 分配到副屏 (Display 2)，而 Presentation 也固定投向副屏将其覆盖，导致主屏空白、副屏只见 B 界面。`MainActivity.onCreate` 开头检测 `windowManager.defaultDisplay.displayId != Display.DEFAULT_DISPLAY` 时，用 `ActivityOptions.launchDisplayId = 0` + `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` 重启自身迁回主屏并 `finish()`（在 `setContentView` 之前 return，跳过 onStart，避免 lateinit binding 崩溃）。验证方式：`adb shell am start --display 2 -n com.huanglong.pad/.MainActivity` 后 dumpsys 确认任务落回 displayId=0。
9. **对决裁决双屏同步 + 音效 + 动画**：碰子时 `moveOrAttack` 调 `playBattleSound(result)`（`SoundPool` 播放 `res/raw` 三种 wav：普通 `battle_clash` / 同归 `battle_boom` / 夺旗 `battle_win`，`USAGE_GAME`，需设备媒体音量 > 0），并同时调主屏 `showBattleDialog`（`AlertDialog`）与 `gamePresentation.showBattleDialog`（副屏用 `presentation_game.xml` 预置全屏浮层 `battle_overlay`，随父 `rotation=180°` 对 B 正向）。**副屏浮层勿改回 `window.addContentView` —— 那会导致浮层尺寸/居中在 Presentation 上不可靠、显示不全。** 文案由 `battleResultText(result, attacker, viewer)` 按观看方视角生成（搞笑风格、己方/对方两套）。对决文字语音播报已全部优化为**本地预置搞笑音频文件播放**，放置在 `res/raw` 目录下（`tts_attacker_won.mp3`、`tts_defender_won.mp3`、`tts_both_died.mp3`、`tts_flag_captured.mp3`）。在碰子时通过 `SoundPool`（最大流上限已提升至 8 信道以支持多路混音）内存预加载实现**0延迟**瞬间播放，完美规避了系统缺少 TTS 引擎或需配置包可见性权限的问题。播报遵循谁主动出击就播放谁那一侧视角的搞笑文案。入场动画见 [BattleAnim.kt](file:///E:/pad/app/src/main/java/com/huanglong/pad/BattleAnim.kt)（卡片缩放淡入 + 攻防对冲），主副屏共用。**两屏等大**：主屏逻辑 density 2.0、副屏约 1.33，副屏卡片在 `BattleAnim.play` 中按 `mainDensity/subDensity` 比例放大，保证与主屏物理等大（勿写死，按 `displayMetrics.density` 动态算）。

10. **双屏独立提示系统（废弃原生 Toast）**：由于 Android 11+ 对后台和自定义 View Toast 限制极严，容易导致主屏不显示提示。现已废弃系统 `Toast`，在主屏 `activity_main.xml` 和副屏 `presentation_game.xml` 中分别预置了专属的 `tv_tip` 控件（其中副屏 `rotation=180`）。`MainActivity.showToast(msg, target)` 会按触发方路由提示：目标为 A 弹主屏 tip，目标为 B 弹副屏 tip；全局消息（重新开始、对决开始）双屏同时提示。
11. **明亮主题立体背景（纯 XML 渐变，无位图）**：窗口背景 [app_window_bg.xml](file:///E:/pad/app/src/main/res/drawable/app_window_bg.xml) 四层 layer-list：对角暖灰渐变垫底 → 左上普鲁士蓝/右下琥珀金低透明度"极光"径向色晕（呼应 A/B 阵营色）→ 顶部中央径向柔光（顶光源）→ 底部轻暗角收束景深。面板 [glass_panel_bg.xml](file:///E:/pad/app/src/main/res/drawable/glass_panel_bg.xml) 为悬浮卡片：下方投影层 + 受光面纵向渐变 + 玻璃白高光描边。轮次指示器改用 [turn_indicator_bg.xml](file:///E:/pad/app/src/main/res/drawable/turn_indicator_bg.xml) 内嵌凹槽渐变（勿再用纯色 `bg_dark` 色块，背景已是渐变会显突兀）。

12. **双边确认重新开始**：单边点击「重新开始」只发起请求（`MainActivity.requestReset(player)`，`resetRequests` 集合记录），A/B 双方都点击后 `resetGame()` 才真正执行；期间双屏按各自视角提示"已发起/对方请求"。任一方继续走子即清空未完成的请求（防止陈旧请求导致意外重新开始）。无副屏调试模式（`gamePresentation == null`）下单边直接重新开始。
13. **对弈阶段规则速查面板**：布阵完成进入 PLAYING/FINISHED 后，左侧面板的待部署托盘（`tv_tray_label` + `rv_piece_tray`）隐藏，改为显示可滚动的游戏规则速查 `sv_rules`（文案在 strings.xml `game_rules_text`，内容须与 GameEngine 实际规则一致），主副屏同构。
14. **玩家控制面板“三明治”风景框方案**：左侧玩家控制面板（包括规则和待部署托盘区域）采用了三层结构以实现高质感：底层为原版的 `glass_panel_bg` 提供立体投影和高光边框支持；中层为 `CardView`，通过强制取消留白（`app:cardPreventCornerOverlap="false"` 和 `app:cardUseCompatPadding="false"`）精准裁出 16dp 圆角，内部放置无缝裁切（`centerCrop`）的清新风景图以及一层 65% 透明度（`#A6FFFFFF`）的白色蒙版以提升文字清晰度和磨砂感；顶层再覆盖一层 `glass_stroke.xml` 以强调边框质感。同时，规则显示逻辑已修改，任意一方完成布阵后立刻切换显示规则面板，不再等待双方同时就绪。

## ✍️ 开发规范与约定
为了保持代码整洁和协作顺畅，请遵循以下规范：
1. **简单至上 (Simplicity First)**：只编写解决问题所需的最小代码，不编写投机性或未要求的代码。
2. **微创修改 (Surgical Changes)**：仅修改必须修改的文件，切勿随意格式化或重构未损坏的相邻代码。
3. **任务跟踪 (Goal-Driven)**：使用 `tasks.md` 来追踪任务进度。
4. **双向文档同步 (Dual-Context Sync)**：更新 `CLAUDE.md` 时，必须同步创建/更新 `gemini.md`，保持内容完全一致。
5. **中文回复 (Reply in Chinese)**：默认使用中文与用户交流，保持专业与简洁。
