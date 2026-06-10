# 陆战棋规则修正 + 棋盘铁路修正 + 界面美化

- [x] GameEngine：修正铁路拓扑（Col 2 仅保留跨山界段），移除行营铁路补丁
- [x] GameEngine：修正地雷裁决（碰雷者亡、雷保留），新增无棋可走判负 hasAnyValidMove
- [x] MainActivity：司令阵亡亮军旗 + 无棋可走判负接入 + 结束文案区分胜负原因
- [x] GamePresentation：副屏同步军旗亮明渲染与结束文案
- [x] BoardBackgroundView：铁路绘制修正 + 界河带渐变 + 山界圆圈 + 棋盘外框美化
- [x] 编译安装实机验证 + 双屏截图确认
- [x] 更新 CLAUDE.md / gemini.md 技术决策记录
- [x] 修复副屏侧启动导致主屏空白：onCreate 检测非主屏启动自动迁回 Display 0（已实机验证）
- [x] 副屏控制面板与主屏风格统一：presentation_game.xml 面板改为与主屏完全同序声明（双重翻转抵消原理，已实机验证标题居左上）
