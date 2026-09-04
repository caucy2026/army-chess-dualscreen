package com.huanglong.pad

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.huanglong.pad.game.ChessPiece

/** 主副屏共用的棋子托盘，使用精确差异刷新以减少无效重绘。 */
internal class PieceTrayAdapter(
    @DrawableRes private val normalBackground: Int,
    @DrawableRes private val selectedBackground: Int,
    @ColorRes private val textColor: Int,
    private val onSelect: (ChessPiece) -> Unit
) : RecyclerView.Adapter<PieceTrayAdapter.ViewHolder>() {

    private var items = emptyList<ChessPiece>()
    private var selectedId: String? = null

    fun updateList(newItems: List<ChessPiece>, selectedPiece: ChessPiece?) {
        val oldItems = items
        val oldSelectedId = selectedId
        val snapshot = newItems.toList()
        val newSelectedId = selectedPiece?.id

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = snapshot.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == snapshot[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == snapshot[newItemPosition]
        })

        items = snapshot
        selectedId = newSelectedId
        diff.dispatchUpdatesTo(this)

        if (oldSelectedId != newSelectedId) {
            setOfNotNull(oldSelectedId, newSelectedId).forEach { id ->
                val index = items.indexOfFirst { it.id == id }
                if (index >= 0) notifyItemChanged(index, SELECTION_PAYLOAD)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.context.resources.getDimensionPixelSize(R.dimen.piece_tray_height)
            )
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = ResourcesCompat.getFont(parent.context, R.font.zcool_kuaile)
            setPadding(4, 4, 4, 4)
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bind(holder.textView, items[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(SELECTION_PAYLOAD)) {
            applySelectionStyle(holder.textView, items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bind(textView: TextView, piece: ChessPiece) {
        textView.text = piece.type.displayName
        applySelectionStyle(textView, piece)
        textView.setOnClickListener { onSelect(piece) }
    }

    private fun applySelectionStyle(textView: TextView, piece: ChessPiece) {
        textView.setBackgroundResource(
            if (selectedId == piece.id) selectedBackground else normalBackground
        )
        textView.setTextColor(ContextCompat.getColor(textView.context, textColor))
    }

    override fun getItemCount(): Int = items.size

    internal class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private companion object {
        const val SELECTION_PAYLOAD = "selection"
    }
}
