package com.huanglong.pad

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huanglong.pad.databinding.ActivityMainBinding
import com.huanglong.pad.game.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cells = HashMap<Position, View>()
    private var trayAdapter: TrayAdapter? = null

    // 游戏全局会话状态
    enum class GameStage { LAYOUT, PLAYING, FINISHED }
    
    var gameStage = GameStage.LAYOUT
    var currentTurn = Player.A
    var isReadyA = false
    var isReadyB = false
    var winner: Player? = null

    // 棋盘数据结构：物理位置映射到棋子对象
    val boardState = HashMap<Position, ChessPiece>()

    // 托盘中未布置的棋子列表
    val trayPiecesA = ArrayList<ChessPiece>()
    val trayPiecesB = ArrayList<ChessPiece>()

    // 布阵阶段当前选中的托盘棋子
    var selectedPieceA: ChessPiece? = null
    var selectedPieceB: ChessPiece? = null

    // 对弈阶段当前选中的棋盘位置
    var selectedCellA: Position? = null
    var selectedCellB: Position? = null

    // 可行军高亮目标点
    val highlightedTargets = HashSet<Position>()

    // 副屏 Presentation 对象
    private var gamePresentation: GamePresentation? = null

    // 对决音效播放器 (SoundPool + res/raw 音频)
    private var soundPool: SoundPool? = null
    private var sndClash = 0
    private var sndBoom = 0
    private var sndWin = 0

    // 本地裁决搞笑语音音效 ID
    private var sndTtsAttackerWon = 0
    private var sndTtsDefenderWon = 0
    private var sndTtsBothDied = 0
    private var sndTtsFlagCaptured = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 配置主屏棋盘背景为玩家 A (冰蓝) 视角
        binding.boardBackgroundView.perspective = Player.A

        initSound()
        initGameData()
        initBoardGrid()
        initTrayRecyclerView()
        setupListeners()
        refreshUI()
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：自动检测并关联投送副屏，保证两边同时在前台
        setupDoubleScreen()
        refreshUI()
    }

    override fun onStop() {
        super.onStop()
        // 进入后台：立即关闭副屏投送，保证两边同时在后台，防止副屏残留与内存泄漏
        gamePresentation?.dismiss()
        gamePresentation = null
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }

    /**
     * 初始化一整套全新的 50 颗棋子并放入双方托盘中
     */
    private fun initGameData() {
        boardState.clear()
        trayPiecesA.clear()
        trayPiecesB.clear()
        selectedPieceA = null
        selectedPieceB = null
        selectedCellA = null
        selectedCellB = null
        highlightedTargets.clear()
        gameStage = GameStage.LAYOUT
        currentTurn = Player.A
        isReadyA = false
        isReadyB = false
        winner = null

        // 填充玩家 A 的 25 颗备选棋子
        for (type in PieceType.values()) {
            for (i in 0 until type.count) {
                trayPiecesA.add(ChessPiece(UUID.randomUUID().toString(), type, Player.A))
            }
        }

        // 填充玩家 B 的 25 颗备选棋子
        for (type in PieceType.values()) {
            for (i in 0 until type.count) {
                trayPiecesB.add(ChessPiece(UUID.randomUUID().toString(), type, Player.B))
            }
        }
    }

    /**
     * 在主屏上生成 12x5 透明棋格交互网格
     */
    private fun initBoardGrid() {
        val grid = binding.boardGridLayout
        grid.removeAllViews()

        for (r in 0 until GameEngine.ROWS) {
            for (c in 0 until GameEngine.COLS) {
                val cellView = layoutInflater.inflate(R.layout.view_board_cell, grid, false)
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    rowSpec = GridLayout.spec(r, 1f)
                    columnSpec = GridLayout.spec(c, 1f)
                }
                cellView.layoutParams = params

                val logicPos = Position(r, c)
                cells[logicPos] = cellView

                // 点击单元格监听 (主屏以 Player A 视角接收)
                cellView.setOnClickListener {
                    onCellClicked(logicPos, Player.A)
                }
                grid.addView(cellView)
            }
        }
    }

    /**
     * 初始化主屏的棋子供备托盘
     */
    private fun initTrayRecyclerView() {
        binding.rvPieceTray.layoutManager = GridLayoutManager(this, 2)
        trayAdapter = TrayAdapter { piece ->
            onTrayPieceSelected(piece, Player.A)
        }
        binding.rvPieceTray.adapter = trayAdapter
    }

    private fun setupListeners() {
        // 一键布防
        binding.btnAutoLayout.setOnClickListener {
            autoLayout(Player.A)
        }
        // 完成布阵
        binding.btnReady.setOnClickListener {
            setPlayerReady(Player.A)
        }
        // 重置游戏
        binding.btnReset.setOnClickListener {
            resetGame()
        }
    }

    /**
     * 检测外部物理副屏并弹出 Presentation 界面
     */
    private fun setupDoubleScreen() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        
        if (presentationDisplays.isNotEmpty()) {
            val targetDisplay = presentationDisplays[0]
            gamePresentation = GamePresentation(this, targetDisplay).apply {
                show()
            }
            Toast.makeText(this, "检测到副屏并成功投送！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未检测到外部副屏。您可继续在主屏调试。", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 玩家重置游戏
     */
    fun resetGame() {
        initGameData()
        refreshUI()
        Toast.makeText(this, "游戏已重置，请重新布兵", Toast.LENGTH_SHORT).show()
    }

    /**
     * 选中托盘棋子回调
     */
    fun onTrayPieceSelected(piece: ChessPiece, player: Player) {
        if (gameStage != GameStage.LAYOUT) return
        
        if (player == Player.A) {
            selectedPieceA = if (selectedPieceA?.id == piece.id) null else piece
        } else {
            selectedPieceB = if (selectedPieceB?.id == piece.id) null else piece
        }
        refreshUI()
    }

    /**
     * 点击棋盘某个格子的核心处理逻辑
     */
    fun onCellClicked(pos: Position, clicker: Player) {
        if (gameStage == GameStage.LAYOUT) {
            handleLayoutClick(pos, clicker)
        } else if (gameStage == GameStage.PLAYING) {
            handlePlayingClick(pos, clicker)
        }
    }

    /**
     * 1. 阶段：布阵交互处理
     */
    private fun handleLayoutClick(pos: Position, clicker: Player) {
        // 限制：布防期不能放在对方的半区或行营
        // 在各自视角中，必须放在自己的布防区 (逻辑 Row 6-11，若是B则需将其转换为逻辑坐标后判断)
        val logicPos = pos
        val isReady = if (clicker == Player.A) isReadyA else isReadyB
        if (isReady) return // 已就绪则锁定不能修改

        // B的布防区在逻辑坐标 Row 0..5 (B视角下渲染在 6..11)
        val isSelfArea = if (clicker == Player.A) {
            logicPos.row in 6..11
        } else {
            logicPos.row in 0..5
        }
        if (!isSelfArea) {
            Toast.makeText(this, "只能放置在己方大营内！", Toast.LENGTH_SHORT).show()
            return
        }

        if (GameEngine.isCamp(logicPos)) {
            Toast.makeText(this, "行营内不可布防！", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPiece = if (clicker == Player.A) selectedPieceA else selectedPieceB
        val existingPiece = boardState[logicPos]

        if (selectedPiece != null) {
            // 规则验证：军旗、地雷、炸弹位置限制
            if (selectedPiece.type == PieceType.FLAG && !GameEngine.isHq(logicPos)) {
                Toast.makeText(this, "军旗必须放置在大本营！", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedPiece.type == PieceType.LANDMINE) {
                // A的地雷要在 10..11 排；B的地雷要在 0..1 排
                val isMineValid = if (clicker == Player.A) logicPos.row in 10..11 else logicPos.row in 0..1
                if (!isMineValid) {
                    Toast.makeText(this, "地雷必须部署在最后两排！", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            if (selectedPiece.type == PieceType.BOMB) {
                // A炸弹不能在排6；B炸弹不能在排5
                val isBombInvalid = if (clicker == Player.A) logicPos.row == 6 else logicPos.row == 5
                if (isBombInvalid) {
                    Toast.makeText(this, "炸弹不可部署在最前排！", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            // 放置选中的棋子到棋盘
            if (existingPiece != null) {
                // 把原有的子退回到托盘
                if (clicker == Player.A) trayPiecesA.add(existingPiece) else trayPiecesB.add(existingPiece)
            }
            
            boardState[logicPos] = selectedPiece
            if (clicker == Player.A) {
                trayPiecesA.remove(selectedPiece)
                selectedPieceA = null
            } else {
                trayPiecesB.remove(selectedPiece)
                selectedPieceB = null
            }
        } else {
            // 撤回已经部署的棋子
            if (existingPiece != null && existingPiece.owner == clicker) {
                boardState.remove(logicPos)
                if (clicker == Player.A) {
                    trayPiecesA.add(existingPiece)
                } else {
                    trayPiecesB.add(existingPiece)
                }
            }
        }
        refreshUI()
    }

    /**
     * 2. 阶段：行军及碰撞交互处理
     */
    private fun handlePlayingClick(pos: Position, clicker: Player) {
        if (currentTurn != clicker) {
            Toast.makeText(this, "不是您的回合！", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedCell = if (clicker == Player.A) selectedCellA else selectedCellB
        val piece = boardState[pos]

        if (selectedCell == null) {
            // 选中己方要移动的棋子
            if (piece != null && piece.owner == clicker) {
                if (piece.type == PieceType.LANDMINE || piece.type == PieceType.FLAG) {
                    Toast.makeText(this, "地雷和军旗不可移动！", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // 设置当前选中的出发点
                if (clicker == Player.A) selectedCellA = pos else selectedCellB = pos
                
                // 计算该子所有可行的目标落脚点
                highlightedTargets.clear()
                for (r in 0 until GameEngine.ROWS) {
                    for (c in 0 until GameEngine.COLS) {
                        val target = Position(r, c)
                        if (GameEngine.isValidMove(pos, target, boardState, clicker)) {
                            highlightedTargets.add(target)
                        }
                    }
                }
            }
        } else {
            // 点击目标点，开始移动或攻击
            if (highlightedTargets.contains(pos)) {
                // 执行动作
                moveOrAttack(selectedCell, pos)
                
                // 清理选择和高亮
                clearActions()
                
                // 转换回合
                currentTurn = if (currentTurn == Player.A) Player.B else Player.A
            } else {
                // 取消选择或者更换选中子
                clearActions()
                // 如果点的是己方另一个能动的子，直接重新选中
                if (piece != null && piece.owner == clicker &&
                    piece.type != PieceType.LANDMINE && piece.type != PieceType.FLAG) {
                    onCellClicked(pos, clicker)
                    return
                }
            }
        }
        refreshUI()
    }

    private fun clearActions() {
        selectedCellA = null
        selectedCellB = null
        highlightedTargets.clear()
    }

    /**
     * 实执行移动或吃子判定
     */
    private fun moveOrAttack(from: Position, to: Position) {
        val attacker = boardState[from] ?: return
        val defender = boardState[to]

        if (defender == null) {
            // 1. 移入空地
            boardState.remove(from)
            boardState[to] = attacker
        } else {
            // 2. 发生碰撞，进行对决判定
            val result = GameEngine.resolveBattle(attacker, defender)
            
            // 交战音效 + 弹出裁决浮窗（主屏 A 与副屏 B 同步显示）
            playBattleSound(result)
            showBattleDialog(attacker, result)
            gamePresentation?.showBattleDialog(attacker, result)

            // 语音播报：谁主动出击就播放谁那边的本地预置搞笑音频（0延迟）
            playTtsVoice(result)

            boardState.remove(from)
            when (result) {
                BattleResult.ATTACKER_WON -> {
                    // 攻方存活，占领防方格子（全程暗棋，身份不亮明）
                    boardState[to] = attacker
                }
                BattleResult.DEFENDER_WON -> {
                    // 防方存活，留守原地（全程暗棋，身份不亮明）
                }
                BattleResult.BOTH_DIED -> {
                    // 同归于尽
                    boardState.remove(to)
                }
                BattleResult.FLAG_CAPTURED -> {
                    // 夺旗，宣告胜利
                    boardState.remove(to)
                    gameStage = GameStage.FINISHED
                    winner = attacker.owner
                }
            }
        }
    }

    /**
     * 交战音效：依据裁决结果播放不同提示音（夺旗胜利音 / 同归于尽双响 / 普通碰子）
     */
    private fun playBattleSound(result: BattleResult) {
        val sp = soundPool ?: return
        val id = when (result) {
            BattleResult.FLAG_CAPTURED -> sndWin
            BattleResult.BOTH_DIED -> sndBoom
            else -> sndClash
        }
        if (id != 0) sp.play(id, 1f, 1f, 1, 0, 1f)
    }

    /**
     * 初始化对决音效（SoundPool 异步加载 res/raw 三个 wav）
     */
    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        sndClash = sp.load(this, R.raw.battle_clash, 1)
        sndBoom = sp.load(this, R.raw.battle_boom, 1)
        sndWin = sp.load(this, R.raw.battle_win, 1)
        sndTtsAttackerWon = sp.load(this, R.raw.tts_attacker_won, 1)
        sndTtsDefenderWon = sp.load(this, R.raw.tts_defender_won, 1)
        sndTtsBothDied = sp.load(this, R.raw.tts_both_died, 1)
        sndTtsFlagCaptured = sp.load(this, R.raw.tts_flag_captured, 1)
        soundPool = sp
    }

    /**
     * 播放主动出击方视角的本地预置搞笑裁决语音（0延迟）
     */
    private fun playTtsVoice(result: BattleResult) {
        val sp = soundPool ?: return
        val id = when (result) {
            BattleResult.ATTACKER_WON -> sndTtsAttackerWon
            BattleResult.DEFENDER_WON -> sndTtsDefenderWon
            BattleResult.BOTH_DIED -> sndTtsBothDied
            BattleResult.FLAG_CAPTURED -> sndTtsFlagCaptured
        }
        if (id != 0) {
            sp.play(id, 1f, 1f, 1, 0, 1f)
        }
    }

    /**
     * 根据观看方视角生成对决裁决文案（主屏 A 视角 / 副屏 B 视角）
     */
    fun battleResultText(result: BattleResult, attacker: ChessPiece, viewer: Player): String {
        val mine = attacker.owner == viewer
        return when (result) {
            BattleResult.ATTACKER_WON -> if (mine) "一招制敌！对面这颗子被送回老家 👋" else "我方猛士壮烈牺牲，对面有点东西啊 😵"
            BattleResult.DEFENDER_WON -> if (mine) "偷鸡不成蚀把米，白给一子 🐔" else "稳如泰山！对面自己撞上来送了 🙏"
            BattleResult.BOTH_DIED -> "同归于尽！要走一起走，够义气 💥"
            BattleResult.FLAG_CAPTURED -> if (mine) "直捣黄龙，军旗到手，这把赢麻了 🏆" else "老家被人端了！下次一定 😭"
        }
    }

    /**
     * 展示高保真暗棋对决动画对话框 (2.5秒自动消散)
     */
    private fun showBattleDialog(attacker: ChessPiece, result: BattleResult) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battle, null)
        val tvAttacker = dialogView.findViewById<TextView>(R.id.fighter_self)
        val tvDefender = dialogView.findViewById<TextView>(R.id.fighter_enemy)
        val tvResult = dialogView.findViewById<TextView>(R.id.battle_result_text)

        // 渲染攻方（碰子瞬间亮明攻方身份）
        tvAttacker.text = attacker.type.displayName
        tvAttacker.setTextColor(ContextCompat.getColor(this, R.color.white))
        tvAttacker.setBackgroundResource(if (attacker.owner == Player.A) R.drawable.piece_a_bg else R.drawable.piece_b_bg)

        // 渲染防守方：暗棋裁决不公开身份，仅显示锁 🔒
        tvDefender.text = "🔒"
        tvDefender.setBackgroundResource(R.drawable.piece_enemy_bg)

        tvResult.text = battleResultText(result, attacker, Player.A)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        BattleAnim.play(dialogView, tvAttacker, tvDefender)

        // 2.5秒后自动消失
        dialogView.postDelayed({
            if (dialog.isShowing) dialog.dismiss()
        }, 2500)
    }

    /**
     * 设置玩家就绪
     */
    fun setPlayerReady(player: Player) {
        // 布防判定
        val myLayout = boardState.filter { it.value.owner == player }
        if (!GameEngine.verifyLayout(myLayout, player)) {
            Toast.makeText(this, "布防阵型不合法！请检查军旗、地雷及炸弹是否正确部署。", Toast.LENGTH_LONG).show()
            return
        }

        if (player == Player.A) {
            isReadyA = true
        } else {
            isReadyB = true
        }

        // 双方都准备好，正式进入战斗对弈阶段
        if (isReadyA && isReadyB) {
            gameStage = GameStage.PLAYING
            currentTurn = Player.A
            Toast.makeText(this, "部署完毕！对决开始，由玩家 A 率先行动！", Toast.LENGTH_SHORT).show()
        }
        refreshUI()
    }

    /**
     * 一键布防 (Auto Layout) 合法配置
     */
    fun autoLayout(player: Player) {
        if (gameStage != GameStage.LAYOUT) return

        // 1. 清理已有布防，重新收回托盘
        val keysToRemove = boardState.filter { it.value.owner == player }.keys.toList()
        for (k in keysToRemove) {
            val p = boardState.remove(k)
            if (p != null) {
                if (player == Player.A) trayPiecesA.add(p) else trayPiecesB.add(p)
            }
        }

        val myPieces = if (player == Player.A) trayPiecesA else trayPiecesB
        val piecesMap = HashMap<Position, ChessPiece>()

        // 2. 划分所有待部署的 25 个物理格
        val availablePositions = ArrayList<Position>()
        val startRow = if (player == Player.A) 6 else 0
        val endRow = if (player == Player.A) 11 else 5

        for (r in startRow..endRow) {
            for (c in 0 until GameEngine.COLS) {
                val pos = Position(r, c)
                if (!GameEngine.isCamp(pos)) {
                    availablePositions.add(pos)
                }
            }
        }

        // 3. 开始按照规则一键布兵
        // (1) 军旗必须放在两个大本营之一
        val hqs = if (player == Player.A) {
            listOf(Position(11, 1), Position(11, 3))
        } else {
            listOf(Position(0, 1), Position(0, 3))
        }
        val flagPos = hqs.random()
        val flagPiece = myPieces.first { it.type == PieceType.FLAG }
        piecesMap[flagPos] = flagPiece
        availablePositions.remove(flagPos)
        myPieces.remove(flagPiece)

        // (2) 另一个大本营随机放一个普通强子 (除了地雷、炸弹和军旗之外的子)
        val otherHq = hqs.first { it != flagPos }
        val strongPiece = myPieces.first { it.type != PieceType.LANDMINE && it.type != PieceType.BOMB }
        piecesMap[otherHq] = strongPiece
        availablePositions.remove(otherHq)
        myPieces.remove(strongPiece)

        // (3) 地雷必须在最后两排
        val minePositions = availablePositions.filter {
            if (player == Player.A) it.row in 10..11 else it.row in 0..1
        }.shuffled().take(3)
        
        val minePieces = myPieces.filter { it.type == PieceType.LANDMINE }
        for (i in 0 until 3) {
            piecesMap[minePositions[i]] = minePieces[i]
            availablePositions.remove(minePositions[i])
            myPieces.remove(minePieces[i])
        }

        // (4) 炸弹不能在第一排
        val bombPositions = availablePositions.filter {
            if (player == Player.A) it.row != 6 else it.row != 5
        }.shuffled().take(2)
        val bombPieces = myPieces.filter { it.type == PieceType.BOMB }
        for (i in 0 until 2) {
            piecesMap[bombPositions[i]] = bombPieces[i]
            availablePositions.remove(bombPositions[i])
            myPieces.remove(bombPieces[i])
        }

        // (5) 剩余 18 个棋子随机乱序塞入剩余空位置即可
        myPieces.shuffle()
        for (pos in availablePositions.toList()) {
            if (myPieces.isNotEmpty()) {
                val p = myPieces.removeAt(0)
                piecesMap[pos] = p
            }
        }

        // 4. 将布局同步到全局 BoardState
        boardState.putAll(piecesMap)
        if (player == Player.A) trayPiecesA.clear() else trayPiecesB.clear()

        refreshUI()
        Toast.makeText(this, "${if (player == Player.A) "玩家 A" else "玩家 B"} 自动部署就绪", Toast.LENGTH_SHORT).show()
    }

    /**
     * 同步刷新所有屏幕界面
     */
    fun refreshUI() {
        // 1. 刷新主屏状态文字与按钮
        when (gameStage) {
            GameStage.LAYOUT -> {
                binding.tvStatusText.text = if (isReadyA) "等待玩家 B 完成布阵..." else "请在下方布防区排兵布阵"
                binding.tvTurnIndicator.text = "布阵阶段"
                binding.btnAutoLayout.visibility = if (isReadyA) View.GONE else View.VISIBLE
                binding.btnReady.visibility = View.VISIBLE
                binding.btnReady.isEnabled = !isReadyA
                binding.btnReady.text = if (isReadyA) "已就绪" else "完成布阵"
            }
            GameStage.PLAYING -> {
                binding.tvStatusText.text = "对弈中..."
                binding.tvTurnIndicator.text = if (currentTurn == Player.A) "🔵 轮到您的回合" else "🟢 玩家 B 回合中"
                binding.btnAutoLayout.visibility = View.GONE
                binding.btnReady.visibility = View.GONE
            }
            GameStage.FINISHED -> {
                binding.tvStatusText.text = "游戏结束"
                binding.tvTurnIndicator.text = if (winner == Player.A) "🎉 恭喜！您获胜了！" else "❌ 您输了，大本营失守！"
            }
        }

        // 2. 刷新主屏待部署棋子托盘
        if (gameStage == GameStage.LAYOUT) {
            binding.rvPieceTray.visibility = View.VISIBLE
            trayAdapter?.updateList(trayPiecesA, selectedPieceA)
        } else {
            binding.rvPieceTray.visibility = View.GONE
        }

        // 3. 刷新主屏 60 个棋格与棋子渲染
        for ((pos, cellView) in cells) {
            val tvPiece = cellView.findViewById<TextView>(R.id.tv_piece_text)
            val vHighlight = cellView.findViewById<View>(R.id.v_cell_highlight)
            val piece = boardState[pos]

            // 渲染行军可达高亮
            if (highlightedTargets.contains(pos)) {
                vHighlight.visibility = View.VISIBLE
                vHighlight.background?.setTint(ContextCompat.getColor(this, R.color.color_a))
            } else {
                vHighlight.visibility = View.GONE
            }

            // 渲染棋子实体
            if (piece != null) {
                tvPiece.visibility = View.VISIBLE
                tvPiece.text = piece.type.displayName
                
                if (piece.owner == Player.A) {
                    // 己方：明子
                    tvPiece.setBackgroundResource(R.drawable.piece_a_bg)
                    tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    if (selectedCellA == pos) {
                        tvPiece.setBackgroundResource(R.drawable.piece_a_selected_bg)
                    }
                } else {
                    // 敌方：暗子
                    if (gameStage == GameStage.FINISHED) {
                        tvPiece.setBackgroundResource(R.drawable.piece_b_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    } else {
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.text = "🔒"
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    }
                }
            } else {
                tvPiece.visibility = View.GONE
            }
        }

        // 4. 同步命令副屏 Presentation 刷新！
        gamePresentation?.refreshUI()
    }

    /**
     * 托盘 RecyclerView 的适配器
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

            if (selected?.id == piece.id) {
                tv.setBackgroundResource(R.drawable.piece_a_selected_bg)
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.black))
            } else {
                tv.setBackgroundResource(R.drawable.piece_a_bg)
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.white))
            }

            tv.setOnClickListener { onSelect(piece) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
