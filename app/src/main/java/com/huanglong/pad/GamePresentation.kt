package com.huanglong.pad

import android.app.Presentation
import android.content.Context
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huanglong.pad.databinding.PresentationGameBinding
import com.huanglong.pad.game.*

/**
 * 外部副屏展示及交互控制器 (针对玩家 B)
 */
class GamePresentation(
    private val mainActivity: MainActivity,
    outerDisplay: Display
) : Presentation(mainActivity, outerDisplay) {

    private lateinit var binding: PresentationGameBinding
    private val cells = HashMap<Position, View>()
    private var trayAdapter: TrayAdapter? = null

    override fun onCreate(androidBundle: android.os.Bundle?) {
        super.onCreate(androidBundle)
        
        binding = PresentationGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置全屏沉浸模式 (隐藏状态栏和导航栏)
        window?.let { win ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
            androidx.core.view.WindowInsetsControllerCompat(win, binding.root).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // 副屏棋盘背景按 B 视角绘制 (B 大本营落在画布下半区)，
        // 外层 XML 的 180° 物理旋转仅用于校正 B 背靠背读屏的文字/方向
        binding.boardBackgroundView.perspective = Player.B

        initBoardGrid()
        initTrayRecyclerView()
        setupListeners()
        applyInitialBackground()
        refreshUI()
    }

    /**
     * 初始化 12x5 交互网格
     */
    private fun initBoardGrid() {
        val grid = binding.boardGridLayout
        grid.removeAllViews()

        for (r in 0 until GameEngine.ROWS) {
            for (c in 0 until GameEngine.COLS) {
                val cellView = layoutInflater.inflate(R.layout.view_board_cell, grid, false)

                // 副屏以 B 视角渲染：逻辑坐标经 reverse() 映射到物理网格，
                // 使 B 的布防区 (逻辑 Row 0-5) 落在画布下半区，与背景视角一致
                val logicPos = Position(r, c)
                val visualPos = logicPos.reverse()
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    rowSpec = GridLayout.spec(visualPos.row, 1f)
                    columnSpec = GridLayout.spec(visualPos.col, 1f)
                }
                cellView.layoutParams = params

                cells[logicPos] = cellView

                // 设置格子点击监听，点击时将逻辑位置回传给 MainActivity 处理
                cellView.setOnClickListener {
                    mainActivity.onCellClicked(logicPos, Player.B)
                }
                grid.addView(cellView)
            }
        }
    }

    /**
     * 初始化备选托盘
     */
    private fun initTrayRecyclerView() {
        binding.rvPieceTray.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 2)
        trayAdapter = TrayAdapter { piece ->
            mainActivity.onTrayPieceSelected(piece, Player.B)
        }
        binding.rvPieceTray.adapter = trayAdapter
    }

    // 副屏背景场景循环：四张场景图轮播（已移除"无背景"档），与主屏 A 各自独立。
    // 启动恢复上次保存的背景；若无记录/读到无背景则随机选一张兜底。
    private val bgScenes = intArrayOf(
        R.drawable.bg_scene_1, R.drawable.bg_scene_2, R.drawable.bg_scene_3,
        R.drawable.bg_scene_4, R.drawable.bg_scene_5, R.drawable.bg_scene_6,
        R.drawable.bg_scene_7, R.drawable.bg_scene_8, R.drawable.bg_scene_9,
        R.drawable.bg_scene_10
    )
    private val bgSceneNames = arrayOf("沙漠迷彩", "丛林迷彩", "蔚蓝海洋", "静谧星空", "春意江南", "桃林春晓", "樱花漫舞", "幽谷兰香", "小桥流水", "山水田园")
    private val bgPrefs by lazy { context.getSharedPreferences("game_prefs", Context.MODE_PRIVATE) }
    private var bgSceneIndex = -1

    /** 启动副屏初始背景：优先用上次保存的索引；无效(无记录/无背景)则随机一张兜底，并写回保存使之固定。 */
    private fun applyInitialBackground() {
        val saved = bgPrefs.getInt("bg_index_b", -1)
        bgSceneIndex = if (saved in bgScenes.indices) saved else bgScenes.indices.random()
        binding.root.setBackgroundResource(bgScenes[bgSceneIndex])
        bgPrefs.edit().putInt("bg_index_b", bgSceneIndex).apply()
    }

    private fun setupListeners() {
        // 一键布阵
        binding.btnAutoLayout.setOnClickListener {
            mainActivity.autoLayout(Player.B)
        }
        // 完成布阵
        binding.btnReady.setOnClickListener {
            mainActivity.setPlayerReady(Player.B)
        }
        // 重置 (需双边确认)
        binding.btnReset.setOnClickListener {
            mainActivity.requestReset(Player.B)
        }
        // 更换背景（仅切换副屏 B 自己的背景，循环四种场景；并持久化以便下次启动恢复）
        binding.btnChangeBg.setOnClickListener {
            bgSceneIndex = (bgSceneIndex + 1) % bgScenes.size
            binding.root.setBackgroundResource(bgScenes[bgSceneIndex])
            bgPrefs.edit().putInt("bg_index_b", bgSceneIndex).apply()
            showTip("背景：${bgSceneNames[bgSceneIndex]}")
        }
    }

    fun playMoveAnimation(from: Position, to: Position) {
        val sourceCell = cells[from] as? android.view.ViewGroup
        val targetCell = cells[to]
        if (sourceCell == null || targetCell == null) return
        
        val tvSource = sourceCell.findViewById<android.widget.TextView>(R.id.tv_piece_text)
        
        binding.boardGridLayout.clipChildren = false
        sourceCell.clipChildren = false
        sourceCell.bringToFront()
        
        val dx = targetCell.x - sourceCell.x
        val dy = targetCell.y - sourceCell.y
        
        tvSource.animate()
            .translationX(dx)
            .translationY(dy)
            .setDuration(400)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                tvSource.translationX = 0f
                tvSource.translationY = 0f
                binding.boardGridLayout.clipChildren = true
                sourceCell.clipChildren = true
            }
            .start()
    }

    /**
     * 刷新界面 (由 MainActivity 在状态变更时主动调用)
     */
    fun refreshUI() {
        if (!::binding.isInitialized) return

        val stage = mainActivity.gameStage
        val turn = mainActivity.currentTurn
        val readyB = mainActivity.isReadyB

        // 1. 刷新状态栏
        when (stage) {
            MainActivity.GameStage.LAYOUT -> {
                binding.tvStatusText.text = if (readyB) "等待玩家 A 完成布阵..." else "请在下方布防区排兵布阵"
                binding.tvTurnIndicator.text = "布阵阶段"
                binding.btnAutoLayout.visibility = if (readyB) View.GONE else View.VISIBLE
                binding.btnReady.visibility = View.VISIBLE
                binding.btnReady.isEnabled = !readyB
                binding.btnReady.text = if (readyB) "已就绪" else "完成布阵"
            }
            MainActivity.GameStage.PLAYING -> {
                binding.tvStatusText.text = "对弈中..."
                binding.tvTurnIndicator.text = if (turn == Player.B) "🟢 轮到您的回合" else "🔵 玩家 A 回合中"
                binding.btnAutoLayout.visibility = View.GONE
                binding.btnReady.visibility = View.GONE
            }
            MainActivity.GameStage.FINISHED -> {
                val winner = mainActivity.winner
                val reason = mainActivity.winReason
                binding.tvStatusText.text = if (reason.isEmpty()) "游戏结束" else "游戏结束：$reason"
                binding.tvTurnIndicator.text = if (winner == Player.B) "🎉 恭喜！您获胜了！" else "❌ 您输了！"
            }
        }

        // 2. 刷新待部署托盘 (对弈/结束阶段改为显示游戏规则速查)
        if (stage == MainActivity.GameStage.LAYOUT && !mainActivity.isReadyB) {
            binding.tvTrayLabel.visibility = View.VISIBLE
            binding.rvPieceTray.visibility = View.VISIBLE
            binding.svRules.visibility = View.GONE
            trayAdapter?.updateList(mainActivity.trayPiecesB, mainActivity.selectedPieceB)
        } else {
            binding.tvTrayLabel.visibility = View.GONE
            binding.rvPieceTray.visibility = View.GONE
            binding.svRules.visibility = View.VISIBLE
        }

        // 3. 刷新棋盘单元格与棋子渲染
        val board = mainActivity.boardState
        val highlights = mainActivity.highlightedTargets
        val selectedCell = mainActivity.selectedCellB

        for ((pos, cellView) in cells) {
            val tvPiece = cellView.findViewById<TextView>(R.id.tv_piece_text)
            val vHighlight = cellView.findViewById<View>(R.id.v_cell_highlight)
            val piece = board[pos]

            // 渲染高亮行军路径
            if (highlights.contains(pos)) {
                vHighlight.visibility = View.VISIBLE
                vHighlight.background?.setTint(ContextCompat.getColor(context, R.color.color_b))
            } else {
                vHighlight.visibility = View.GONE
            }

            // 渲染最后一步高亮
            val vLastMove = cellView.findViewById<android.view.View>(R.id.v_last_move_highlight)
            if (pos == mainActivity.lastMoveFrom || pos == mainActivity.lastMoveTo) {
                vLastMove.visibility = View.VISIBLE
            } else {
                vLastMove.visibility = View.GONE
            }

            // 渲染棋子
            if (piece != null) {
                tvPiece.visibility = View.VISIBLE
                tvPiece.text = piece.type.displayName
                
                // 判断是己方还是敌方棋子
                if (piece.owner == Player.B) {
                    // 己方棋子：明棋，绿色高亮框
                    tvPiece.setBackgroundResource(R.drawable.piece_b_bg)
                    tvPiece.setTextColor(ContextCompat.getColor(context, R.color.white))
                    
                    // 如果在对弈阶段是被选中的棋子，增加呼吸灯边框
                    if (selectedCell == pos) {
                        tvPiece.setBackgroundResource(R.drawable.piece_b_selected_bg)
                    }
                } else {
                    // 敌方棋子 (Player A)：暗棋，不可见
                    if (stage == MainActivity.GameStage.FINISHED) {
                        // 如果碰子曝光或游戏结束，亮明
                        tvPiece.setBackgroundResource(R.drawable.piece_a_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(context, R.color.white))
                    } else if (piece.type == PieceType.FLAG &&
                        mainActivity.revealedFlagOwners.contains(piece.owner)) {
                        // 司令阵亡，敌方军旗强制亮明（保留文字"军旗"，红底天窗）
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(context, R.color.white))
                    } else {
                        // 背面朝上 (锁头图标)
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.text = ""
                        tvPiece.setTextColor(ContextCompat.getColor(context, R.color.white))
                    }
                }
            } else {
                tvPiece.visibility = View.GONE
            }
        }
    }

    /**
     * 副屏全局提示条（仿主屏 Toast）：B 在副屏操作触发的提示必须显示在副屏，
     * 不可使用 Android Toast（Toast 只会落在主屏）。2 秒自动隐藏，连续提示自动续期。
     */
    private var tipHideRunnable: Runnable? = null

    fun showTip(msg: String) {
        if (!::binding.isInitialized) return
        binding.tvTip.text = msg
        binding.tvTip.visibility = View.VISIBLE
        tipHideRunnable?.let { binding.tvTip.removeCallbacks(it) }
        val hide = Runnable { binding.tvTip.visibility = View.GONE }
        tipHideRunnable = hide
        binding.tvTip.postDelayed(hide, 2000)
    }

    /**
     * 副屏对决裁决浮层（XML 预置全屏浮层 battle_overlay，已随父布局旋转 180° 匹配 B 读屏方向，
     * 带入场动画，2.5 秒自动隐藏）
     */
    fun showBattleDialog(attacker: ChessPiece, defender: ChessPiece, resultText: String) {
        if (!::binding.isInitialized) return

        // 渲染攻方（如果攻方是副屏玩家 B 则亮明，否则保密）
        if (attacker.owner == Player.B) {
            binding.subFighterSelf.text = attacker.type.displayName
            binding.subFighterSelf.setTextColor(ContextCompat.getColor(context, R.color.white))
            binding.subFighterSelf.setBackgroundResource(R.drawable.piece_b_bg)
        } else {
            binding.subFighterSelf.text = ""
            binding.subFighterSelf.setBackgroundResource(R.drawable.piece_enemy_bg)
        }

        // 渲染防守方（如果防守方是副屏玩家 B 则亮明，否则保密）
        if (defender.owner == Player.B) {
            binding.subFighterEnemy.text = defender.type.displayName
            binding.subFighterEnemy.setTextColor(ContextCompat.getColor(context, R.color.white))
            binding.subFighterEnemy.setBackgroundResource(R.drawable.piece_b_bg)
        } else {
            binding.subFighterEnemy.text = ""
            binding.subFighterEnemy.setBackgroundResource(R.drawable.piece_enemy_bg)
        }
        binding.subBattleResultText.text = resultText

        // 两屏逻辑 density 不同（主屏 2.0 / 副屏约 1.33），按比例放大副屏卡片使其与主屏物理等大
        val scale = mainActivity.resources.displayMetrics.density / context.resources.displayMetrics.density

        binding.battleOverlay.visibility = View.VISIBLE
        BattleAnim.play(binding.battleCard, binding.subFighterSelf, binding.subFighterEnemy, scale)
        binding.battleOverlay.postDelayed({
            binding.battleOverlay.visibility = View.GONE
        }, 2500)
    }

    /**
     * 棋子托盘 RecyclerView 适配器
     */
    private class TrayAdapter(
        private val onSelect: (ChessPiece) -> Unit
    ) : RecyclerView.Adapter<TrayAdapter.ViewHolder>() {

        private var list = listOf<ChessPiece>()
        private var selected: ChessPiece? = null

        fun updateList(newList: List<ChessPiece>, selectedPiece: ChessPiece?) {
            this.list = newList
            this.selected = selectedPiece
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    parent.context.resources.getDimensionPixelSize(R.dimen.piece_tray_height)
                )
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
                setPadding(4, 4, 4, 4)
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val piece = list[position]
            val tv = holder.itemView as TextView
            tv.text = piece.type.displayName

            // 渲染成玩家 B 绿色风格
            if (selected?.id == piece.id) {
                tv.setBackgroundResource(R.drawable.piece_b_selected_bg)
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.white))
            } else {
                tv.setBackgroundResource(R.drawable.piece_b_bg)
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.white))
            }

            tv.setOnClickListener { onSelect(piece) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
