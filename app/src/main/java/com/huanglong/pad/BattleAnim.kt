package com.huanglong.pad

import android.view.View

/**
 * 对决浮窗入场动画：卡片缩放淡入 + 攻防双方对冲相遇。主屏与副屏共用。
 */
object BattleAnim {
    /** scale 为卡片最终缩放（用于副屏按 density 比例放大，主屏默认 1f） */
    fun play(card: View, attacker: View, defender: View, scale: Float = 1f) {
        card.alpha = 0f
        card.scaleX = 0.7f * scale
        card.scaleY = 0.7f * scale
        card.animate().alpha(1f).scaleX(scale).scaleY(scale).setDuration(260).start()

        attacker.translationX = -70f
        attacker.animate().translationX(0f).setDuration(340).start()

        defender.translationX = 70f
        defender.animate().translationX(0f).setDuration(340).start()
    }
}
