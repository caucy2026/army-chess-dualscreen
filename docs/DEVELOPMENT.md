# 双屏军棋 (DoubleScreenChess) 开发与踩坑技术文档

本项目是一款基于 **KEMI_7M600_T1** 双屏 Android 平板（内置主屏 Display 0 与外接副屏 Display 2）量身定制的军棋（陆战棋）暗棋实时对战应用。

本篇文档系统总结了项目的架构设计、核心机制、开发调试中遇到的环境及逻辑踩坑记录，并附带完整的构建部署指南。

---

## 📌 架构设计与双屏通信机制

军棋的“暗棋”规则具有信息不对称性，双屏物理隔离是绝佳的硬件支撑（防偷看）。

```
        ┌───────────────────────────────────────────────────────────┐
        │                        同应用进程                         │
        │                                                           │
        │   ┌──────────────────────┐     ┌──────────────────────┐   │
        │   │     MainActivity     │     │   GamePresentation   │   │
        │   │  (主屏 - 玩家 A 视角)│     │  (副屏 - 玩家 B 视角)│   │
        │   └──────────┬───────────┘     └───────────┬──────────┘   │
        │              │                             │              │
        │              └──────────────┬──────────────┘              │
        │                             ▼                             │
        │                      Shared Memory                        │
        │              ┌─────────────────────────────┐              │
        │              │  boardState & GameEngine    │              │
        │              └─────────────────────────────┘              │
        └───────────────────────────────────────────────────────────┘
```

1. **同进程状态同步 (Shared State)**：
   由于主屏 `MainActivity` 和副屏 `GamePresentation` (继承自 `android.app.Presentation`) 运行在同一个应用进程和同一个 JVM 中，它们可以直接共享内存中的 `boardState`（棋盘数据 Map）以及游戏阶段变量。主屏通过主动调用 `gamePresentation?.refreshUI()` 确保副屏渲染百分之百秒级同步。
2. **双屏物理旋转 180 度 (Rotation 180) 与直连映射**：
   由于主副屏背靠背或对向摆放，玩家 B 看到的画面原本是颠倒的。
   * **物理旋转与同序声明（已修正，勿倒序）**：我们在副屏布局 XML 最外层 `LinearLayout` 静态声明 `android:rotation="180"` 锁定，用于校正 B 背靠背读屏的文字方向。由于"XML 布局 → 旋转 180° 入帧缓冲 → B 从背面观看再翻 180°"双重翻转相互抵消，**B 感知到的顺序就是 XML 声明顺序**。因此副屏控制面板必须与主屏 `activity_main.xml` 完全同序声明（标题在上、按钮居中、托盘在最下方贴近手边），两屏风格才完全一致。早期版本误用"完全反序排布"导致 B 看到标题在底部，已修正。
   * **触控自动映射与直连**：旋转后 Android 框架会自动将 `MotionEvent` 触控坐标旋转 180 度。因此，我们彻底免除了在代码里做 `11 - r` 和 `4 - c` 的双重逻辑翻转，所有网格物理上直接与绝对逻辑坐标 `Position(r, c)` 直连映射，配合 `perspective = Player.A` 的自定义棋盘背景，大幅简化了算法复杂度并确保了玩家 B 视角下己方布兵与大本营正好在屏幕下方。

---

## 🛠️ 踩坑记录与保姆级解决方案 (Troubleshooting)

### 1. 编译环境踩坑：系统全局 JDK 24 导致 Gradle 闪退
* **问题描述**：本地安装了 Java 24（`24.0.1`），在运行 Gradle 时抛出 `Semantic analysis BUG! Unsupported class file major version 68`。
* **原因分析**：Java 24 对应的 Class 字节码主版本号是 68，而项目所用的 Gradle 8.13 内部的编译插件（如较老版本的 Kotlin 插件）不支持解析主版本 68 的类文件。
* **解决方案**：在项目中创建 [gradle.properties](file:///E:/pad/gradle.properties)，配置 `org.gradle.java.home`，强行指向 PyCharm 2024.1 中自带的 **OpenJDK 17** 运行环境（`C:\Program Files\JetBrains\PyCharm 2024.1\jbr`），完美解决编译闪退。

### 2. 编译环境踩坑：JBR 缺少 `jlink` 导致 Java 编译失败
* **问题描述**：Java 编译 Task 抛出 `Execution failed for JdkImageTransform: ... jlink executable ... jlink.exe does not exist`。
* **原因分析**：PyCharm 自带的 `jbr` 只是运行时的 JRE，不是完整的 JDK，其没有包含 Java 9+ 模块打包所需的 `jlink` 工具。
* **解决方案**：在 [app/build.gradle](file:///E:/pad/app/build.gradle) 中，将 `compileOptions` 和 `kotlinOptions` 的编译目标与兼容性从 Java 17 降级为 **Java 1.8**。由于 Java 8 不包含模块系统，Gradle 编译时会自动跳过 JdkImage 转化和 `jlink` 的调用，从而成功规避此报错（项目无 Java 9+ 特异性 API，不影响运行）。

### 3. XML 资源踩坑：LinearLayout 不支持 gap 属性 & 错误的大小写 GONE
* **问题描述**：资源链接失败，提示 `attribute android:gap not found` 以及 `GONE is incompatible`。
* **原因分析**：
  * Android 原生的 `LinearLayout` 与 Web CSS Flexbox 不同，不支持 `android:gap` 属性（需用 margin 或 Space 占位）。
  * XML 中的可见性属性值只识别小写的 `gone`，大写的 `GONE` 属于非法属性值。
* **解决方案**：移除了不合法的 `android:gap`，并将 `view_board_cell.xml` 中的所有大写 `GONE` 纠正为小写 `gone`。

### 4. 逻辑踩坑：动态生成网格没有执行 addView
* **问题描述**：游戏启动后棋盘一片空白，点击没有任何响应，一键布防也无效果。
* **原因分析**：在 `MainActivity` 和 `GamePresentation` 中，通过 `layoutInflater` 循环实例化 60 个 `cellView` 并存入 Map 后，**漏掉了将其添加到 UI 布局中的核心命令** `grid.addView(cellView)`。
* **解决方案**：在循环末尾加上 `grid.addView(cellView)`，透明的 12x5 交互网格成功挂载，完全打通了棋盘事件分发与刷新通路。

### 5. 逻辑踩坑：副屏一键布阵后点击“完成布阵”提示阵型不合法
* **问题描述**：副屏一键布防在数据上虽然放满，但点击就绪按钮时，弹窗提示阵型非法。
* **原因分析**：[GameEngine.kt](file:///E:/pad/app/src/main/java/com/huanglong/pad/game/GameEngine.kt) 的 `verifyLayout` 布防验证函数中，之前只针对玩家 A 的逻辑坐标（Row 6-11）进行了校验，没有做 B 视角的分区校验。导致 B 的棋子在逻辑 Row 0-5 判定时全被扣为“非法位置”。
* **解决方案**：重构 `verifyLayout`，通过 `player` 参数动态分支校验：
  * 玩家 A 校验下半区 `Row 6-11`，地雷在最后两排 `Row 10-11`，军旗在大本营 `Position(11,1)/(11,3)`，炸弹非前排。
  * 玩家 B 校验上半区 `Row 0-5`，地雷在最前两排 `Row 0-1`，军旗在大本营 `Position(0,1)/(0,3)`，炸弹非前排。

### 6. 逻辑与系统踩坑：Android 11+ 包可见性限制导致 TextToSpeech 语音合成引擎初始化失败
* **问题描述**：对决裁决文字语音播报在 Android 11+ 上直接报错初始化失败（`TTS Initialization failed!`），没有任何发声。
* **原因分析**：本项目的 `targetSdkVersion` 设置为 33。自 Android 11（API 30）起，系统引入了包可见性（Package Visibility）限制。如果应用在 `AndroidManifest.xml` 中未显式使用 `<queries>` 标签声明 `android.intent.action.TTS_SERVICE`，系统将阻止应用绑定到底层系统 TTS 服务。
* **解决方案**：在 [AndroidManifest.xml](file:///E:/pad/app/src/main/AndroidManifest.xml) 的 `<manifest>` 根标签内添加 `<queries>` 声明，以允许应用绑定系统语音引擎服务。

### 7. 逻辑与系统踩坑：精简版平板系统缺少 TTS 语音引擎包，且原生 TTS 合成延迟大、声音机械生硬
* **问题描述**：添加包可见性后部分系统（因厂家精简）仍无法播报，日志显式初始化依然失败（`settings get secure tts_default_synth` 返回 null），这表明底盘完全无 TTS 服务。此外，动态合成文字语音具有约 1 秒左右的明显延迟，碰子对决时有明显的音画不同步和卡顿感。
* **解决方案**：放弃了实时 TTS 语音合成，转而采用**“本地预置搞笑音频 + SoundPool 内存预缓存混音播放”**的高响应方案。通过公开接口，将 4 种搞笑文案预生成高保真 MP3 文件存入项目的 [res/raw 资源目录](file:///E:/pad/app/src/main/res/raw) 下。在 `SoundPool` 中提升并发通道数为 8 并加入内存预加载，在碰子瞬间实现零延迟、高保真的对决结果播报，完美摆脱了对系统 TTS 引擎服务的依赖。

---

## 🚀 常用调试构建与实机测试命令

由于本地 Gradle 没有配入环境变量，在 PowerShell 终端下，所有 Gradle 编译和 ADB 命令建议**组合在单行合并运行**以保持 Daemon 和 ADB 的连贯性：

### 1. 编译并无线部署至平板并远程启动
```powershell
& "C:\Users\Laomo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug; adb connect 172.21.16.133:5555; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am start -n com.huanglong.pad/com.huanglong.pad.MainActivity
```

### 2. 截取双屏实机运行画面并拉回到电脑桌面
```powershell
adb connect 172.21.16.133:5555; adb shell screencap -p /sdcard/screenshot_main.png; adb shell screencap -d 2 -p /sdcard/screenshot_sub.png; adb pull /sdcard/screenshot_main.png C:\Users\Laomo\Desktop\screenshot_main.png; adb pull /sdcard/screenshot_sub.png C:\Users\Laomo\Desktop\screenshot_sub.png
```
*(注：副屏截图使用 `-d 2` 参数直接映射物理外接屏 Display 2)*

### 8. 走棋动画与残影高亮避坑：多屏状态同步与并发防触
* **问题**：走棋时瞬间切换，对手不知道是哪个子移动的；如果通过弹出对决框停留来解决，居中的 Dialog 会遮挡棋盘；如果取消 Dialog 自动隐藏，又会引起交互阻塞。
* **原因**：暗棋规则下，非交战的走棋必须对敌方保密身份。纯靠弹窗不足以解决“从哪里走到哪里”的轨迹认知问题。
* **解决**：
  1. **轨迹动画**：在 MainActivity 和 GamePresentation 中实现了严格同步的 400ms 平滑位移动画（基于 ViewPropertyAnimator）。
  2. **状态防触锁**：由于动画耗时 400ms，必须全局加入 isAnimating 变量，在半空中强制忽略一切 onCellClicked 触摸，防止玩家狂点导致底层 oardState 与 UI 位置脱节。
  3. **背景残影**：在 iew_board_cell.xml 底层添加了 #v_last_move_highlight (红框绿底)，配合 lastMoveFrom/lastMoveTo 全局状态。每次 UI 刷新时永久点亮上一回合的起止两个格子，不改变底层暗棋规则，也不遮挡弹窗，完美复刻国际象棋的“最后一步高亮”标准做法。
  4. **BOM 编码阻断**：在 Windows PowerShell 中动态生成 XML 布局时，若使用默认的 [System.Text.Encoding]::UTF8 会带入 BOM (Byte Order Mark) 头，直接导致 Android 资源编译报 Cannot read field "elmName" because "root" is null 致命崩溃。必须强制使用 New-Object System.Text.UTF8Encoding $false 创建无 BOM 的 UTF-8 编码对象写入。