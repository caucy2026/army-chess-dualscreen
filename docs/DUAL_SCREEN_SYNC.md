# 双屏状态同步实现说明（对齐 KEMI 适配指南 V1.1）

本文说明本项目如何按 `Z:\h730new\vendor\newlink\development\docs\KEMI双屏对战应用适配指南.md`（V1.1）
同步双屏应用状态，以及哪些地方有意偏离指南、为什么。

## 1. 指南条目 → 实现位置

| 指南章节 | 要求 | 本项目实现 |
| --- | --- | --- |
| §0/§1 玩家绑定物理面板 | 用 `persist.vendor.display.master` 算归属，不靠 `displayId == 0` | `dualscreen/PanelMap.kt` → `routes()` |
| §1.1 副屏取值 | 排除 `VIRTUAL`/`STATE_OFF`，不写死 `displayId == 2` | `PanelMap.visiblePanels()` |
| §2 方向 | 抵消「显示投影」= 窗口 rotation + 交换补偿 | `PanelMap.contentRotation()` |
| §2.2 施加位置 | 施加在内容根 View 上，触摸坐标无需换算 | `MainActivity.mainContentRoot`、`GamePresentation.contentRoot` |
| §4 过渡屏障 | 指纹变化 → 版本失效 → 两侧遮罩 → 丢弃旧输入 → 暂停 → 重绑 → 新帧提交后撤遮罩 | `dualscreen/DualScreenSync.kt` |
| §4 触发源 | DisplayManager 事件 + `onConfigurationChanged` + ~100ms 采样 | `DualScreenSync`（DisplayListener / 采样器 / Host 转发） |
| §4 属性读取 | 反射读，不要每次 `getprop` 子进程 | `PanelMap.readProperty()` |
| §5 遮罩准入 | 只有三种「真未知」才遮罩 | `PanelMap.maskReason()` |
| §6 退出 | 两窗口一起退；`panelsAvailable < 2` 属「等双屏恢复」不能退 | 单 task 天然原子退出（见 §3 偏离说明）；面板掉线时遮罩等待 |
| §7.2 会话唯一 | 视图不缓存对局实例 | `GamePresentation` 每次 `refreshUI()` 都从 `MainActivity` 取，不留字段 |
| §8.2 日志 | `version=… state=… routes=…` 一行 | `PanelMap.logLine()`，日志 tag `BattleDisplay` |

## 2. 状态判定规则

```text
master      = persist.vendor.display.master   (0 未交换 / 1 已交换)
screen_mode = vendor.nl.screen_mode           (仅诊断与 A 字分支，缺失不阻塞)
panels      = 非虚拟且非 STATE_OFF，按 displayId 升序

master = 0 → 玩家A = 逻辑主屏, 玩家B = 逻辑副屏
master = 1 → 玩家A = 逻辑副屏, 玩家B = 逻辑主屏
投影 = 窗口rotation×90 + (已交换且非A字 ? 180 : 0)；应用旋转 = 360 − 投影
```

遮罩只在以下情况出现，其余一律不上遮罩、不停输入：

1. `master` **存在但** 不是 `0/1`；
2. 曾经有两块面板，现在不足两块（一屏灭了 → 「等待双屏就绪」，不跟着退）；
3. 两块屏算出同一个 `displayId`。

**降级（有意为之）**：固件完全不暴露 `persist.vendor.display.master` 时，视为该机型没有交换能力，
按「始终未交换」映射，不遮罩。同理，从启动起就只有一块面板时（单屏机型 / 主屏调试）只跑玩家 A，
不遮罩。这与指南 §5 第 1 条的差别是刻意的：严格照做会让没有该属性的机型永久卡在遮罩页
（指南 §10 也记录过这个坑）。只有「曾经有两块、现在掉了一块」才遮罩等恢复。

## 3. 架构：双 Activity + 双 task

与五子棋双屏（`GomokuDualScreen`）一致的方案，弃用 `Presentation`：

| 文件 | 职责 |
| --- | --- |
| `dualscreen/BattleDisplayActivity.kt` | 基类：按映射 `bind()` 迁移窗口、施加内容旋转、中性遮罩、输入闸门；内含 `BattleDisplays` 协调器（DisplayListener + 布局变化 + 100ms 采样 + 双帧提交判定） |
| `MainActivity.kt` | 玩家 A 窗口（游戏控制中心），`battlePlayer = Player.A` |
| `GamePresentation.kt` | 玩家 B 窗口，`battlePlayer = Player.B`，独立 taskAffinity `${applicationId}.white` |
| `dualscreen/GameSession.kt` | 对局状态单例，窗口迁移后不丢局 |
| `dualscreen/ExitPolicy.kt` | 是否连对方一起退 |
| `dualscreen/PanelMap.kt` | 映射与方向换算（纯逻辑，单测覆盖） |

**为什么必须换掉 `Presentation`**：交换开启时玩家 B 的目标是逻辑主屏（display 0），而
`Presentation` 不能投默认屏。实测结果就是「另一块屏起不来」。改成 Activity 后两个窗口都能用
`launchDisplayId` 落到任意 display。

启动链：`MainActivity` 先在 display 0 起来（防呆），`BattleDisplays.update()` 算出
`routes`，由玩家 A 用 `ensureSecondScreen(routes[B])` 直接拉起玩家 B 到目标屏；
随后每个窗口在 `bind()` 里发现自己在错误的屏上，就用 `launchDisplayId` 重建自己并 `finish()`。

**迁移与防呆的冲突（实测踩过）**：`bind()` 把玩家 A 迁到 display 2 后，`onCreate` 里的
「强制回主屏」防呆又把它踢回 display 0，两边无限互相重建，日志里刷出一串 version 且
`player=A closed, finishing peer window`。修法是迁移时带上
`EXTRA_ROUTED` 标记，防呆只对用户从桌面启动的实例生效。

## 4. 验证

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
adb logcat -s BattleDisplay
adb shell getprop persist.vendor.display.master
adb shell dumpsys display | findstr mViewports   # orientation = 显示投影
```

单元测试 `app/src/test/java/com/huanglong/pad/dualscreen/PanelMapTest.kt`（22 项）覆盖：
交换/未交换映射、属性缺失与非法值不猜、副屏 displayId 不写死、四种姿态 × 交换组合、
A 字不叠加补偿、竖持回退、指纹变化、三种遮罩准入条件、日志格式。

## 5. 实机验证记录（KEMI_Vibe_Pads_S1 / hi3781v730 / Android 12）

设备读数：

```text
persist.vendor.display.master = 0        (未交换)
vendor.nl.screen_mode        = -1        (普通)
ro.config.dualscreen.keycode = 131

DisplayViewport: displayId=0 INTERNAL orientation=0  1920x1280
                 displayId=2 EXTERNAL orientation=2  1920x1280
```

应用运行日志：

```text
BattleDisplay: version=1 state=0|-1|0:2:0:内置屏幕,2:2:2:HDMI 屏幕 routes={A=0, B=2}
```

- 主窗口在 display 0，玩家 B 窗口在 display 2，与 `routes={A=0, B=2}` 一致；
- 玩家 B 内容旋转 180°（`contentRotation(2, "0", "-1") = 180`）；
- `adb shell screencap -d 2` 抓到的画面是**倒的**。按指南 §2.4，该屏 `orientation=2` 的截图是
  合成前画面，所以面板上实际是正的——判定朝向必须以实体屏为准。

### 5.1 这个固件的两个坑（都已修）

1. **`DisplayManager.displays` 看不到 HDMI 副屏。** 只有按
   `DISPLAY_CATEGORY_PRESENTATION` 类别查询才返回它。现在 `PanelMap.visiblePanels()` 两个来源
   取并集再按 displayId 去重。
2. **`Display.TYPE_VIRTUAL` 是 5，不是 2。** 2 是 `TYPE_EXTERNAL`，正是本机副屏的类型；
   早先按 2 过滤会把真正的副屏当虚拟屏丢掉，表现为「只检测到一块屏」。已改为 5。

### 5.2 交换开启（master = 1）的实测行为

设备切到互换后读数：

```text
persist.vendor.display.master = 1
vendor.nl.screen_mode        = 0
viewports: displayId=0 INTERNAL orientation=2 | displayId=2 EXTERNAL orientation=0
```

即两块屏的显示投影对调，与指南 §2.1「0°，已交换：`[0,2]` → `[2,0]`」的表一致。

此时 `routes = {A=2, B=0}`。改造前的实现（单 Activity + `Presentation`）在这里会
**一个副窗口都建不出来，只剩主屏一块画面**，即用户反馈的「另一块屏未启动成功」。

改成双 Activity 后的实测结果：

```text
BattleDisplay: version=5 state=1|0|0:2:0:内置屏幕,2:2:2:HDMI 屏幕 routes={A=2, B=0}
BattleDisplay: ready version=5 both frames committed

Window #5  com.huanglong.pad/com.huanglong.pad.MainActivity      mDisplayId=2
Window #18 com.huanglong.pad/com.huanglong.pad.GamePresentation  mDisplayId=0
```

玩家 A 的窗口迁到 display 2（= 物理面板 A），玩家 B 的窗口落到 display 0（= 物理面板 B），
两块屏都起来了、方向都正。每个玩家的内容旋转量因为窗口跟着面板走而保持恒定。

截图判定（`screencap` 抓的是投影前画面）：

- `screencap -d 0` 是倒的（该屏投影已是 2，面板上正）→ 显示玩家 B 的横幅与棋盘
- `screencap -d 2` 是正的（该屏投影已是 0）→ 显示玩家 A 的横幅与棋盘

即「玩家 A 在物理面板 A、玩家 B 在物理面板 B」在交换开启时也成立，指南 §1 的绑定语义满足。

### 5.3 触发交换的方式

`com.newlink.action.SWITCH_DISPLAY_MASTER` 是受保护广播，`adb shell`（uid 2000）发送会被
`SecurityException` 拒绝，设备也没有 root。实际是用户在系统 UI 里点「互换」触发的。
调试时如需自动化，得先解决权限（root 或系统签名），否则只能手动点。

### 5.4 「副屏空白 + 主屏一直等待双屏就绪」（已修）

现象：副屏一片空白，主屏停在深色遮罩「等待双屏就绪」，点不动。

抓到的证据与原因：

```text
Task{... com.huanglong.pad.white ... visible=false}
mLastPausedActivity: ActivityRecord{... .GamePresentation ...}     ← 副屏那个 task 被暂停了
```

三处设计问题叠在一起：

1. **两扇窗口在两个 task 里，切后台只回来一个。** 按 Home / 被别的应用盖住后，
   Android 只恢复用户点到的那一个 task（玩家 A），副屏那个 task 留在了 stopped，
   于是副屏不画、玩家 A 等第二块屏提交新帧，遮罩永远撤不掉。
   修法：`ensureSecondScreen()` 改用「是否已挂到 `BattleDisplays`」判断存活，
   发现副屏窗口没了就把它重建（先 `markReplacing` 再 `finish` 旧实例，避免被当成用户退出）。
2. **`reconcile` 会把自己误杀。** 原来「只剩一扇窗口超过 1.5s 就把剩下那扇也关掉」，
   但双屏下另一块屏的 Activity 会一直保持 RESUMED，按时间判「被系统收掉」并不成立。
   实测按 Home 后 1.4s 就把玩家 A 关了。修法：干脆去掉这条自动关闭，
   缺窗口交给 `ensureSecondScreen()` 重建，显式退出交给 `windowClosed()`。
3. **旧窗口被踢掉时会被误判成退出。** 加 `replacing` 标记，重建/迁移导致的关闭不触发对端退出。

修复后实测（`master=0`）：

```text
启动      : ready version=4 both frames committed
按 Home   : version=6（只重算，不关任何一扇）
回到前台  : version=8 → ready version=8 both frames committed
```

两扇窗口分别在 display 0 / display 2，截图都有画面，遮罩已撤。

### 5.5 「退出后副屏停在等待双屏就绪」（已修）

现象：按返回退出后，主屏回桌面，副屏却留在深色遮罩「等待双屏就绪」。

原因有三层：

1. `closeAll()` 只对两扇窗口各调了一次 `finishAffinity()`。对「另一个 task 里的窗口」
   不一定生效，实测副屏那个 `GamePresentation` 仍停在 RESUMED。
2. 退出过程中 `detach()` 还会给「剩下那扇」上遮罩，于是副屏被刷成「等待双屏就绪」。
3. 退出还没结束时 `update()` 仍可能在跑，把副屏又拉起来。

修法：加了退出状态机 `exiting`

- `closeAll()` 先置 `exiting=true`（幂等），再 `finishAffinity()` + `finish()`，
  400ms 后对仍未结束的窗口补一刀 `finishAndRemoveTask()`；
- `detach()` 在 `exiting` 时不再 `invalidate()`（不再上遮罩）；
- `update()` 在 `exiting` 时直接 return（不再重建副屏）；
- 一扇窗口被关时改为走 `closeAll()`，保证统一路径；
- `attach()` 会把 `exiting` 复位，下次启动照常。

实测：

```text
按返回 → BattleDisplay: closeAll: finishing windows=2
退出后 → pid 仍在但 com.huanglong.pad 窗口数为 0；副屏回系统桌面，无遮罩残留
```

同时：用户主动退出会重置会话（下次启动开新局），换屏迁移则保留对局。

### 5.6 副屏朝向：已人工确认 180° 正确

副屏要不要在应用里补 180°，只能在实体屏上判定。结论：**补 180° 是对的，保持按投影公式计算**
（真机肉眼确认，2026-10-20）。

容易误判的一点：系统桌面在 display 2 的 `screencap` 是正的，而补偿后本应用的
`screencap -d 2` 是倒的。按指南 §2.4，`screencap` 抓的是投影前画面，
所以「截图倒、面板正」正是补偿生效的表现，不能用截图判断朝向。

现场还想复核时，有运行时开关（已并入状态指纹，改完 1 秒内自动重绑，日志里能看到 version 递增）：

```powershell
adb shell setprop debug.acds.content_rotation 0      # 应用不做任何旋转
adb shell setprop debug.acds.content_rotation 180    # 强制 180
adb shell setprop debug.acds.content_rotation ""     # 回到按投影公式计算（默认，当前取值）
```


### 5.7 手势上划 / 切到后台：两扇一起收（已修）

现象（用户实测）：从下方手势上划退出，副屏那扇会留在前面显示「等待双屏就绪」；
或者在副屏上划之后应用像是「重启」了一下。

原因：双屏下另一块屏的 Activity 可能一直保持 RESUMED。用户把应用切到后台时只有一扇 onStop，
于是：

- 剩下一扇被 `invalidate()` 刷成「等待双屏就绪」并一直停在那儿；
- 或者 `ensureSecondScreen()` 发现副屏窗口没了，又把它拉起来 —— 看起来像应用重启。

修法：新增 `onHostStopped()` / `parkAll()`

- 任意一扇 onStop（手势上划、Home、切到别的应用）且不是迁移/退出/转屏时，把**两扇一起收掉**；
- 用单独的 `known` 集合记录「还活着但可能已经 detach」的窗口 —— `hosts` 在 onStop 时就移除，
  只收 `hosts` 会漏掉正在停下的那一扇（实测剩 1 个窗口）；
- `detach()` 不再 `invalidate()`，从根上避免「剩下那扇被刷遮罩」；
- `update()` 在 parking 期间直接 return，避免把副屏又拉起来；
- 两扇都回桌面，回到应用时按映射重新拉起，对局状态在 `GameSession` 里保留（不是新开一局）。

实测：

```text
主屏 HOME → BattleDisplay: app backgrounded, parking windows=2
副屏 HOME → BattleDisplay: app backgrounded, parking windows=2
两种情况下：com.huanglong.pad 窗口数 0，两块屏都回桌面，无遮罩残留
重新启动 → version=8 → ready version=8 both frames committed，对局保留
```

> 说明：`input swipe` 注入的边沿手势在这台机器上没能触发系统的手势导航，
> 所以是用 `input keyevent KEYCODE_HOME` 与 `input -d 2 keyevent KEYCODE_HOME`
> 分别对主屏、副屏触发同一个 `onStop` 路径来验证的。真机上请用手势再确认一次。





