package com.huanglong.pad.dualscreen

import com.huanglong.pad.game.Player

/**
 * 两个窗口分属两个 task（玩家 B 的 taskAffinity 带 `.white` 后缀），所以退出必须显式处理：
 * 只 finish 自己会留下另一屏停在「等待双屏就绪」。
 */
object ExitPolicy {

    /**
     * 用户关掉一扇窗口时，另一扇是否要跟着退出。
     *
     * @param peerAlive 另一扇窗口是否还在
     * @param panelsAvailable 当前可用实体面板数
     * @param replaced 该玩家是否已被新窗口替换（切换显示时的迁移/重建）
     * @param migrating 这一扇是否正在迁往另一块屏
     */
    fun closePeer(
        peerAlive: Boolean,
        panelsAvailable: Int,
        replaced: Boolean,
        migrating: Boolean
    ): Boolean = !migrating && !replaced && peerAlive && panelsAvailable >= 2

    /**
     * 宽限期到了还只剩一扇窗口，说明另一扇是被系统直接收掉的（不走 isFinishing），
     * 这时剩下那扇也不该继续等；但如果确实只剩一块可用面板，那是等屏恢复，保留。
     */
    fun reconcileClose(hostCount: Int, panelsAvailable: Int): Boolean =
        hostCount == 1 && panelsAvailable >= 2

    /** 玩家 → 是否为主玩家（A）。仅用于日志/诊断。 */
    fun isPrimary(player: Player): Boolean = player == Player.A
}
