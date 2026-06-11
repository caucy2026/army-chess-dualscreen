# 一键布阵优化任务

## 第一轮（已完成）

- [x] GameEngine 增加困死检测 hasForbiddenTrappedPiece/isPieceTrapped（仅拦截贵重子被困死）
- [x] MainActivity 阵型定义按真实棋盘行 [5,3,4,3,5,5] 重排并加注释（行为不变，提升可读性）
- [x] MainActivity autoLayout 镜像改为按列翻转真实坐标 c→4-c（真·左右镜像，保证合法）
- [x] MainActivity 增加受约束随机布阵生成器 randomTypeLayout + 合法性/困死兜底重试
- [x] gradle assembleDebug 构建验证通过

## 第二轮（修复 bug + 新增战术约束 + 增量布防）

- [x] GameEngine 新增 satisfiesAutoConstraints（司令前2排、旅师军长不第一排）
- [x] 重写 getClassicFormations 三套预设阵型（铁壁/飞花/均衡，修 (11,4) 困死 bug + 满足新约束）
- [x] 重构随机生成为 constrainedFill（军旗→地雷→司令→旅师军长→炸弹→其余）
- [x] 改造 autoLayout 为三分逻辑 + 增量布防 incrementalLayout/fullAutoLayout
- [x] 同步更新 CLAUDE.md 与 gemini.md 第6节
- [x] gradle assembleDebug 构建验证通过

## 第三轮（UI 棋子配送与配色优化）

- [x] 优化玩家 A 天青釉棋子背景及选中态背景 (`piece_a_bg.xml` 与 `piece_a_selected_bg.xml` 冰蓝发光)
- [x] 优化玩家 B 象牙白脂玉棋子背景与选中态背景 (`piece_b_bg.xml` 与 `piece_b_selected_bg.xml` 翡翠绿发光，微调基色为白玉偏淡翠绿)
- [x] 优化敌方暗棋朱砂印泥红瓷背景 (`piece_enemy_bg.xml` 饱和度降低，契合山水田园)
- [x] 调整 MainActivity 逻辑，在游戏结束亮明 B 棋子时，文字设为黑色以保证对比度
- [x] 调整 GamePresentation 逻辑，在主/副屏渲染 B 己方棋子、对决面板及托盘时，文字全部改设为黑色
- [x] 运行 Gradle 构建，编译验证
- [x] 部署测试并进行双屏截图验证，确保界面高级且文字清晰
- [x] 同步更新 CLAUDE.md 与 gemini.md 视觉与排版设计小节
- [x] 提交全部更改并记录到 ui_review.md

