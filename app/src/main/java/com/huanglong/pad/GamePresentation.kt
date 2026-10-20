package com.huanglong.pad

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import com.huanglong.pad.databinding.PresentationGameBinding
import com.huanglong.pad.dualscreen.BattleDisplayActivity
import com.huanglong.pad.dualscreen.GameSession
import com.huanglong.pad.game.*

/**
 * 玩家 B 的窗口。
 *
 * 从 `Presentation` 改成真正的 Activity：交换屏幕时玩家 B 要落到 display 0，
 * 而 `Presentation` 不能投默认屏，只有 Activity 能靠 `launchDisplayId` 迁过去。
 * 类名保留历史名称，但它已不是 android.app.Presentation。
 *
 * 对局状态一律读 [GameSession]，不在本类缓存副本——窗口迁移会重建 Activity。
 */
class GamePresentation : BattleDisplayActivity() {

    override val battlePlayer: Player = Player.B

    private lateinit var binding: PresentationGameBinding
    private val cells = HashMap<Position, BoardCellViews>()
    private var trayAdapter: PieceTrayAdapter? = null
    private var battleHideRunnable: Runnable? = null

    /** 玩家 A 窗口（游戏控制中心），用于回传交互。 */
    private val host: MainActivity? get() = GameSession.mainActivity

    // 会话属性一律走单例
    private val gameStage: MainActivity.GameStage get() = GameSession.gameStage
    private val currentTurn: Player get() = GameSession.currentTurn
    private val isReadyB: Boolean get() = GameSession.isReadyB
    private val winner: Player? get() = GameSession.winner
    private val winReason: String get() = GameSession.winReason

    companion object {
        var instance: GamePresentation? = null
    }

    override fun onCreate(androidBundle: Bundle?) {
        super.onCreate(androidBundle)
        instance = this
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = PresentationGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 全屏沉浸模式 (隐藏状态栏和导航栏)
        window?.let { win ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
            androidx.core.view.WindowInsetsControllerCompat(win, binding.root).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // 副屏棋盘背景按 B 视角绘制 (B 大本营落在画布下半区)
        binding.boardBackgroundView.perspective = Player.B

        initBoardGrid()
        initTrayRecyclerView()
        setupListeners()
        applyInitialBackground()
        refreshUI()
    }

    override fun onStop() {
        if (::binding.isInitialized) {
            tipHideRunnable?.let { binding.tvTip.removeCallbacks(it) }
            battleHideRunnable?.let { binding.battleOverlay.removeCallbacks(it) }
            binding.subFighterSelf.animate().cancel()
            binding.subFighterEnemy.animate().cancel()
            binding.battleCard.animate().cancel()
        }
        tipHideRunnable = null
        battleHideRunnable = null
        super.onStop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 面板切换/遮罩出现时丢弃旧输入：收起点选与浮层。 */
    override fun cancelPendingInput() {
        if (!::binding.isInitialized) return
        binding.tvTip.visibility = View.GONE
        binding.battleOverlay.visibility = View.GONE
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
                val cell = BoardCellViews(cellView)

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

                cells[logicPos] = cell

                // 点击时将逻辑位置回传给玩家 A 窗口处理
                cellView.setOnClickListener {
                    host?.onCellClicked(logicPos, Player.B)
                }
                grid.addView(cellView)
            }
        }
    }

    /**
     * 初始化备选托盘
     */
    private fun initTrayRecyclerView() {
        binding.rvPieceTray.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(this, 2)
        trayAdapter = PieceTrayAdapter(
            normalBackground = R.drawable.piece_b_bg,
            selectedBackground = R.drawable.piece_b_selected_bg,
            textColor = R.color.piece_text_b
        ) { piece -> host?.onTrayPieceSelected(piece, Player.B) }
        binding.rvPieceTray.adapter = trayAdapter
    }

    // 副屏背景场景循环，与主屏 A 各自独立；启动恢复上次保存的背景。
    private val bgScenes = intArrayOf(
        R.drawable.bg_scene_1, R.drawable.bg_scene_2, R.drawable.bg_scene_3,
        R.drawable.bg_scene_4, R.drawable.bg_scene_5, R.drawable.bg_scene_6,
        R.drawable.bg_scene_7, R.drawable.bg_scene_8, R.drawable.bg_scene_9
    )
    private val bgSceneNames = arrayOf(
        "沙漠迷彩", "丛林迷彩", "蔚蓝海洋", "静谧星空", "春意江南",
        "桃林春晓", "樱花漫舞", "幽谷兰香", "山水田园"
    )
    private val bgPrefs by lazy { getSharedPreferences("game_prefs", Context.MODE_PRIVATE) }
    private var bgSceneIndex = -1

    /** 启动副屏初始背景：优先用上次保存的索引；无效则随机一张兜底并写回。 */
    private fun applyInitialBackground() {
        val saved = bgPrefs.getInt("bg_index_b", -1)
        bgSceneIndex = if (saved in bgScenes.indices) saved else bgScenes.indices.random()
        binding.root.setBackgroundResource(bgScenes[bgSceneIndex])
        bgPrefs.edit().putInt("bg_index_b", bgSceneIndex).apply()
    }

    private fun setupListeners() {
        binding.btnAutoLayout.setOnClickListener { host?.autoLayout(Player.B) }
        binding.btnReady.setOnClickListener { host?.setPlayerReady(Player.B) }
        binding.btnReset.setOnClickListener { host?.requestReset(Player.B) }
        binding.btnChangeBg.setOnClickListener {
            bgSceneIndex = (bgSceneIndex + 1) % bgScenes.size
            binding.root.setBackgroundResource(bgScenes[bgSceneIndex])
            bgPrefs.edit().putInt("bg_index_b", bgSceneIndex).apply()
            showTip("背景：${bgSceneNames[bgSceneIndex]}")
        }
        binding.btnChangeVoice.setOnClickListener { host?.changeVoiceB() }
    }

    fun playMoveAnimation(from: Position, to: Position) {
        if (!::binding.isInitialized) return
        val sourceCell = cells[from]
        val targetCell = cells[to]
        if (sourceCell == null || targetCell == null) return
        val sourceRoot = sourceCell.root as? ViewGroup ?: return
        val tvSource = sourceCell.piece

        binding.boardGridLayout.clipChildren = false
        sourceRoot.clipChildren = false
        sourceRoot.bringToFront()

        val dx = targetCell.root.x - sourceRoot.x
        val dy = targetCell.root.y - sourceRoot.y

        tvSource.animate()
            .translationX(dx)
            .translationY(dy)
            .setDuration(400)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                tvSource.translationX = 0f
                tvSource.translationY = 0f
                binding.boardGridLayout.clipChildren = true
                sourceRoot.clipChildren = true
            }
            .start()
    }

    /**
     * 刷新界面 (由玩家 A 窗口在状态变更时主动调用)
     */
    fun refreshUI() {
        if (!::binding.isInitialized) return

        val stage = gameStage
        val turn = currentTurn
        val readyB = isReadyB

        // 1. 刷新状态栏
        when (stage) {
            MainActivity.GameStage.LAYOUT -> {
                binding.tvStatusText.text =
                    if (readyB) "等待玩家 A 完成布阵..." else "请在下方布防区排兵布阵"
                binding.tvTurnIndicator.text = "布阵阶段"
                binding.btnAutoLayout.visibility = if (readyB) View.GONE else View.VISIBLE
                binding.btnReady.visibility = View.VISIBLE
                binding.btnReady.isEnabled = !readyB
                binding.btnReady.text = if (readyB) "已就绪" else "完成布阵"
                binding.btnChangeVoice.visibility = View.GONE
            }
            MainActivity.GameStage.PLAYING -> {
                binding.tvStatusText.text = "对弈中..."
                binding.tvTurnIndicator.text =
                    if (turn == Player.B) "🟢 轮到您的回合" else "🔵 玩家 A 回合中"
                binding.btnAutoLayout.visibility = View.GONE
                binding.btnReady.visibility = View.GONE
                binding.btnChangeVoice.visibility = View.VISIBLE
            }
            MainActivity.GameStage.FINISHED -> {
                val w = winner
                val reason = winReason
                binding.tvStatusText.text =
                    if (reason.isEmpty()) "游戏结束" else "游戏结束：$reason"
                binding.tvTurnIndicator.text =
                    if (w == Player.B) "🎉 恭喜！您获胜了！" else "❌ 您输了！"
                binding.btnChangeVoice.visibility = View.GONE
            }
        }

        // 2. 刷新待部署托盘 (对弈/结束阶段改为显示游戏规则速查)
        if (stage == MainActivity.GameStage.LAYOUT && !GameSession.isReadyB) {
            binding.tvTrayLabel.visibility = View.VISIBLE
            binding.rvPieceTray.visibility = View.VISIBLE
            binding.svRules.visibility = View.GONE
            trayAdapter?.updateList(GameSession.trayPiecesB, GameSession.selectedPieceB)
        } else {
            binding.tvTrayLabel.visibility = View.GONE
            binding.rvPieceTray.visibility = View.GONE
            binding.svRules.visibility = View.VISIBLE
        }

        // 3. 刷新棋盘单元格与棋子渲染
        val board = GameSession.boardState
        val highlights = GameSession.highlightedTargets
        val selectedCell = GameSession.selectedCellB

        val highlightColor = ContextCompat.getColor(this, R.color.color_b)
        val pieceTextColorA = ContextCompat.getColor(this, R.color.piece_text_a)
        val pieceTextColorB = ContextCompat.getColor(this, R.color.piece_text_b)
        for ((pos, cell) in cells) {
            val tvPiece = cell.piece
            val piece = board[pos]

            // 渲染高亮行军路径
            if (highlights.contains(pos)) {
                cell.targetHighlight.visibility = View.VISIBLE
                cell.targetHighlight.background?.setTint(highlightColor)
            } else {
                cell.targetHighlight.visibility = View.GONE
            }

            // 渲染最后一步高亮
            if (pos == GameSession.lastMoveFrom || pos == GameSession.lastMoveTo) {
                cell.lastMoveHighlight.visibility = View.VISIBLE
            } else {
                cell.lastMoveHighlight.visibility = View.GONE
            }

            // 渲染棋子
            if (piece != null) {
                tvPiece.visibility = View.VISIBLE
                tvPiece.text = piece.type.displayName

                if (piece.owner == Player.B) {
                    tvPiece.setBackgroundResource(R.drawable.piece_b_bg)
                    tvPiece.setTextColor(pieceTextColorB)
                    if (selectedCell == pos) {
                        tvPiece.setBackgroundResource(R.drawable.piece_b_selected_bg)
                    }
                } else {
                    if (stage == MainActivity.GameStage.FINISHED) {
                        tvPiece.setBackgroundResource(R.drawable.piece_a_bg)
                        tvPiece.setTextColor(pieceTextColorA)
                    } else if (piece.type == PieceType.FLAG &&
                        GameSession.revealedFlagOwners.contains(piece.owner)
                    ) {
                        // 司令阵亡，敌方军旗强制亮明
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.setTextColor(pieceTextColorA)
                    } else {
                        // 背面朝上
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.text = ""
                        tvPiece.setTextColor(pieceTextColorA)
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
     * 副屏对决裁决浮层（2.5 秒自动隐藏）
     */
    fun showBattleDialog(attacker: ChessPiece, defender: ChessPiece, resultText: String) {
        if (!::binding.isInitialized) return

        if (attacker.owner == Player.B) {
            binding.subFighterSelf.text = attacker.type.displayName
            binding.subFighterSelf.setTextColor(ContextCompat.getColor(this, R.color.piece_text_b))
            binding.subFighterSelf.setBackgroundResource(R.drawable.piece_b_bg)
        } else {
            binding.subFighterSelf.text = ""
            binding.subFighterSelf.setBackgroundResource(R.drawable.piece_enemy_bg)
        }

        if (defender.owner == Player.B) {
            binding.subFighterEnemy.text = defender.type.displayName
            binding.subFighterEnemy.setTextColor(ContextCompat.getColor(this, R.color.piece_text_b))
            binding.subFighterEnemy.setBackgroundResource(R.drawable.piece_b_bg)
        } else {
            binding.subFighterEnemy.text = ""
            binding.subFighterEnemy.setBackgroundResource(R.drawable.piece_enemy_bg)
        }
        binding.subBattleResultText.text = resultText

        // 两屏逻辑 density 不同（主屏 2.0 / 副屏约 1.33），按比例放大副屏卡片使其与主屏物理等大
        val hostDensity = host?.resources?.displayMetrics?.density
            ?: resources.displayMetrics.density
        val scale = hostDensity / resources.displayMetrics.density

        binding.battleOverlay.visibility = View.VISIBLE
        BattleAnim.play(binding.battleCard, binding.subFighterSelf, binding.subFighterEnemy, scale)
        battleHideRunnable?.let { binding.battleOverlay.removeCallbacks(it) }
        val hide = Runnable { binding.battleOverlay.visibility = View.GONE }
        battleHideRunnable = hide
        binding.battleOverlay.postDelayed(hide, 2500)
    }
}
