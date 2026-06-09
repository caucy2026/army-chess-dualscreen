# CLAUDE.md

## 📌 项目概述
本项目为通用便签本与草稿工作区（Pad），主要面向**双屏 Android 平板应用开发**（详细设备硬件与屏幕配置参考 [device_info.md](file:///E:/pad/device_info.md)），同时也用于临时代码编写、小工具实验及测试。

## 🛠️ 构建与测试命令
本项目为标准的 Android Gradle 项目，因环境限制，编译命令已固化本地 PyCharm JBR 17 指针。常用指令如下：

- **一键编译并无线安装启动**：
  ```bash
  & "C:\Users\Laomo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug; adb connect 192.168.1.196:5555; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am start -n com.huanglong.pad/com.huanglong.pad.MainActivity
  ```
- **实机截取双屏画面至电脑桌面**：
  ```bash
  adb connect 192.168.1.196:5555; adb shell screencap -p /sdcard/screenshot_main.png; adb shell screencap -d 2 -p /sdcard/screenshot_sub.png; adb pull /sdcard/screenshot_main.png C:\Users\Laomo\Desktop\screenshot_main.png; adb pull /sdcard/screenshot_sub.png C:\Users\Laomo\Desktop\screenshot_sub.png
  ```
- **详细技术设计与踩坑手册**：参考项目根目录下的 [DEVELOPMENT.md](file:///E:/pad/DEVELOPMENT.md)。

## 🎮 双屏军棋核心技术决策与约定
1. **物理旋转 180° 与控制栏防颠倒**：副屏界面在 [presentation_game.xml](file:///E:/pad/app/src/main/res/layout/presentation_game.xml) 中通过 `android:rotation="180"` 物理翻转。为了防止旋转后操作栏控件顺序上下倒置，XML 内部的元素声明必须采用**完全倒序排列**（Title 在最底下，托盘在最顶上），使得旋转后托盘完美还原到屏幕下侧手边。
2. **副屏 B 视角渲染（关键，勿退回直连映射）**：副屏是背靠背双屏的另一面，B 从背面观看等效于再叠加一次 180°。单靠 `android:rotation="180"` 会让文字正向、但 B 的布防区落到 B 眼里的**上方**（旋转与背面观看两次翻转相互抵消位置、只剩文字校正）。正确做法是把 B 的棋子画到**画布下半区**：①背景 `binding.boardBackgroundView.perspective = Player.B`；②[GamePresentation.kt](file:///E:/pad/app/src/main/java/com/huanglong/pad/GamePresentation.kt) `initBoardGrid` 中格子按 `logicPos.reverse()` 摆放到物理网格（`cells` 仍以 `logicPos` 为键、点击回传 `logicPos`，逻辑判定不受影响）；③保留 XML 的 `rotation="180"` 仅用于校正 B 读屏文字/控件方向。链路：B 逻辑子(Row0-5)→grid 视觉底部→旋转到帧缓冲顶部→B 背面观看落到底部。主屏(A)仍为 `perspective=A` 直连映射、无旋转。
3. **敌方暗棋红色"天窗"填充突出（非描边）**：敌方暗棋使用 [piece_enemy_bg.xml](file:///E:/pad/app/src/main/res/drawable/piece_enemy_bg.xml)，采用醒目的**霓虹红渐变填充**（`#E6F05252`→`#E6B91C1C`）整片"天窗"染红、不再靠细描边；锁头 `🔒` 颜色相应改为 `R.color.white`，在红底上保持高对比清晰可见。
4. **行军高亮小圆点染色**：行军路径可达指示点不可覆盖纯色背景（会导致 10% 透明度接近隐形），必须在代码中对已有的 oval drawable 进行 `setTint` 染色（主屏冰蓝 `color_a`，副屏极光绿 `color_b`）以保持圆形发光质感。
5. **全程暗棋（碰子不翻明）**：棋子交锋后不公开身份，攻守双方存活子均保持暗棋（红锁），仅在游戏结束（`GameStage.FINISHED`）才翻开看真身。`ChessPiece` 已移除 `isRevealed` 字段，敌方棋子渲染条件仅判 `gameStage == FINISHED`，`moveOrAttack` 不再有任何亮明赋值。
6. **标准陆战棋规则修正（依据维基/竞赛规则，勿回退）**：
   - **界河跨越**：仅 Col 0/2/4 三条纵向铁路可直线跨界河，Col 1/Col 3 为"山界"阻断、无斜线跨河（`isRiverCrossValid` 仅放行 `from.col==to.col && col∈{0,2,4}`）。
   - **大本营死子**：进入大本营的棋子不可再移动，`isValidMove` 中 `isHq(from)` 直接返回 false（军旗及第二大本营的守家子均不可动）。
   - **行营单步**：行营内棋子每次只能走一步、不享受铁路直达，铁路判定附加 `&& !isCamp(from)`（影响铁路上的中央行营 (3,2)/(8,2)）。
   - **大本营无斜线**：已移除 `isRoadConnected` 中大本营对角线判定，大本营只能从正前方同列直线（或同排横向）进出，敌方仅能正面攻入/夺旗。
7. **棋盘路线视觉（公路提亮 + 棋子缩小）**：公路连线 `roadPaint` 用 `#5B7AA8`、宽 4.5f 提亮，使整张路网清晰；棋子 `view_board_cell.xml` 的 `tv_piece_text` 加 `layout_margin=5dp`、字号 13sp 缩小一圈，露出底层铁路/公路。注意：中央 Col 2 竖线本就是三条纵向铁路之一，与 Col 0/4 等粗属正常。
8. **对决裁决双屏同步 + 音效 + 动画**：碰子时 `moveOrAttack` 调 `playBattleSound(result)`（`SoundPool` 播放 `res/raw` 三种 wav：普通 `battle_clash` / 同归 `battle_boom` / 夺旗 `battle_win`，`USAGE_GAME`，需设备媒体音量 > 0），并同时调主屏 `showBattleDialog`（`AlertDialog`）与 `gamePresentation.showBattleDialog`（副屏用 `presentation_game.xml` 预置全屏浮层 `battle_overlay`，随父 `rotation=180°` 对 B 正向）。**副屏浮层勿改回 `window.addContentView` —— 那会导致浮层尺寸/居中在 Presentation 上不可靠、显示不全。** 文案由 `battleResultText(result, attacker, viewer)` 按观看方视角生成（搞笑风格、己方/对方两套）。对决文字语音播报已全部优化为**本地预置搞笑音频文件播放**，放置在 `res/raw` 目录下（`tts_attacker_won.mp3`、`tts_defender_won.mp3`、`tts_both_died.mp3`、`tts_flag_captured.mp3`）。在碰子时通过 `SoundPool`（最大流上限已提升至 8 信道以支持多路混音）内存预加载实现**0延迟**瞬间播放，完美规避了系统缺少 TTS 引擎或需配置包可见性权限的问题。播报遵循谁主动出击就播放谁那一侧视角的搞笑文案。入场动画见 [BattleAnim.kt](file:///E:/pad/app/src/main/java/com/huanglong/pad/BattleAnim.kt)（卡片缩放淡入 + 攻防对冲），主副屏共用。**两屏等大**：主屏逻辑 density 2.0、副屏约 1.33，副屏卡片在 `BattleAnim.play` 中按 `mainDensity/subDensity` 比例放大，保证与主屏物理等大（勿写死，按 `displayMetrics.density` 动态算）。

## ✍️ 开发规范与约定
为了保持代码整洁和协作顺畅，请遵循以下规范：
1. **简单至上 (Simplicity First)**：只编写解决问题所需的最小代码，不编写投机性或未要求的代码。
2. **微创修改 (Surgical Changes)**：仅修改必须修改的文件，切勿随意格式化或重构未损坏的相邻代码。
3. **任务跟踪 (Goal-Driven)**：使用 `tasks.md` 来追踪任务进度。
4. **双向文档同步 (Dual-Context Sync)**：更新 `CLAUDE.md` 时，必须同步创建/更新 `gemini.md`，保持内容完全一致。
5. **中文回复 (Reply in Chinese)**：默认使用中文与用户交流，保持专业与简洁。
