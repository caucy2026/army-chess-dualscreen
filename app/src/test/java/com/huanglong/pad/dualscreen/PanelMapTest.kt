package com.huanglong.pad.dualscreen

import com.huanglong.pad.game.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双屏映射与方向换算的回归测试。
 *
 * 对应 `KEMI双屏对战应用适配指南.md` 第 1、2、5 章的判定表：
 * 玩家绑定物理面板、交换语义、显示投影换算、遮罩准入条件。
 */
class PanelMapTest {

    private fun panel(id: Int, rotation: Int = 0, state: Int = 2) =
        PanelInfo(displayId = id, name = "panel-$id", state = state, rotation = rotation)

    // ---------- 第 1 章：玩家 ↔ 物理面板 ----------

    @Test
    fun `交换关闭时逻辑主屏是玩家A`() {
        val routes = PanelMap.routes("0", listOf(panel(0), panel(2)))!!
        assertEquals(0, routes[Player.A])
        assertEquals(2, routes[Player.B])
    }

    @Test
    fun `交换开启时逻辑主屏是玩家B`() {
        val routes = PanelMap.routes("1", listOf(panel(0), panel(2)))!!
        assertEquals(2, routes[Player.A])
        assertEquals(0, routes[Player.B])
    }

    @Test
    fun `读不到master时不猜`() {
        assertNull(PanelMap.routes(null, listOf(panel(0), panel(2))))
    }

    @Test
    fun `master非法值不猜`() {
        assertNull(PanelMap.routes("2", listOf(panel(0), panel(2))))
        assertNull(PanelMap.routes("", listOf(panel(0), panel(2))))
    }

    @Test
    fun `只有一块面板时无法映射`() {
        assertNull(PanelMap.routes("0", listOf(panel(0))))
    }

    @Test
    fun `副屏displayId不写死为2`() {
        // 别的机型/固件副屏可能是 7
        val routes = PanelMap.routes("0", listOf(panel(0), panel(7)))!!
        assertEquals(7, routes[Player.B])
    }

    @Test
    fun `面板顺序按displayId升序决定主副`() {
        val panels = listOf(panel(3), panel(1)).sortedBy { it.displayId }
        val routes = PanelMap.routes("0", panels)!!
        assertEquals(1, routes[Player.A])
        assertEquals(3, routes[Player.B])
    }

    @Test
    fun `无交换能力的机型走降级映射`() {
        val routes = PanelMap.routesWithoutSwap(listOf(panel(0), panel(2)))!!
        assertEquals(0, routes[Player.A])
        assertEquals(2, routes[Player.B])
    }

    // ---------- 第 2 章：显示投影 / 内容旋转 ----------

    @Test
    fun `0度未交换不旋转`() {
        assertEquals(0f, PanelMap.contentRotation(0, "0", null), 0.001f)
    }

    @Test
    fun `0度已交换补180`() {
        assertEquals(180f, PanelMap.contentRotation(0, "1", null), 0.001f)
    }

    @Test
    fun `180度未交换补180`() {
        assertEquals(180f, PanelMap.contentRotation(2, "0", null), 0.001f)
    }

    @Test
    fun `180度已交换抵消为0`() {
        assertEquals(0f, PanelMap.contentRotation(2, "1", null), 0.001f)
    }

    @Test
    fun `A字形态不叠加交换补偿`() {
        assertEquals(0f, PanelMap.contentRotation(0, "1", "1"), 0.001f)
    }

    @Test
    fun `竖持90度回退为不旋转`() {
        // 90/270 需要换宽高，本项目布局为横屏，返回 0 交由调用方忽略
        assertEquals(0f, PanelMap.contentRotation(1, "0", null), 0.001f)
        assertEquals(0f, PanelMap.contentRotation(3, "0", null), 0.001f)
    }

    @Test
    fun `旋转结果只有0或180`() {
        for (rot in 0..3) {
            for (master in listOf("0", "1", null)) {
                for (mode in listOf(null, "0", "1")) {
                    val deg = PanelMap.contentRotation(rot, master, mode)
                    assertTrue("deg=$deg", deg == 0f || deg == 180f)
                }
            }
        }
    }

    // ---------- 第 4 章：状态指纹 ----------

    @Test
    fun `指纹随master与rotation变化`() {
        val base = listOf(panel(0), panel(2))
        val a = PanelMap.fingerprint("0", null, base)
        val b = PanelMap.fingerprint("1", null, base)
        val c = PanelMap.fingerprint("0", null, listOf(panel(0), panel(2, rotation = 2)))
        assertTrue(a != b)
        assertTrue(a != c)
    }

    @Test
    fun `指纹稳定时不变`() {
        val panels = listOf(panel(0), panel(2))
        assertEquals(
            PanelMap.fingerprint("0", "0", panels),
            PanelMap.fingerprint("0", "0", panels)
        )
    }

    // ---------- 第 5 章：遮罩只留三种真未知 ----------

    @Test
    fun `常态不需要遮罩`() {
        val panels = listOf(panel(0), panel(2))
        val routes = PanelMap.routes("0", panels)
        assertEquals(MaskReason.NONE, PanelMap.maskReason(true, "0", routes, panels))
    }

    @Test
    fun `属性存在但非法要遮罩`() {
        val panels = listOf(panel(0), panel(2))
        assertEquals(
            MaskReason.MASTER_UNKNOWN,
            PanelMap.maskReason(true, "9", PanelMap.routes("9", panels), panels)
        )
    }

    @Test
    fun `面板不足要遮罩`() {
        val panels = listOf(panel(0))
        assertEquals(
            MaskReason.PANELS_INSUFFICIENT,
            PanelMap.maskReason(true, "0", PanelMap.routes("0", panels), panels)
        )
    }

    @Test
    fun `两块屏算出同一displayId要遮罩`() {
        val panels = listOf(panel(0), panel(0))
        val routes = mapOf(Player.A to 0, Player.B to 0)
        assertEquals(
            MaskReason.DUPLICATE_ROUTE,
            PanelMap.maskReason(true, "0", routes, panels)
        )
    }

    // ---------- 第 8 章：日志 ----------

    @Test
    fun `日志行含版本与路由`() {
        val panels = listOf(panel(0), panel(2))
        val line = PanelMap.logLine(42, "0", null, panels, PanelMap.routes("0", panels))
        assertTrue(line, line.contains("version=42"))
        assertTrue(line, line.contains("A=0"))
        assertTrue(line, line.contains("B=2"))
    }
}
