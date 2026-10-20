package com.huanglong.pad.dualscreen

import android.hardware.display.DisplayManager
import android.view.Display
import com.huanglong.pad.game.Player

/**
 * 一块实体面板的运行时快照。
 *
 * 注意：这里记录的是 **物理面板** 的候选者（即系统里可见的逻辑显示），
 * 它的 [displayId] 只是「逻辑编号」，交换时编号不变、但对应到哪块实体面板会变。
 */
data class PanelInfo(
    val displayId: Int,
    val name: String,
    val state: Int,
    val rotation: Int
)

/** 允许上遮罩的三种「真未知」。 */
enum class MaskReason {
    NONE,
    /** persist.vendor.display.master 存在但不是 0/1（读不到时按降级处理，不遮罩）。 */
    MASTER_UNKNOWN,
    /** 可用实体面板不足两块。 */
    PANELS_INSUFFICIENT,
    /** 两块屏算出同一个 displayId。 */
    DUPLICATE_ROUTE
}

/**
 * 玩家 ↔ 物理面板映射 与 显示投影方向换算。
 *
 * 依据 `KEMI双屏对战应用适配指南.md`（V1.1）第 1、2 章：
 *  - 玩家绑定物理面板，不绑定 displayId；
 *  - 画面朝向要抵消「显示投影」，不是窗口 rotation；
 *  - 不写死副屏 displayId == 2。
 *
 * 本类只做纯计算，方便 JVM 单测覆盖。
 */
object PanelMap {

    /** 固件给出的交换语义：0 = 未交换，1 = 已交换。 */
    const val PROP_MASTER = "persist.vendor.display.master"

    /** 仅用于诊断与 A 字分支，缺失不得阻塞开局。 */
    const val PROP_SCREEN_MODE = "vendor.nl.screen_mode"

    /** vendor.nl.screen_mode == 1 表示 A 字形态（不叠加交换补偿）。 */
    private const val SCREEN_MODE_A_SHAPE = "1"

    /**
     * Display.TYPE_VIRTUAL 是隐藏常量，值为 5。
     *
     * 不要误用 2：2 是 TYPE_EXTERNAL，本机 HDMI 副屏正是 2，
     * 写成 2 会把真正的副屏当成虚拟屏过滤掉（实测踩过）。
     */
    private const val TYPE_VIRTUAL = 5

    private const val VIRTUAL_UNIQUE_PREFIX = "virtual:"

    /**
     * 反射读取系统属性。
     *
     * 不要每帧 ProcessBuilder("/system/bin/getprop")：那会拉起进程，帧率/耗电异常。
     */
    fun readProperty(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** 虚拟屏判断：优先反射 getType()，读不到时按 uniqueId 前缀兜底。 */
    fun isVirtual(display: Display): Boolean {
        try {
            val m = Display::class.java.getMethod("getType")
            if ((m.invoke(display) as? Int) == TYPE_VIRTUAL) return true
        } catch (_: Throwable) {
            // SDK 上 getType() 是隐藏接口，反射失败走 uniqueId 兜底
        }
        val uniqueId = try {
            // getUniqueId() 在部分 SDK 上同样是隐藏接口，一并走反射
            (Display::class.java.getMethod("getUniqueId").invoke(display) as? String) ?: ""
        } catch (_: Throwable) {
            ""
        }
        return uniqueId.startsWith(VIRTUAL_UNIQUE_PREFIX)
    }

    /**
     * 取可用实体面板：排除虚拟屏与 STATE_OFF，按 displayId 升序。
     *
     * 不要写死 `displayId == 2`：别的机型/固件可能是 1 或其它值。
     */
    fun visiblePanels(displays: List<Display>): List<PanelInfo> =
        displays.asSequence()
            .filter { it.isValid && it.state != Display.STATE_OFF && !isVirtual(it) }
            .sortedBy { panelOf(it).displayId }
            .map { panelOf(it) }
            .toList()

    private fun panelOf(d: Display): PanelInfo = PanelInfo(
        displayId = d.displayId,
        name = d.name ?: "",
        state = d.state,
        rotation = d.rotation
    )

    /**
     * 直接从 DisplayManager 取实体面板列表。
     *
     * 注意本机（hi3781v730）固件的一个事实：HDMI 副屏带 FLAG_PRESENTATION，
     * 但普通应用调用 `displays`（getDisplays()）拿不到它，只有按
     * [DisplayManager.DISPLAY_CATEGORY_PRESENTATION] 类别查询才可见。
     * 因此两个来源取并集、按 displayId 去重，再统一走虚拟屏/关闭屏过滤。
     */
    fun visiblePanels(displayManager: DisplayManager): List<PanelInfo> {
        val all = ArrayList<Display>(4)
        all.addAll(displayManager.displays.toList())
        runCatching {
            all.addAll(
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
            )
        }
        val deduped = all.distinctBy { it.displayId }
        return visiblePanels(deduped)
    }

    /**
     * 物理面板 A 固定玩家 A（主屏/黑），物理面板 B 固定玩家 B（副屏/白）。
     *
     * 交换关闭 `master = "0"`：逻辑主屏显示在 A，逻辑副屏显示在 B；
     * 交换开启 `master = "1"`：逻辑主屏显示在 B，逻辑副屏显示在 A。
     *
     * @return null 表示无法判定（属性缺失/非法或面板不足），调用方不得猜测。
     */
    fun routes(master: String?, panels: List<PanelInfo>): Map<Player, Int>? {
        if (panels.size < 2) return null
        val logicalPrimary = panels[0].displayId
        val logicalSecondary = panels[1].displayId
        if (logicalPrimary == logicalSecondary) return null
        return when (master) {
            "0" -> mapOf(Player.A to logicalPrimary, Player.B to logicalSecondary)
            "1" -> mapOf(Player.A to logicalSecondary, Player.B to logicalPrimary)
            else -> null
        }
    }

    /**
     * 降级映射：仅当固件不暴露 [PROP_MASTER]（即该机型没有交换能力）时使用，
     * 等价于「始终未交换」。属性存在但非法时必须走遮罩，不能走这里。
     */
    fun routesWithoutSwap(panels: List<PanelInfo>): Map<Player, Int>? {
        if (panels.size < 2) return null
        val primary = panels[0].displayId
        val secondary = panels[1].displayId
        if (primary == secondary) return null
        return mapOf(Player.A to primary, Player.B to secondary)
    }

    /**
     * 内容需要施加的旋转角，用于抵消「显示投影」。
     *
     * 投影   = 窗口 rotation × 90 + (已交换且非 A 字 ? 180 : 0)
     * 应用旋转 = 360 − 投影
     *
     * 竖持（投影 90/270）需要换宽高，本应用布局为横屏固定，返回 0 交由调用方忽略。
     */
    fun contentRotation(windowRotation: Int, master: String?, screenMode: String?): Float {
        val window = ((windowRotation % 4) + 4) % 4 * 90
        val swapped = master == "1" && screenMode != SCREEN_MODE_A_SHAPE
        val projection = (window + if (swapped) 180 else 0) % 360
        if (projection % 180 != 0) return 0f
        return ((360 - projection) % 360).toFloat()
    }

    /**
     * 状态指纹 = master + screen_mode + 每块屏的 displayId / state / rotation / 名称。
     * 指纹变化才触发过渡屏障。
     */
    fun fingerprint(master: String?, screenMode: String?, panels: List<PanelInfo>): String {
        val panelPart = panels.joinToString(",") {
            "${it.displayId}:${it.state}:${it.rotation}:${it.name}"
        }
        return "${master ?: "?"}|${screenMode ?: "-"}|$panelPart"
    }

    /** 依据指南第 5 章判定是否属于「真未知」，只有这几种允许上遮罩。 */
    fun maskReason(
        masterPresent: Boolean,
        master: String?,
        routes: Map<Player, Int>?,
        panels: List<PanelInfo>
    ): MaskReason = when {
        masterPresent && master != "0" && master != "1" -> MaskReason.MASTER_UNKNOWN
        panels.size < 2 -> MaskReason.PANELS_INSUFFICIENT
        routes == null -> MaskReason.MASTER_UNKNOWN
        routes.values.toSet().size < 2 -> MaskReason.DUPLICATE_ROUTE
        else -> MaskReason.NONE
    }

    /**
     * 诊断用：把一块 Display 的关键字段拼成一行，便于现场核对枚举结果。
     */
    fun describe(display: Display): String {
        val type = try {
            (Display::class.java.getMethod("getType").invoke(display) as? Int)?.toString() ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        return "id=${display.displayId} name=${display.name} state=${display.state}" +
            " rot=${display.rotation} type=$type virtual=${isVirtual(display)}" +
            " flags=0x${Integer.toHexString(display.flags)}"
    }

    /** 诊断用：当前应用能看到的全部 Display（不做任何过滤）。 */
    fun rawDisplays(displayManager: DisplayManager): List<Display> {
        val all = ArrayList<Display>(4)
        runCatching { all.addAll(displayManager.displays.toList()) }
        runCatching {
            all.addAll(
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
            )
        }
        return all.distinctBy { it.displayId }
    }

    /** 交换能力是否由固件提供。 */
    fun hasSwapCapability(): Boolean = readProperty(PROP_MASTER) != null

    /** 指南 8.2 的状态日志行，便于现场对账。 */
    fun logLine(
        version: Int,
        master: String?,
        screenMode: String?,
        panels: List<PanelInfo>,
        routes: Map<Player, Int>?
    ): String {
        val state = panels.joinToString(",") {
            "${it.displayId}:${it.state}:${it.rotation}:${it.name}"
        }
        val route = routes?.let { r ->
            "{A=${r[Player.A]}, B=${r[Player.B]}}"
        } ?: "null"
        return "version=$version state=${master ?: "?"}|${screenMode ?: "-"}|$state routes=$route"
    }
}
