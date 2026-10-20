package com.huanglong.pad.dualscreen

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.huanglong.pad.MainActivity
import com.huanglong.pad.game.Player
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一个玩家窗口只负责一个玩家，落到哪块屏由固件状态算出（[PanelMap]），不做现场标定。
 *
 * 关键：两个玩家都是真正的 Activity（不再用 `Presentation`），只有这样才能用
 * `launchDisplayId` 把窗口迁到任意 display —— 包括交换开启时玩家 A 要去的 display 2
 * 和玩家 B 要去的 display 0。`Presentation` 投不到 display 0，那条路走不通。
 *
 * 因为窗口跟着物理面板走，每个玩家的内容旋转量在交换前后保持不变。
 */
abstract class BattleDisplayActivity : AppCompatActivity() {

    /** 本窗口固定负责的玩家。 */
    abstract val battlePlayer: Player

    private var cover: TextView? = null
    private var applied = ""
    private var moving = false
    private var gestureVersion = -1L
    private var lastSize = ""

    private val layoutListener =
        View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (oldR > oldL && oldB > oldT && (r - l != oldR - oldL || b - t != oldB - oldT)) {
                BattleDisplays.configurationChanged()
            }
        }

    /** 面板切换/遮罩出现时丢弃进行中的输入，由子类清理高亮、预览等。 */
    open fun cancelPendingInput() {}

    override fun onStart() {
        super.onStart()
        findViewById<View>(android.R.id.content)?.addOnLayoutChangeListener(layoutListener)
        BattleDisplays.attach(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        BattleDisplays.configurationChanged()
    }

    override fun onStop() {
        findViewById<View>(android.R.id.content)?.removeOnLayoutChangeListener(layoutListener)
        BattleDisplays.detach(this)
        // 手势上划 / 切到别的应用：把两扇窗口一起收掉。
        // 否则另一块屏会留在「等待双屏就绪」，或者被 ensureSecondScreen 又拉起来（看起来像应用重启）。
        BattleDisplays.onHostStopped(this, moving, isChangingConfigurations)
        super.onStop()
    }

    override fun onBackPressed() {
        // 侧滑/返回是整机退出：两侧窗口属于两个 task，只 finish 自己会留下另一屏空等。
        BattleDisplays.closeAll()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (isFinishing) BattleDisplays.windowClosed(this, moving)
        super.onDestroy()
    }

    /** 中性遮罩：只在固件状态读不到或面板不足时出现，出现期间双方都不能操作。 */
    fun shield(message: String) {
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        if (cover?.parent == root && cover?.visibility == View.VISIBLE && cover?.text == message) {
            return
        }
        if (cover?.parent != root) {
            cover = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(35, 35, 35))
                // 可点击只为吞掉触摸，避免遮罩期间点穿到棋盘。
                isClickable = true
            }
            root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        }
        cover?.text = message
        cover?.visibility = View.VISIBLE
        cover?.bringToFront()

        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        gestureVersion = -1
        cancelPendingInput()
    }

    fun reveal() {
        cover?.visibility = View.GONE
    }

    /**
     * 把本窗口绑到 [target] 这块屏上。
     *
     * 当前不在目标屏时，用 `launchDisplayId` 拉起同一个 Activity 的新实例再 finish 自己，
     * 不在同一个实例里改 display。对局状态在 [GameSession] 里，迁移不会丢局。
     */
    fun bind(target: Int, token: String, version: Long, rotation: Float) {
        if (moving) return
        if (display?.displayId != target) {
            moving = true
            try {
                val options = ActivityOptions.makeBasic().apply { launchDisplayId = target }
                startActivity(
                    Intent(this, javaClass).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    ).putExtra(EXTRA_ROUTED, true),
                    options.toBundle()
                )
                finish()
            } catch (e: Exception) {
                moving = false
                Log.e(TAG, "Cannot route player=$battlePlayer to $target", e)
            }
            return
        }
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val size = "${root.width}x${root.height}"
        if (root.width == 0 || root.height == 0) return
        if (applied == token && lastSize == size) return
        applied = token
        lastSize = size

        // 抵消系统投影（含整机姿态与屏幕交换），让画面固定在物理面板上。
        // ViewGroup 会把逆变换套到触摸上，所以落子坐标不需要另外换算。
        if (root.rotation != rotation) root.rotation = rotation

        afterFrameCommit(root) {
            if (!isFinishing && applied == token) BattleDisplays.frame(this, version)
        }
        root.invalidate()
    }

    /** 新版本帧提交后才算「已上屏」；API 29 以下退化为连投两帧。 */
    private fun afterFrameCommit(root: View, onCommitted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fired = false
            root.viewTreeObserver.registerFrameCommitCallback {
                if (!fired) {
                    fired = true
                    onCommitted()
                }
            }
            root.postDelayed({
                if (!fired) {
                    fired = true
                    onCommitted()
                }
            }, 700)
        } else {
            root.post { root.post { onCommitted() } }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!BattleDisplays.ready) {
            // 遮罩在等固件状态时只让它吞事件，不往棋盘里送。
            if (cover?.visibility == View.VISIBLE) return super.dispatchTouchEvent(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureVersion = BattleDisplays.version
        if (gestureVersion != BattleDisplays.version) return true
        return super.dispatchTouchEvent(event)
    }

    companion object {
        const val TAG = "BattleDisplay"

        /**
         * 迁移标记：本实例是被 [bind] 按映射 relaunch 出来的，不是用户从桌面启动的。
         *
         * 宿主 Activity 的「强制回主屏」防呆必须跳过带此标记的实例——否则交换开启时
         * 玩家 A 被迁到 display 2，防呆又把它踢回 display 0，两边无限互相重建。
         */
        const val EXTRA_ROUTED = "com.huanglong.pad.dualscreen.ROUTED"
    }
}

/**
 * 双屏输出与过渡屏障。
 *
 * 玩家 → 屏 由 [PanelMap] 依固件状态算出；遮罩只用于真未知（属性非法、面板不足、
 * 两块屏算出同一个 displayId）。触发源合并：DisplayListener + onConfigurationChanged
 * + 布局尺寸变化 + 100ms 只读采样（有些帧交换不发 Display 事件）。
 */
object BattleDisplays {

    /** 调试开关：强制内容旋转（"0"/"180"），留空则按投影公式。 */
    private const val PROP_ROTATION_OVERRIDE = "debug.acds.content_rotation"

    var ready = false
        private set
    var version = 0L
        private set

    private val hosts = mutableMapOf<Player, BattleDisplayActivity>()
    private val frames = mutableSetOf<Player>()

    /**
     * 所有还活着的窗口。
     *
     * 必须单独记一份：`hosts` 在 onStop 时就移除，而「已经把应用切到后台、Activity 还在」
     * 的那扇窗口仍然需要被收掉，否则它会留在屏幕上（停住但不销毁）。
     */
    private val known = mutableSetOf<BattleDisplayActivity>()

    /** 正在被主动替换（重建/迁移）的玩家，此时它的窗口关闭不代表退出。 */
    private val replacing = mutableSetOf<Player>()

    private val handler = Handler(Looper.getMainLooper())
    private var executor: java.util.concurrent.ScheduledExecutorService? = null
    private var signature = ""
    private var lifecycle = 0L
    private var displayManager: DisplayManager? = null
    private var everHadTwoPanels = false

    /** 正在整机退出。退出过程中不得再上遮罩、也不得重建另一扇窗口，否则会留下「等双屏」的残影。 */
    private var exiting = false

    /** 正在把两扇窗口一起收掉（切后台）。 */
    private var parking = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = recheck()
        override fun onDisplayRemoved(displayId: Int) = recheck()
        override fun onDisplayChanged(displayId: Int) = recheck()
    }

    fun hasWindow(player: Player) = hosts.containsKey(player)

    /** 是否正在整机退出（宿主据此决定要不要丢弃本局）。 */
    val isExiting: Boolean get() = exiting

    /**
     * 窗口/显示有变化时只做一次「重新核对」，真正的失效留给 [update] 里的指纹比较。
     * 这台设备的 DisplayListener 会为无关变化反复回调（亮度、模式刷新），
     * 直接 invalidate() 会让遮罩反复重建、屏幕上一直闪「等待双屏就绪」。
     */
    fun configurationChanged() = recheck()

    private fun recheck() {
        if (hosts.isEmpty()) return
        update(readProperty(PanelMap.PROP_MASTER), readProperty(PanelMap.PROP_SCREEN_MODE))
    }

    fun attach(host: BattleDisplayActivity) {
        exiting = false
        hosts[host.battlePlayer]?.takeUnless { it === host }?.finish()
        hosts[host.battlePlayer] = host
        known.add(host)
        invalidate()
        if (executor != null) return

        val context = host.applicationContext
        displayManager = context.getSystemService(DisplayManager::class.java).also {
            it.registerDisplayListener(listener, handler)
        }
        val generation = ++lifecycle
        executor = Executors.newSingleThreadScheduledExecutor().also { worker ->
            worker.scheduleWithFixedDelay({
                val master = readProperty(PanelMap.PROP_MASTER)
                val shape = readProperty(PanelMap.PROP_SCREEN_MODE)
                handler.post { if (generation == lifecycle) update(master, shape) }
            }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    /** 只读属性；反射读取避免每 100ms 拉起一个 getprop 进程。 */
    private fun readProperty(name: String): String? = PanelMap.readProperty(name)

    fun detach(host: BattleDisplayActivity) {
        if (hosts[host.battlePlayer] === host) {
            hosts.remove(host.battlePlayer)
        }
        if (hosts.isEmpty()) {
            executor?.shutdownNow()
            executor = null
            lifecycle++
            displayManager?.unregisterDisplayListener(listener)
            displayManager = null
        }
        // 这里不要 invalidate()：给「剩下的那扇」上遮罩是错的。
        // 应用切后台时就是这条路径，遮罩会让另一块屏停在「等待双屏就绪」。
        // 另一扇的去留统一由 onHostStopped() / windowClosed() 决定。
    }

    /**
     * 一扇窗口 onStop 了：用户把应用切到后台（手势上划、Home、切到别的应用）。
     *
     * 双屏下另一块屏的 Activity 可能仍保持 RESUMED，于是只剩它一个人在前台
     * —— 表现就是「副屏一直显示等待双屏就绪」，或者它被 ensureSecondScreen 又拉起来（像重启）。
     * 这里把两扇一起收掉：都回桌面，回到应用时再按映射重新拉起，对局状态在 GameSession 里不丢。
     */
    fun onHostStopped(host: BattleDisplayActivity, migrating: Boolean, changingConfig: Boolean) {
        if (exiting || migrating || changingConfig || parking) return
        parkAll(host)
    }

    private fun parkAll(extra: BattleDisplayActivity? = null) {
        parking = true
        val open = LinkedHashSet<BattleDisplayActivity>()
        open.addAll(known)
        open.addAll(hosts.values)
        extra?.let { open.add(it) }
        hosts.clear()
        ready = false
        frames.clear()
        if (open.isNotEmpty()) {
            Log.i(BattleDisplayActivity.TAG, "app backgrounded, parking windows=${open.size}")
        }
        open.forEach { finishWindow(it) }
        handler.postDelayed({
            open.forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    runCatching { activity.finishAndRemoveTask() }
                }
            }
            parking = false
        }, 400)
    }

    /** 两侧一起退出：两个 Activity 在两个 task 里，必须逐个 finish，否则留下一屏空等。 */
    fun closeAll() {
        if (exiting) return
        exiting = true
        val open = LinkedHashSet<BattleDisplayActivity>()
        open.addAll(known)
        open.addAll(hosts.values)
        executor?.shutdownNow()
        executor = null
        lifecycle++
        displayManager?.unregisterDisplayListener(listener)
        displayManager = null
        ready = false
        frames.clear()
        hosts.clear()
        Log.i(BattleDisplayActivity.TAG, "closeAll: finishing windows=${open.size}")

        open.forEach { finishWindow(it) }
        // finishAffinity 对「另一个 task 里的窗口」不一定生效（实测副屏会留下来干等），
        // 400ms 后再补一刀，用 finishAndRemoveTask 把它的 task 也清掉。
        handler.postDelayed({
            open.forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    runCatching { activity.finishAndRemoveTask() }
                }
            }
        }, 400)
    }

    private fun finishWindow(activity: BattleDisplayActivity) {
        runCatching {
            if (activity.isFinishing || activity.isDestroyed) return@runCatching
            activity.finishAffinity()
            activity.finish()
        }
    }

    /** 主动替换某玩家的窗口前打标记，避免把「重建」误判成「用户退出」。 */
    fun markReplacing(player: Player) {
        replacing.add(player)
    }

    /**
     * 一扇窗口真的被关掉时（不是迁移、不是被主动替换），另一扇也要退出；
     * 但如果另一块面板本身已经没了，那是「等双屏恢复」，不能跟着退。
     */
    fun windowClosed(host: BattleDisplayActivity, migrating: Boolean) {
        known.remove(host)
        if (replacing.remove(host.battlePlayer)) return
        val replaced = hosts[host.battlePlayer] != null
        val peer = hosts.values.firstOrNull { it !== host }
        if (!ExitPolicy.closePeer(peer != null, availablePanels(), replaced, migrating)) return
        Log.i(BattleDisplayActivity.TAG, "player=${host.battlePlayer} closed, finishing peer window")
        // 用 closeAll 走统一退出路径：保证另一扇一定被关掉，且退出过程中不再上遮罩
        closeAll()
    }

    private fun availablePanels(): Int {
        val dm = displayManager ?: return 0
        return PanelMap.visiblePanels(dm).size
    }

    private fun invalidate() {
        ready = false
        version++
        frames.clear()
        signature = ""
        hosts.values.forEach { it.shield("等待双屏就绪") }
    }

    private fun update(master: String?, shape: String?) {
        if (exiting || parking) return
        val host = hosts.values.firstOrNull() ?: return
        val dm = host.getSystemService(DisplayManager::class.java)
        val panels = PanelMap.visiblePanels(dm)
        if (panels.size >= 2) everHadTwoPanels = true

        // 调试用：现场核对朝向时可以直接改，不必重新打包。
        //   adb shell setprop debug.acds.content_rotation 0     → 应用不做任何旋转
        //   adb shell setprop debug.acds.content_rotation 180   → 强制旋转 180
        //   adb shell setprop debug.acds.content_rotation ""    → 回到按投影公式计算
        val rotationOverride = readProperty(PROP_ROTATION_OVERRIDE)

        // 固件不暴露交换属性 → 该机型没有交换能力，按「始终未交换」处理，不遮罩
        val effectiveMaster = master ?: "0"
        val routes = PanelMap.routes(effectiveMaster, panels)

        // 玩家 B 的窗口由玩家 A 负责拉起，直接投到映射算出的目标屏（它掌握游戏控制权）
        (hosts[Player.A] as? MainActivity)?.ensureSecondScreen(routes?.get(Player.B))

        // 指纹只放真正影响绑定的东西：id、rotation、名称。
        // 不要把 state 放进来：这块屏的 state 会在 ON/DIM 之间来回变（亮度、屏保），
        // 一进指纹就会不停失效，屏幕上就是一直闪「等待双屏就绪」。
        val next = "${master ?: "?"}|${shape ?: "?"}|${rotationOverride ?: "-"}|" +
            panels.joinToString { "${it.displayId}:${it.rotation}:${it.name}" }
        if (signature != next) {
            invalidate()
            signature = next
            Log.i(
                BattleDisplayActivity.TAG,
                PanelMap.logLine(version.toInt(), master, shape, panels, routes) +
                    (if (master == null) " degraded=no-swap-prop" else "")
            )
        }

        // 单屏机型/主屏调试：从来没有第二块屏，不能把应用锁死在遮罩上
        val singleScreen = panels.size < 2 && !everHadTwoPanels

        if (routes == null || (singleScreen && hosts.size < 2)) {
            if (singleScreen) {
                val onlyId = panels.firstOrNull()?.displayId ?: android.view.Display.DEFAULT_DISPLAY
                hosts[Player.A]?.bind(
                    onlyId,
                    "$version:$next",
                    version,
                    resolveRotation(
                        dm.getDisplay(onlyId)?.rotation ?: 0,
                        effectiveMaster,
                        shape,
                        rotationOverride
                    )
                )
                ready = true
                hosts.values.forEach { it.reveal() }
                return
            }
            hosts.values.forEach { it.shield(blockedReason(panels, master)) }
            return
        }

        hosts.toMap().forEach { (player, activity) ->
            val target = routes[player] ?: return@forEach
            val windowRotation = dm.getDisplay(target)?.rotation ?: activity.display?.rotation ?: 0
            activity.bind(
                target,
                "$version:$next",
                version,
                resolveRotation(windowRotation, effectiveMaster, shape, rotationOverride)
            )
        }
    }

    /** 内容旋转：默认按显示投影公式算，`debug.acds.content_rotation` 可强制覆盖。 */
    private fun resolveRotation(
        windowRotation: Int,
        master: String?,
        shape: String?,
        override: String?
    ): Float = when (override) {
        "0" -> 0f
        "180" -> 180f
        else -> PanelMap.contentRotation(windowRotation, master, shape)
    }

    private fun blockedReason(panels: List<PanelInfo>, master: String?): String = when {
        master != null && master !in listOf("0", "1") ->
            "读不到屏幕交换状态 persist.vendor.display.master\n请确认固件与读取权限后重启应用"
        panels.size < 2 -> "等待双屏恢复\n当前可用面板 ${panels.size} 块"
        else -> "双屏状态异常，等待恢复"
    }

    fun frame(host: BattleDisplayActivity, frameVersion: Long) {
        if (frameVersion != version || hosts[host.battlePlayer] !== host) return
        frames.add(host.battlePlayer)
        if (frames.containsAll(listOf(Player.A, Player.B)) && hosts.size == 2) {
            ready = true
            Log.i(BattleDisplayActivity.TAG, "ready version=$version both frames committed")
            hosts.values.forEach { it.reveal() }
        }
    }
}
