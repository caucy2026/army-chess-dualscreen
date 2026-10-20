# 更新记录

## v1.2.0（versionCode 3）

对齐《KEMI 双屏对战应用适配指南 V1.1》，并修掉实机上暴露的三类双屏状态问题。

### 架构：`Presentation` → 双 Activity

- 玩家 A（`MainActivity`）与玩家 B（`GamePresentation`）都改为真正的 Activity，
  各自独立 task（B 的 `taskAffinity` 为 `${applicationId}.white`），用 `launchDisplayId` 绑定目标屏。
- 原因：交换开启（`persist.vendor.display.master = 1`）时玩家 B 的目标是逻辑主屏（display 0），
  而 `Presentation` 不能投到 `DEFAULT_DISPLAY`，实测表现是「另一块屏起不来」。
- 新增 `dualscreen/BattleDisplayActivity.kt`（窗口基类 + `BattleDisplays` 协调器）、
  `GameSession.kt`（对局会话单例）、`PanelMap.kt`（映射与方向）、`ExitPolicy.kt`（退出策略）。

### 状态同步

- 玩家 → 屏映射由 `persist.vendor.display.master` 算出，不写死 `displayId == 0/2`；
  副屏取「排除虚拟屏与 `STATE_OFF` 后按 displayId 升序的第一块非主屏」。
- 方向按「显示投影」换算：`投影 = 窗口rotation×90 + (已交换且非A字 ? 180 : 0)`，`应用旋转 = 360 − 投影`，
  施加在内容根 View 上（触摸坐标由 ViewGroup 自动逆变换，无需换算）。
- 过渡屏障：状态指纹 = `master | screen_mode | 各屏 id:rotation:name`，
  变化时 版本失效 → 两侧遮罩 → 丢弃旧输入 → 按新映射重绑 → 两侧提交新帧后才撤遮罩。
  触发源合并 DisplayListener、`onConfigurationChanged`、布局尺寸变化与 100 ms 只读属性采样。
- 对局状态放进 `GameSession` 单例：换屏迁移会重建 Activity，状态不再随窗口丢失。
- 只有三种「真未知」才上遮罩：`master` 存在但非法、曾经有两块面板而现在不足、两块屏算出同一 `displayId`。
  「固件不暴露 `master`」与「从启动起就只有一块屏」按降级处理，不遮罩。

### 修复

- **交换开启时副屏无法启动**：改双 Activity 后玩家 A 迁到 display 2、玩家 B 落到 display 0，两块屏都能起。
- **防呆与迁移互相拉扯**：玩家 A 被迁到 display 2 后，`onCreate` 里的「强制回主屏」又把它踢回 display 0，
  导致无限互相重建。迁移实例带 `EXTRA_ROUTED` 标记，防呆只对桌面启动的实例生效。
- **切后台/手势上划后副屏停在「等待双屏就绪」**：双屏下另一块屏的 Activity 会保持 RESUMED，
  只 onStop 一扇时剩下那扇被刷成遮罩，或被 `ensureSecondScreen` 又拉起来（看起来像重启）。
  现在任意一扇 onStop 都把两扇一起收掉（`parkAll`），回桌面；再进应用按映射重新拉起，对局保留。
- **退出后副屏残留遮罩**：`finishAffinity` 对另一个 task 里的窗口不一定生效，副屏仍停在 RESUMED。
  新增退出状态机：退出中不再上遮罩、不再重建对端，并对未结束的窗口补 `finishAndRemoveTask()`。

### 固件适配记录

- 本机 `DisplayManager.displays` 拿不到 HDMI 副屏，只有 `DISPLAY_CATEGORY_PRESENTATION` 类别查询才可见，
  现在两个来源取并集再按 displayId 去重。
- `Display.TYPE_VIRTUAL` 为 5；写成 2（`TYPE_EXTERNAL`）会把真正的 HDMI 副屏当虚拟屏过滤掉。

### 调试

- `adb shell setprop debug.acds.content_rotation 0|180|""` 可现场切换内容旋转（并入状态指纹，1 秒内生效），
  用于核对副屏实体屏朝向。
- 状态日志 tag 为 `BattleDisplay`：`version=… state=… routes=…`。

### 其他

- 新增 `scripts/build_store_release.ps1`：构建 → 校验签名指纹/包名/版本 → 产出商城上传用
  `luzhanqi-<versionName>-<versionCode>-store.apk` 与同名 `.sha256`（产物在 `build/publish/`）。
- 新增 `docs/DUAL_SCREEN_SYNC.md`（指南条目与实现对照、状态判定规则、实机验证记录与坑位）。
- 单元测试新增 `PanelMapTest`（22 项），覆盖交换/未交换映射、属性缺失与非法值不猜、
  副屏 displayId 不写死、四种姿态 × 交换组合、A 字不叠加补偿、竖持回退、指纹变化、遮罩准入与日志格式。
