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
