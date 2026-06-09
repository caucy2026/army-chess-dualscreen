# 双屏开发设备配置信息 (KEMI_7M600_T1)

本项目用于开发双屏 Android 平板应用。以下是通过 ADB 收集到的该设备的详细硬件配置、系统设置 and 显示参数。

## 📌 设备概述
* **品牌 (Brand)**: huanglong
* **型号 (Model)**: huanglong (对应市场型号：`KEMI_7M600_T1`)
* **芯片型号 (Chipset)**: 海思 Hi3781V730 (`Hi3781v730_tablet`)
* **操作系统 (OS)**: Android 12 (API 31)
* **固件类型**: `eng` 开发版 (已默认 Root，开启 ADB 权限)

---

## 🖥️ 双显示屏配置 (核心)
设备搭载了两个物理分辨率完全相同的显示屏，在 Android 系统的显示映射如下：

| 显示屏属性 | 🖥️ 内置主屏 (Display 0) | 🔌 外部副屏 (Display 2) |
| :--- | :--- | :--- |
| **设备名称** | 内置屏幕 (`local:0`) | HDMI 屏幕 (`local:1`) |
| **物理分辨率** | 1920 × 1280 (3:2) | 1920 × 1280 (3:2) |
| **屏幕类型 (Type)** | `INTERNAL` (内置) | `EXTERNAL` (外部) |
| **触摸支持 (Touch)** | `INTERNAL` (内置触控) | `EXTERNAL` (外接/独立触控) |
| **默认刷新率** | 60 Hz (支持最高 120 Hz) | 60 Hz |
| **像素密度 (Density)**| 320 dpi (`ro.sf.lcd_density`) | 213 dpi / 320 dpi (逻辑映射) |
| **默认方向 (Orientation)** | Landscape (横屏) | Seascape (逆向横屏) |
| **系统标志 (Flags)** | `FLAG_DEFAULT_DISPLAY`, `FLAG_SECURE` | `FLAG_PRESENTATION`, `FLAG_SECURE` |

> [!IMPORTANT]
> **开发注意事项**：
> 1. 副屏 (Display 2) 带有 `FLAG_PRESENTATION` 标志。在开发双屏应用时，副屏内容通常需通过 `android.app.Presentation` 类或 `DisplayManager` 动态获取并投放。
> 2. 副屏的默认旋转方向为 `seascape` (逆向横屏)，这可能与主屏的 `landscape` (普通横屏) 存在 180 度反向。在处理跨屏坐标映射或相机方向时需要格外小心。
> 3. 设备定义了双屏切换专用物理按键码：`ro.config.dualscreen.keycode = 131`。

---

## ⚙️ 核心硬件参数

### 1. CPU & GPU
* **架构**: ARM64 (`arm64-v8a`)
* **核心数**: 8 核 (8 × Cortex-A73 架构变体)
* **GPU 渲染**: ARM Mali (`ro.hardware.vulkan = mali`)，支持 Vulkan

### 2. 内存 & 存储
* **总内存 (RAM)**: 6 GB (`5745252 kB`)
* **交换分区 (Swap)**: 2.5 GB (`2621436 kB`)，系统已开启 ZRAM 优化

### 3. 连接性 (Connectivity)
* **Wi-Fi 驱动**: AIC8800 (`vendor_im.wlan.driver = AIC8800`)
* **蓝牙芯片**: rtkusb_bt (`rtkusb_bt`)
* **移动网络**: 无 (`ro.radio.noril = true`)，仅支持无线局域网连接

---

## 🛠️ 调试与常用系统属性
在开发和调试双屏应用时，可能需要用到以下配置项：

* **获取当前显示设备拓扑**：
  ```bash
  adb shell dumpsys display
  ```
* **双屏属性控制**：
  * 主屏方向设置: `persist.vendor.prop.screenorientation = landscape`
  * 副屏方向设置: `persist.vendor.prop.screenorientation.ext = seascape`
  * 双屏数量确认: `vendor.display.count = 2`
  * 触摸配置映射自定义: `persist.sys_im.input.custom_settings`
