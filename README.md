# 双屏陆战棋

面向 Android 双屏设备的本地双人陆战棋应用。主屏由 `MainActivity` 服务玩家 A，副屏通过 Android `Presentation` API 提供玩家 B 的独立翻转视角；棋局状态由主屏统一维护并同步刷新两块屏幕。

## 功能

- 双屏背靠背布阵、暗棋对弈与双边确认重开
- 公路、铁路、工兵拐弯、行营保护、夺旗和无棋可走判负
- 司令阵亡亮旗，以及碰雷同归于尽、大本营棋子可移出等项目房规
- 预设/随机/增量一键布阵，带合法性、战术约束和困死检测
- 9 套独立背景与 4 种方言裁决语音
- 启动时检查 KEMI 商城更新，优先跳转商城，商城不可用时校验并安装 APK

## 构建与检查

Windows 环境直接使用仓库自带的 Gradle Wrapper：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

正式发布前，将本地签名材料放入 `signing/`，然后执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

已签名 Release APK 输出到 `app/build/outputs/apk/release/app-release.apk`。每次发布必须递增
`app/build.gradle` 中的 `versionCode`，并始终使用同一份签名材料；`signing/`、密钥和属性文件
已被 Git 忽略，禁止提交到仓库。

## 代码结构

- `app/src/main/java/com/huanglong/pad/MainActivity.kt`：棋局状态、主屏交互、音效与双屏协调
- `app/src/main/java/com/huanglong/pad/AppUpdateManager.kt`：KEMI 更新检查、商城跳转、下载校验与安装
- `app/src/main/java/com/huanglong/pad/GamePresentation.kt`：副屏展示与玩家 B 交互
- `app/src/main/java/com/huanglong/pad/game/GameEngine.kt`：纯 Kotlin 规则引擎
- `app/src/test/`：规则引擎 JVM 回归测试
- `docs/`：双屏开发、部署与维护说明
- `scripts/`：背景和方言语音资源生成脚本

更完整的架构、房规和设备说明见 `CLAUDE.md` 与 `docs/`。
