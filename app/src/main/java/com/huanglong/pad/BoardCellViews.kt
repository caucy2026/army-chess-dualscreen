package com.huanglong.pad

import android.view.View
import android.widget.TextView

/** 缓存单个棋格的子视图，避免每次双屏刷新都重复执行 findViewById。 */
internal class BoardCellViews(root: View) {
    val root: View = root
    val piece: TextView = root.findViewById(R.id.tv_piece_text)
    val targetHighlight: View = root.findViewById(R.id.v_cell_highlight)
    val lastMoveHighlight: View = root.findViewById(R.id.v_last_move_highlight)
}
