# 一键布阵优化任务

- [x] GameEngine 增加困死检测 hasForbiddenTrappedPiece/isPieceTrapped（仅拦截贵重子被困死）
- [x] MainActivity 阵型定义按真实棋盘行 [5,3,4,3,5,5] 重排并加注释（行为不变，提升可读性）
- [x] MainActivity autoLayout 镜像改为按列翻转真实坐标 c→4-c（真·左右镜像，保证合法）
- [x] MainActivity 增加受约束随机布阵生成器 randomTypeLayout + 合法性/困死兜底重试
- [x] gradle assembleDebug 构建验证通过
