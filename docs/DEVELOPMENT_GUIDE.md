# 陆战棋 (Army Chess) 双屏版 开发与维护详细指南

本项目是一款基于 **KEMI_7M600_T1** 双屏 Android 平板专属定制的 Android 原生陆战棋（暗棋）对战应用。支持主屏（玩家 A）与副屏（玩家 B）独立视角对弈，配合多方言搞笑语音裁决系统。

---

## 📂 1. 项目目录与核心文件说明

### 1.1 核心代码结构
```
app/src/main/java/com/huanglong/pad/
│
├── MainActivity.kt           # 主屏 Activity，承载游戏主状态、SoundPool 音频库、主屏触控交互
├── GamePresentation.kt       # 副屏 Presentation，投射独立视角（玩家 B 视角），渲染独立画布并接收状态同步
├── BattleAnim.kt             # 局中战斗对决对消/获胜的过渡动画逻辑
│
└── game/
    ├── Models.kt             # 定义棋子类型(PieceType)、阵营(Player)、坐标(Position)、状态(GameStage)等基础实体
    ├── GameEngine.kt         # 核心裁判引擎：走棋合法性校验、碰子裁决、布阵校验、一键布防战术约束与困死检查
    └── BoardBackgroundView.kt# 自定义 View，绘制极简风格棋盘（行营、铁轨、大本营等物理网格）
```

### 1.2 资源与构建脚本
* **[strings.xml](file:///E:/pad/app/src/main/res/values/strings.xml)**：包含所有 UI 文本及「📖 游戏规则」面板内的规则描述。
* **[generate_tts.py](file:///E:/pad/scripts/generate_tts.py)**：方言语音 MP3 素材一键生成脚本，维护或修改文案后须重新运行此脚本。
* **[gen_bg.py](file:///E:/pad/scripts/gen_bg.py)**：山水、沙漠、丛林等背景图的底层几何与纹理生成脚本。
* **`app/src/main/res/raw/`**：包含 160 个碰子裁决预置的方言 MP3 音频文件（命名格式 `tts_{result}_{dialect_index}_{voice_index}.mp3`）。

---

## 🖥️ 2. 设备物理环境与双屏映射

### 2.1 双屏硬件规格 (KEMI_7M600_T1)
* **芯片**: 海思 Hi3781V730
* **系统**: Android 12 (API 31)，开发版固件，默认已开启 root 权限和 ADB 调试。
* **显示设备拓扑**:
  * **主屏 (Display 0)**：`INTERNAL` 屏幕，1920 × 1280 (3:2)，物理横屏方向 (`landscape`)，由 `MainActivity` 渲染展示。
  * **副屏 (Display 2)**：`EXTERNAL` 屏幕（HDMI），1920 × 1280 (3:2)，物理逆向横屏 (`seascape`，相当于旋转 180°)，由 `GamePresentation` 渲染展示。

### 2.2 旋转 180° 与坐标映射踩坑
* **物理旋转 180°**：由于副屏背靠背放置，玩家 B 看到的内容是倒转的。我们在副屏布局 XML 的最外层静态声明了 `android:rotation="180"` 锁死，用来摆正 B 的阅读视角。
* **物理抵消与同序声明**：由于“物理旋转 180°”与“B 视角的 180° 翻转”互相抵消，**B 感知到的屏幕方向顺序正是 XML 静态声明顺序**。因此，副屏控制面板（如状态栏、操作按钮、棋子托盘）在 XML 中的排布次序必须与主屏完全同序声明（标题在上，按钮居中，托盘贴近底部手边），否则会导致两屏布局风格不一致。
* **触控直连**：副屏旋转 180° 后，Android 触控驱动会自动旋转输入坐标。因此在代码中**不需**对 `MotionEvent` 的点击进行坐标行列翻转，坐标直接与逻辑 `Position(r, c)` 一对一物理直连，极大地简化了算法逻辑。

---

## ⚔️ 3. 游戏规则变体（自定义房规）

游戏裁决与行走判定实现于 `GameEngine.kt`。项目采用了特殊的“房规”，旨在增强对战的随机性与节奏感：

### 3.1 碰地雷同归于尽
* **规则**：除了**工兵**可以无伤排雷（工兵活，地雷消）外，其余任何棋子（从排长到司令、以及炸弹）碰雷均与地雷**同归于尽**（双方同时阵亡并从棋盘消失）。
* **实现**：`GameEngine.resolveBattle()` 中的地雷裁决逻辑会返回 `BOTH_DIED`。

### 3.2 大本营非死子可移动
* **规则**：进入大本营的棋子（除军旗本身外）**仍可正常移出**，不锁定。
* **限制**：大本营在棋盘上无斜向连线，只与外部轨道正交相连，所以子只能**正交直进直出**。
* **实现**：已在 `GameEngine.isValidMove()` 中移除对大本营的死锁检查。

### 3.3 行军轨迹残影高亮
* **规则**：对弈期间永久在棋盘上点亮上一回合走棋的“起点”和“终点”。
* **实现**：在 `view_board_cell.xml` 的底层设置了 `v_last_move_highlight` (圆角圆滑、红框绿底)，由 `MainActivity` 内的 `lastMoveFrom`/`lastMoveTo` 状态决定其是否显示。每次重置棋局时该状态会自动置空防残留。

---

## 🤖 4. 一键布阵算法与战术约束

应用支持“部分布防”和“满盘重洗”两种模式，由 `MainActivity.autoLayout` 分流处理：

### 4.1 增量布防 (`incrementalLayout`)
* **触发条件**：当前已手动布子 1 ~ 24 个。
* **行为**：**绝对不动已放置的棋子**，仅将托盘中剩下的棋子通过受约束的逻辑随机填入空白合法格中。
* **如果无解**：若剩余空格无法满足基本布阵规则（例如地雷必须在后两排，而托盘剩下的地雷无法放满），会提示布防冲突，并保持原样。

### 4.2 全盘布防 (`fullAutoLayout`)
* **触发条件**：当前未布防（0 个子）或已经摆满 25 个子（再次点击相当于一键换阵）。
* **行为**：全盘收回托盘，然后重排。
  * **50% 概率**：从预设经典阵型（**铁壁、飞花、均衡**）中随机挑选一个，并作列左右对称翻转（保证绝对坐标映射在 B 侧合法）。
  * **50% 概率**：执行受约束的递归随机排布。

### 4.3 一键布阵战术约束 (`satisfiesAutoConstraints`)
为了确保自动布阵的棋盘富有博弈意义，一键布阵生成器必须满足以下约束（**注：手动布防不受此限制，只受基础布局校验约束**）：
1. **司令**必须布在最前面两排（玩家 A 为 6-7 行，玩家 B 为 4-5 行），便于前期压制。
2. **军长/师长/旅长**禁止放在第一排，防止开局送头。
3. **军旗**必须放在大本营，**地雷**必须在后两排，**炸弹**禁止放在第一排，**行营**内不得布子。
4. **高阶子防困死约束**（困死房规）：高阶棋子（司令、军长、师长、旅长、团长）以及工兵、炸弹，**禁止**被己方的地雷或军旗在底盘角落困死（即必须有至少一个通路可移动）。而营长、连长、排长则被允许困死。

---

## 🎙️ 5. 多方言搞笑音效系统

### 5.1 4 种方言配置与文案
系统预置了 4 种方言来朗读碰子对决的搞笑文案：
1. **普通话 (0)**：A 男声（Yunxi），B 女声（Xiaoxiao）。
2. **四川话 (1)**：特色川味搞笑段子，拟合四川普通话风格。
3. **河南话 (2)**：A 河南腔男声，B 关中豪爽秦腔女声（Xiaoni）。
4. **粤语 (3)**：纯粤语魔性搞笑港风对决文案。

### 5.2 资源命名与同步
* 音频资源存放在 `app/src/main/res/raw/` 下，文件命名格式：`tts_{result}_{dialect_index}_{voice_index}.mp3`。
  * `result`：对决结果 (`attacker_won` 攻方胜, `defender_won` 守方胜, `both_died` 同归于尽, `flag_captured` 夺旗)。
  * `dialect_index`：`0..3`（方言索引）。
  * `voice_index`：`0..9`（每一类结果都有 10 句不同的搞笑文案可供循环播放）。
* **极重要**：`MainActivity.kt` 伴生对象中声明的搞笑文案列表，必须与 `scripts/generate_tts.py` 内的 `TEXT_DATA` 字典保持严格的字面一致！修改文案后，需通过 Python 脚本重新生成音频包并加入到 Git。

---

## 🎨 6. 视觉与排版设计 (Apple Minimalist)

* **苹果极简风**：背景采用高透纯白弥散阴影（Frosted Glass / 亚克力玻璃面板效果 `glass_panel_bg.xml`），完美透出底盘场景背景。
* **背景切换与持久化**：支持沙漠迷彩、丛林迷彩、蔚蓝海洋、静谧星空等 9 种背景。主副屏可独立切换，通过 `SharedPreferences` 持久化存储所选背景（`bg_index_a`/`bg_index_b`），下次启动自动读取加载。
* **陶瓷白玉质感棋子**：棋子背景使用多层 `layer-list` 渲染了球面高光和 1dp 瓷釉边缘描边：
  * **主屏 A**：冷白玉脂瓷，天青蓝字 (`#2C5E8A`)，选中时发冰蓝荧光。
  * **副屏 B**：暖白玉脂瓷，活力橙字 (`#E06D3B`)，选中时发翡翠绿荧光。
  * **敌方暗棋**：灰白玉瓷底色，无字，高雅且极具质感。
* **主题字体**：全局锁定了「站酷快乐体 (ZCOOL KuaiLe)」，因 Presentation 的独立 Window Context 默认不继承主屏 Theme，已通过棋子布局显式指定 `android:fontFamily` 及在 `TrayAdapter` 中通过代码动态应用 `ResourcesCompat.getFont` 确保字体完美一致。

---

## 🚀 7. 常用开发调试、编译与无线部署命令

在 PowerShell 环境下，我们已将 Gradle 编译与 ADB 的完整指令进行了一行合并处理（防止 adb daemon 意外退出并加速无线部署）：

### 7.1 一键无线编译安装并远程启动
```powershell
& "C:\Users\Laomo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug; adb connect 172.21.16.133:5555; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am start -n com.huanglong.pad/com.huanglong.pad.MainActivity
```

### 7.2 截取双屏实机画面并拉回本地桌面
使用 adb 截屏，其中副屏通过 `-d 2` 参数直接映射物理外接屏 Display 2，拉回至电脑桌面做视觉走查：
```powershell
adb connect 172.21.16.133:5555; adb shell screencap -p /sdcard/screenshot_main.png; adb shell screencap -d 2 -p /sdcard/screenshot_sub.png; adb pull /sdcard/screenshot_main.png C:\Users\Laomo\Desktop\screenshot_main.png; adb pull /sdcard/screenshot_sub.png C:\Users\Laomo\Desktop\screenshot_sub.png
```

### 7.3 清理项目临时文件（减小项目体积）
```powershell
& "C:\Users\Laomo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" clean
```
