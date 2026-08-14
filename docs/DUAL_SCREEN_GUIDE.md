# Android 双屏 (Presentation) 应用开发指南与踩坑实录

本指南基于 **huanglong (KEMI_7M600_T1)** 双物理屏 Android 平板的实战开发经验提炼而成。旨在为后续在此设备上开发其他双屏交互应用（如双屏棋牌、双屏收银、双屏信息展示等）提供底层的架构设计、关键避坑方案及核心脚手架代码。

---

## 🖥️ 1. 设备物理拓扑与环境参数

在进行双屏开发前，必须清楚物理屏幕在 Android 系统中的拓扑关系：

| 显示屏属性 | 🖥️ 主显示屏 (Display 0) | 🔌 辅助/外部显示屏 (Display 2) |
| :--- | :--- | :--- |
| **物理类型** | 内置屏幕 (`INTERNAL`) | 外接 HDMI 屏幕 (`EXTERNAL`) |
| **分辨率** | 1920 × 1280 (3:2) | 1920 × 1280 (3:2) |
| **像素密度** | 320 dpi | 213 dpi / 320 dpi (逻辑映射) |
| **物理朝向** | 横屏 (`landscape`) | 逆向横屏 (`seascape`，旋转 180°) |
| **触控支持** | 独立内置触控 | 独立外接/背靠背触控 |
| **系统标志** | `FLAG_DEFAULT_DISPLAY` | `FLAG_PRESENTATION` |

> [!IMPORTANT]
> **设备专有物理按键**：此设备定义了双屏切换专用物理按键，系统层对应的 KeyCode 为 `131`（`ro.config.dualscreen.keycode = 131`），可在 `onKeyDown` 中拦截以实现特定的双屏切屏逻辑。

---

## 🏗️ 2. 双屏核心架构模式：单源状态，双向映射

对于双屏应用，强烈推荐采用 **“单数据源管理 + 独立 UI 渲染”** 的模式：

```
+-------------------------------------------------------+
|                     MainActivity                      |
|  - Holds all Game State / Business Logic              |
|  - Handles SoundPool audio playback                  |
|  - Controls Sub-screen presentation lifecycle         |
+---------------------------+---------------------------+
                            |
                 (1) State Change Trigger
                            |
                            v
+-------------------------------------------------------+
|                 Sub-Screen Presentation                |
|  - Only acts as a visual projection (B's view)       |
|  - Has NO independent business state                 |
|  - Forwards touch events to Main via Callback         |
+-------------------------------------------------------+
```

* **主控制端 (MainActivity)**：负责维护所有的游戏状态/业务逻辑、处理主屏交互、控制音效播放、以及管理 Presentation 实例的生命周期。
* **副屏端 (Presentation)**：仅作为主屏的“镜像投影”或“独立视角渲染器”。它不应该包含独立的业务状态。一旦用户在副屏产生交互，通过回调将事件发送回 MainActivity 统一处理，再由 MainActivity 更新数据源并同步给主副屏。
* **优势**：避免双端数据不同步，极大降低状态机复杂度，解决因生命周期异步导致的多线程竞争问题。

---

## ⚡ 3. 核心踩坑点与硬核避坑方案

### 坑 1：副屏桌面启动导致主屏空白，内容全挤在副屏
* **起因**：Android 系统的默认机制是“从哪个屏幕的桌面点击应用，Activity 就会默认在哪个屏幕启动”。如果用户在副屏点击图标，`MainActivity` 会在副屏（Display 2）创建，此时再往副屏投射 `Presentation`，二者会重叠覆盖，而主屏完全空白。
* **解决**：在 `MainActivity.onCreate()` 的极早期（**必须在 `setContentView` 之前**），检测当前所处的 `displayId`。如果不是主屏（`Display.DEFAULT_DISPLAY = 0`），立即通过 `ActivityOptions.launchDisplayId` 指定主屏重新启动，并清空当前任务栈，随后 `finish()` 当前实例并直接 `return`。

### 坑 2：副屏物理旋转 180° 带来的触控坐标与布局对齐陷阱
* **起因**：由于双屏多为“背靠背”放置，为了让副屏玩家看正画面，副屏布局通常会在最外层声明 `android:rotation="180"`。
* **注意**：
  1. **布局声明同序**：由于“物理旋转 180°”与“布局旋转 180°”互相抵消，副屏上控件的逻辑顺序和主屏是一致的（即在 XML 中顶部的控件在物理屏幕上也是顶部）。因此，副屏的控制托盘、按钮等在 XML 里的书写顺序应与主屏保持同序，不要反向声明。
  2. **触控坐标直连**：在该设备上，Android 触控驱动在物理旋转 180° 后，会自动对点击坐标做 180° 翻转映射。因此，在代码中**不需要**在 `onTouch` 里手动对 `MotionEvent` 的 `x` 和 `y` 进行矩阵旋转或行列反转，直接取值即对应副屏的逻辑行列。

### 坑 3：Presentation 的 Window Context 导致全局字体和主题失效
* **起因**：Android 的 `Presentation` 继承自 `Dialog`，其使用的 Context 是外部显示器特有的 Window Context，**不会继承**主屏幕在 `themes.xml` 中配置的全局字体（如「站酷快乐体」）。
* **解决**：
  1. **布局文件锁定**：在 Presentation 关联的 layout XML 中，所有 `TextView` 必须显式声明 `android:fontFamily="@font/your_font"`。
  2. **动态代码注入**：在 Presentation 的 Adapter（如托盘列表适配器）中，无法通过 Theme 继承字体。必须在 `onCreateViewHolder` 或 `getView` 中，通过 `ResourcesCompat.getFont(context, R.font.your_font)` 动态加载并设置到文本控件上：
     ```kotlin
     val typeface = ResourcesCompat.getFont(context, R.font.zcool_kuaile)
     textView.typeface = typeface
     ```

---

## 💾 4. 核心脚手架代码 (Boilerplate)

### 4.1 主 Activity 防呆与 Presentation 生命周期管理

```kotlin
class MainActivity : AppCompatActivity() {

    private var presentation: MyPresentation? = null
    private lateinit var displayManager: DisplayManager

    // 监听显示设备插拔/变化
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { updatePresentation() }
        override fun onDisplayRemoved(displayId: Int) { updatePresentation() }
        override fun onDisplayChanged(displayId: Int) { updatePresentation() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【极重要】防呆：强制主屏启动
        @Suppress("DEPRECATION")
        val currentDisplayId = windowManager.defaultDisplay.displayId
        if (currentDisplayId != Display.DEFAULT_DISPLAY) {
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = Display.DEFAULT_DISPLAY
            
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                },
                options.toBundle()
            )
            finish()
            return // 必须 return，拦截后续所有逻辑
        }

        // 初始化主屏界面
        setContentView(R.layout.activity_main)
        
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        
        updatePresentation()
    }

    private fun updatePresentation() {
        // 寻找支持 FLAG_PRESENTATION 的副屏幕
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        
        if (displays.isNotEmpty()) {
            val targetDisplay = displays[0] // 默认取第一个副屏
            
            // 如果已经有 presentation 且对应的屏幕没有变，则只需刷新
            if (presentation != null && presentation?.display == targetDisplay) {
                presentation?.refreshUI()
            } else {
                // 清理旧的 Presentation
                dismissPresentation()
                
                // 创建并投射新的 Presentation
                presentation = MyPresentation(this, targetDisplay).apply {
                    // 设置交互回调
                    setOnInteractiveListener { action ->
                        handleSubScreenAction(action)
                    }
                    show()
                }
            }
        } else {
            dismissPresentation()
        }
    }

    private fun dismissPresentation() {
        presentation?.let {
            if (it.isShowing) {
                try {
                    it.dismiss()
                } catch (e: Exception) {
                    Log.e("DualScreen", "Dismiss presentation failed", e)
                }
            }
        }
        presentation = null
    }

    private fun handleSubScreenAction(action: String) {
        // 统一在主屏处理副屏回传的动作，修改状态后同步刷新主副屏
        updateStateAndRefreshUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissPresentation()
        displayManager.unregisterDisplayListener(displayListener)
    }
}
```

### 4.2 Presentation 基础实现模板

```kotlin
class MyPresentation(
    outerContext: Context, 
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var btnAction: Button
    private var actionListener: ((String) -> Unit)? = null

    fun setOnInteractiveListener(listener: (String) -> Unit) {
        this.actionListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 隐藏副屏的状态栏/导航栏，实现沉浸式全屏
        window?.let { win ->
            win.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        setContentView(R.layout.presentation_layout)
        
        btnAction = findViewById(R.id.btn_action)
        
        // 动态将主题字体赋给 Presentation 里的控件
        val customFont = ResourcesCompat.getFont(context, R.font.zcool_kuaile)
        btnAction.typeface = customFont
        
        btnAction.setOnClickListener {
            actionListener?.invoke("CLICK_ACTION")
        }
    }

    fun refreshUI() {
        // 根据主屏同步过来的全局数据刷新本界面
    }
}
```

---

## 🛠️ 5. 双屏专属调试命令集

在 Windows/PowerShell 命令行下，请常备以下 ADB 指令以提升双屏开发效率：

### 1. 监测设备双屏拓扑结构
当副屏未正常点亮或不清楚其 `displayId` 时：
```powershell
adb shell dumpsys display | findstr "mDisplayId= mUniqueId="
```
*通常主屏的 id 是 `0`，外接 HDMI 屏是 `2`。*

### 2. 模拟从副屏启动应用（稳定性测试）
用于测试前述“防呆迁回主屏”逻辑是否稳健：
```powershell
# 假设副屏的 displayId 是 2，替换包名和入口 Activity
adb shell am start --display 2 -n com.huanglong.pad/com.huanglong.pad.MainActivity
```

### 3. 双端屏幕独立截屏（UI 走查）
双屏应用由于分属不同 Display，普通的 `screencap` 只能截取主屏。截取副屏需要显式指定 `-d` 参数：
```powershell
# 截取主屏并保存到桌面
adb shell screencap -p /sdcard/screenshot_main.png
adb pull /sdcard/screenshot_main.png C:\Users\Laomo\Desktop\screenshot_main.png

# 截取副屏（Display ID = 2）并保存到桌面
adb shell screencap -d 2 -p /sdcard/screenshot_sub.png
adb pull /sdcard/screenshot_sub.png C:\Users\Laomo\Desktop\screenshot_sub.png
```

### 4. 实时查看 Activity 所在的 Display 栈
当发现界面渲染混乱、点击失效时，查看 Activity 到底处于哪个显示栈中：
```powershell
adb shell dumpsys activity activities | findstr "Display"
```
