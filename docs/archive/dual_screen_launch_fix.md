# 双屏应用启动防呆指南：强制主屏启动修复方案

在开发双屏应用（如 Android 上的折叠屏或双屏平板设备）时，我们经常会遇到由于用户操作导致应用显示错乱的问题。其中一个典型且致命的 Bug 是：**用户在副屏点击应用图标启动应用，导致主屏幕空白，而所有内容都挤在副屏显示。**

本文档详细记录了我们在军棋项目中如何排查并彻底解决这一问题的方法，可用于指导其他双屏项目修复同类 Bug。

## 🐛 问题现象 (Bug Description)

1. 在具有双物理屏幕的 Android 设备上（主屏 Display 0，副屏 Display 2 等）。
2. 用户在**副屏的桌面**上点击应用图标启动本应用。
3. **预期表现**：应用的主界面（MainActivity）应该始终在主屏显示，而副屏仅用于投射游戏副屏画面（Presentation）。
4. **实际错误表现**：主屏完全空白（或者显示桌面），应用的主界面跑到了副屏上。随后代码逻辑触发 Presentation 也投向副屏。由于二者都在副屏，导致界面相互遮挡重叠，或者一方覆盖另一方，游戏完全无法正常进行。

## 🔍 问题原因 (Root Cause)

Android 系统的默认行为是：**应用从哪个屏幕启动，它的默认 Activity 就会被分配到哪个屏幕的 Display 栈中。** 

因此，当用户从副屏桌面点击应用时：
1. `MainActivity` 会被系统分配并在副屏（Display ID != 0）上创建。
2. 随后代码执行双屏投递逻辑（如 `Presentation`），通常我们会将其投递到检测到的辅助显示器上（还是副屏）。
3. 结果导致 `MainActivity` 和 `Presentation` 两者都被挤在副屏，主屏则处于无应用驻留状态（空白）。

## 🛠️ 解决方案 (Solution)

**核心思路：强制将 MainActivity 迁回主屏幕。**

在 `MainActivity` 极早期（`onCreate` 开始时，甚至在 `setContentView` 之前），我们主动检测当前 Activity 所处的显示器 ID。如果发现它不在主屏（`Display.DEFAULT_DISPLAY` 通常是 `0`），我们就立即销毁当前实例，并使用 `ActivityOptions.launchDisplayId` 指定目标屏幕为 `0`，携带清空任务栈的 Flag，重新启动该 Activity。

### 核心代码实现

在 `MainActivity.kt` 的 `onCreate` 方法中最开始的位置插入以下代码：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 【核心修复逻辑】双屏防呆：强制迁回主屏
    @Suppress("DEPRECATION")
    val launchedDisplayId = windowManager.defaultDisplay.displayId
    // 如果当前不是默认显示器（主屏通常为 DEFAULT_DISPLAY = 0）
    if (launchedDisplayId != Display.DEFAULT_DISPLAY) {
        // 创建指定屏幕启动的参数
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = Display.DEFAULT_DISPLAY
        
        // 重新启动 MainActivity，并清空当前错误的任务栈
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            options.toBundle()
        )
        // 销毁当前运行在错误屏幕的实例
        finish()
        
        // 【关键】直接 return，避免继续执行后面的逻辑，特别是跳过 lateinit 变量的初始化和生命周期
        return
    }

    // --- 下面是正常的主屏逻辑 ---
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    // ... 其他初始化逻辑
}
```

## ⚠️ 注意事项与避坑指南 (Gotchas)

1. **拦截时机必须足够早**：必须在 `setContentView` 之前检测和拦截。因为如果在错误屏幕上加载了布局甚至触发了某些业务代码，再重启会导致屏幕有闪烁感，或者遇到 `lateinit` 变量未初始化的问题。
2. **务必 `return`**：调用 `finish()` 并不会立刻终止后面的代码执行。必须紧接着显式 `return`，以防止后续诸如 View Binding 绑定、资源初始化等逻辑继续报错。
3. **FLAG 的重要性**：重启时必须加上 `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK`。如果不加清空栈的标志，可能会导致回退栈混乱，按返回键时退回死循环。
4. **如何本地验证**：在开发过程中如果需要模拟从副屏启动，可以通过 adb 命令指定 display id：
   ```bash
   # 假设副屏的 display id 是 2，项目包名和 Activity 名替换成你的
   adb shell am start --display 2 -n com.your.package/.MainActivity
   ```
   执行后，可以通过 `dumpsys activity activities` 观察任务栈，确认应用是否成功落回了 `displayId=0` 的栈中。

---
希望这份指南能够帮助后续开发其他双屏 Agent 的同学少走弯路！
