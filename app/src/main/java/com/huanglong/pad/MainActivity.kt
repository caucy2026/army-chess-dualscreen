package com.huanglong.pad

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.provider.Settings
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
    var winReason = ""

    // 标准规则：司令阵亡后该方军旗强制亮明（记录需亮旗的阵营）
    val revealedFlagOwners = HashSet<Player>()

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

    // 双边确认重置：已发起重置请求的玩家集合，A/B 都点击重置后才真正重置
    private val resetRequests = HashSet<Player>()
    private var isAnimating = false
    var lastMoveFrom: Position? = null
    var lastMoveTo: Position? = null

    // 对决音效播放器 (SoundPool + res/raw 音频)
    private var soundPool: SoundPool? = null
    private var sndClash = 0
    private var sndBoom = 0
    private var sndWin = 0

    // 本地裁决搞笑语音音效 ID
    private val sndTtsAttackerWon = mutableListOf<Int>()
    private val sndTtsDefenderWon = mutableListOf<Int>()
    private val sndTtsBothDied = mutableListOf<Int>()
    private val sndTtsFlagCaptured = mutableListOf<Int>()

    // 保存进入应用前的自动旋转状态
    private var originalRotationState: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 双屏防呆：从副屏侧桌面启动时系统会把 Activity 分配到副屏 (Display 2)，
        // 而 Presentation 也固定投向副屏将其覆盖，主屏空白。此处强制迁回主屏 (Display 0) 重启自身。
        @Suppress("DEPRECATION")
        val launchedDisplayId = windowManager.defaultDisplay.displayId
        if (launchedDisplayId != Display.DEFAULT_DISPLAY) {
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = Display.DEFAULT_DISPLAY
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                options.toBundle()
            )
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置全屏沉浸模式 (隐藏状态栏和导航栏)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 关闭系统自动旋转
        try {
            if (Settings.System.canWrite(this)) {
                originalRotationState = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
                if (originalRotationState != 0) {
                    Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 配置主屏棋盘背景为玩家 A (冰蓝) 视角
        binding.boardBackgroundView.perspective = Player.A

        initSound()
        initGameData()
        initBoardGrid()
        initTrayRecyclerView()
        setupListeners()
        applyInitialBackground()
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

        // 还原系统自动旋转状态
        if (originalRotationState != -1) {
            try {
                if (Settings.System.canWrite(this)) {
                    Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, originalRotationState)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
        lastMoveFrom = null
        lastMoveTo = null
        gameStage = GameStage.LAYOUT
        currentTurn = Player.A
        isReadyA = false
        isReadyB = false
        winner = null
        winReason = ""
        revealedFlagOwners.clear()

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

    // 主屏背景场景循环：四张场景图轮播（已移除"无背景"档）。
    // 启动恢复上次保存的背景；若无记录/读到无背景则随机选一张兜底，主副屏各自独立持久化。
    private val bgScenes = intArrayOf(
        R.drawable.bg_scene_1, R.drawable.bg_scene_2, R.drawable.bg_scene_3,
        R.drawable.bg_scene_4, R.drawable.bg_scene_5, R.drawable.bg_scene_6,
        R.drawable.bg_scene_7, R.drawable.bg_scene_8, R.drawable.bg_scene_9
    )
    private val bgSceneNames = arrayOf("沙漠迷彩", "丛林迷彩", "蔚蓝海洋", "静谧星空", "春意江南", "桃林春晓", "樱花漫舞", "幽谷兰香", "山水田园")
    private val bgPrefs by lazy { getSharedPreferences("game_prefs", Context.MODE_PRIVATE) }
    private var bgSceneIndex = -1

    /** 启动主屏初始背景：优先用上次保存的索引；无效(无记录/无背景)则随机一张兜底，并写回保存使之固定。 */
    private fun applyInitialBackground() {
        val saved = bgPrefs.getInt("bg_index_a", -1)
        bgSceneIndex = if (saved in bgScenes.indices) saved else bgScenes.indices.random()
        binding.mainRoot.setBackgroundResource(bgScenes[bgSceneIndex])
        bgPrefs.edit().putInt("bg_index_a", bgSceneIndex).apply()
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
        // 重置游戏 (需双边确认)
        binding.btnReset.setOnClickListener {
            requestReset(Player.A)
        }
        // 更换背景（仅切换主屏 A 自己的背景，循环四种场景；并持久化以便下次启动恢复）
        binding.btnChangeBg.setOnClickListener {
            bgSceneIndex = (bgSceneIndex + 1) % bgScenes.size
            binding.mainRoot.setBackgroundResource(bgScenes[bgSceneIndex])
            bgPrefs.edit().putInt("bg_index_a", bgSceneIndex).apply()
            showToast("背景：${bgSceneNames[bgSceneIndex]}", Player.A)
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
            showToast("检测到副屏并成功投送！")
        } else {
            showToast("未检测到外部副屏。您可继续在主屏调试。")
        }
    }

    /**
     * 双边确认重置：单边点击只发起请求，A/B 双方都点击重置后才真正重开棋局，
     * 防止一方误触清掉对局。无副屏调试模式下单边直接重置。
     */
    fun requestReset(requester: Player) {
        if (gamePresentation == null) {
            resetGame()
            return
        }
        resetRequests.add(requester)
        if (resetRequests.size == 2) {
            resetGame()
            return
        }
        val requesterName = if (requester == Player.A) "玩家 A" else "玩家 B"
        val tipForRequester = "已发起重新开始请求，等待对方点击「重新开始」确认"
        val tipForOther = "$requesterName 请求重新开始棋局，点击「重新开始」即同意"
        showToast(if (requester == Player.A) tipForRequester else tipForOther, Player.A)
        gamePresentation?.showTip(if (requester == Player.B) tipForRequester else tipForOther)
    }

    /**
     * 玩家重置游戏
     */
    fun resetGame() {
        resetRequests.clear()
        initGameData()
        refreshUI()
        // 全局消息：双屏同时提示（重置可能由任一方触发）
        showToast("游戏已重新开始，请重新布兵")
        gamePresentation?.showTip("游戏已重新开始，请重新布兵")
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
        if (isAnimating) return
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
            showToast("只能放置在己方大营内！", clicker)
            return
        }

        if (GameEngine.isCamp(logicPos)) {
            showToast("行营内不可布防！", clicker)
            return
        }

        val selectedPiece = if (clicker == Player.A) selectedPieceA else selectedPieceB
        val existingPiece = boardState[logicPos]

        if (selectedPiece != null) {
            // 规则验证：军旗、地雷、炸弹位置限制
            if (selectedPiece.type == PieceType.FLAG && !GameEngine.isHq(logicPos)) {
                showToast("军旗必须放置在大本营！", clicker)
                return
            }
            if (selectedPiece.type == PieceType.LANDMINE) {
                // A的地雷要在 10..11 排；B的地雷要在 0..1 排
                val isMineValid = if (clicker == Player.A) logicPos.row in 10..11 else logicPos.row in 0..1
                if (!isMineValid) {
                    showToast("地雷必须部署在最后两排！", clicker)
                    return
                }
            }
            if (selectedPiece.type == PieceType.BOMB) {
                // A炸弹不能在最前面两排 (6,7)；B炸弹不能在最前面两排 (4,5)
                val isBombInvalid = if (clicker == Player.A) logicPos.row in 6..7 else logicPos.row in 4..5
                if (isBombInvalid) {
                    showToast("炸弹不可部署在最前两排！", clicker)
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
    fun playMoveAnimation(from: Position, to: Position, onComplete: () -> Unit) {
        val sourceCell = cells[from] as? android.view.ViewGroup
        val targetCell = cells[to]
        if (sourceCell == null || targetCell == null) {
            onComplete()
            return
        }
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
                lastMoveFrom = from
                lastMoveTo = to
                onComplete()
            }
            .start()
    }

    private fun handlePlayingClick(pos: Position, clicker: Player) {
        if (currentTurn != clicker) {
            showToast("不是您的回合！", clicker)
            return
        }

        val selectedCell = if (clicker == Player.A) selectedCellA else selectedCellB
        val piece = boardState[pos]

        if (selectedCell == null) {
            // 选中己方要移动的棋子
            if (piece != null && piece.owner == clicker) {
                if (piece.type == PieceType.LANDMINE || piece.type == PieceType.FLAG) {
                    showToast("地雷和军旗不可移动！", clicker)
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
                val sc = selectedCell
                if (sc != null) {
                    isAnimating = true
                    gamePresentation?.playMoveAnimation(sc, pos)
                    playMoveAnimation(sc, pos) {
                        isAnimating = false
                        // 执行动作
                        moveOrAttack(sc, pos)
        
                        // 继续走子视为放弃此前单方发起的重置请求
                        resetRequests.clear()
        
                        // 清理选择和高亮
                        clearActions()
        
                        // 转换回合
                        currentTurn = if (currentTurn == Player.A) Player.B else Player.A
        
                        // 标准规则：轮到的一方若无任何合法走法，则判负
                        if (gameStage == GameStage.PLAYING &&
                            !GameEngine.hasAnyValidMove(boardState, currentTurn)) {
                            gameStage = GameStage.FINISHED
                            winner = if (currentTurn == Player.A) Player.B else Player.A
                            winReason = "${if (currentTurn == Player.A) "玩家 A" else "玩家 B"} 无棋可走"
                        }
                        refreshUI()
                    }
                    return
                }
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
            playBattleSound(result, attacker.owner)
            
            // 玩家A的专属语音/文案在 0..4，玩家B的在 5..9
            val textIndex = if (attacker.owner == Player.A) (0..4).random() else (5..9).random()
            val resultText = getBattleResultText(result, textIndex)
            
            showBattleDialog(attacker, defender, resultText)
            gamePresentation?.showBattleDialog(attacker, defender, resultText)

            // 语音播报：谁主动出击就播放谁那边的本地预置搞笑音频（0延迟）
            playTtsVoice(result, textIndex, attacker.owner)

            boardState.remove(from)
            when (result) {
                BattleResult.ATTACKER_WON -> {
                    // 攻方存活，占领防方格子（全程暗棋，身份不亮明）
                    boardState[to] = attacker
                    onPieceKilled(defender)
                }
                BattleResult.DEFENDER_WON -> {
                    // 防方存活，留守原地（全程暗棋，身份不亮明）
                    onPieceKilled(attacker)
                }
                BattleResult.BOTH_DIED -> {
                    // 同归于尽
                    boardState.remove(to)
                    onPieceKilled(attacker)
                    onPieceKilled(defender)
                }
                BattleResult.FLAG_CAPTURED -> {
                    // 夺旗，宣告胜利
                    boardState.remove(to)
                    gameStage = GameStage.FINISHED
                    winner = attacker.owner
                    winReason = "军旗被夺"
                }
            }
        }
    }

    /**
     * 棋子阵亡结算：司令阵亡时按标准规则强制亮明该方军旗
     */
    private fun onPieceKilled(piece: ChessPiece) {
        if (piece.type == PieceType.COMMANDER) {
            revealedFlagOwners.add(piece.owner)
        }
    }

    /**
     * 交战音效：依据裁决结果播放不同提示音（夺旗胜利音 / 同归于尽双响 / 普通碰子）
     */
    private fun playBattleSound(result: BattleResult, attackerPlayer: Player) {
        val sp = soundPool ?: return
        val id = when (result) {
            BattleResult.FLAG_CAPTURED -> sndWin
            BattleResult.BOTH_DIED -> sndBoom
            else -> sndClash
        }
        if (id != 0) {
            sp.play(id, 1f, 1f, 1, 0, 1f)
        }
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
        for (i in 0 until 10) {
            val idAtt = resources.getIdentifier("tts_attacker_won_$i", "raw", packageName)
            if (idAtt != 0) sndTtsAttackerWon.add(sp.load(this, idAtt, 1))
            val idDef = resources.getIdentifier("tts_defender_won_$i", "raw", packageName)
            if (idDef != 0) sndTtsDefenderWon.add(sp.load(this, idDef, 1))
            val idBoth = resources.getIdentifier("tts_both_died_$i", "raw", packageName)
            if (idBoth != 0) sndTtsBothDied.add(sp.load(this, idBoth, 1))
            val idFlag = resources.getIdentifier("tts_flag_captured_$i", "raw", packageName)
            if (idFlag != 0) sndTtsFlagCaptured.add(sp.load(this, idFlag, 1))
        }
        soundPool = sp
    }

    /**
     * 播放主动出击方视角的本地预置搞笑裁决语音（0延迟）
     */
    private fun playTtsVoice(result: BattleResult, textIndex: Int, attackerPlayer: Player) {
        val sp = soundPool ?: return
        val list = when (result) {
            BattleResult.ATTACKER_WON -> sndTtsAttackerWon
            BattleResult.DEFENDER_WON -> sndTtsDefenderWon
            BattleResult.BOTH_DIED -> sndTtsBothDied
            BattleResult.FLAG_CAPTURED -> sndTtsFlagCaptured
        }
        if (list.isNotEmpty() && textIndex < list.size) {
            val id = list[textIndex]
            if (id != 0) {
                sp.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
    }

    /**
     * 根据观看方视角生成对决裁决文案（主屏 A 视角 / 副屏 B 视角）
     */
    companion object {
        val textAttackerWon = listOf(
            "哈哈，我的子比你大，你被吃啦！", "以卵击石，不自量力！", "太弱了，不堪一击啊！", "碾压局，轻松拿下！", "哎呀，不好意思，把你吃掉了。",
            "兄弟，看清楚军衔再撞好吗？", "大鱼吃小鱼，我吃你！", "哇哦，白给的一个子，谢谢啦！", "就这？还想跟我硬碰硬？", "实力悬殊，我赢麻了！"
        )
        val textDefenderWon = listOf(
            "哎呦喂，自己撞枪口上啦？", "飞蛾扑火，自寻死路！", "防守反击，就是这么轻松！", "撞墙了吧？头破血流了吧！", "真是不长眼啊，直接送人头！",
            "哈哈，这可是铁板一块，撞不破的！", "守株待兔，你果然上钩了！", "感谢老铁送来的棋子！", "想偷袭？没门！我比你大！", "完美防御，反杀成功！"
        )
        val textBothDied = listOf(
            "卧槽，居然同归于尽了！", "啊！一起毁灭吧！", "炸弹！砰的一声，什么都没了！", "玉石俱焚，谁也别想好过！", "双双把家还，这也太惨烈了！",
            "硬碰硬，结果大家都没了！", "旗鼓相当，一换一不亏！", "你不要过来啊！轰！同归于尽！", "这是什么神仙对决，直接双杀！", "得，这下大家都清净了。"
        )
        val textFlagCaptured = listOf(
            "拔得头筹！你的军旗被我扛走啦！", "军旗倒下，游戏结束！你输了！", "哈哈，你的大本营被我端啦！", "夺旗成功！胜利属于我！", "这军旗我就笑纳了，承让承让！",
            "大势已去，你的军旗没保住啊！", "直捣黄龙，军旗到手！", "一招毙命，摘下你的军旗！", "防守太差了吧，军旗就这么被我拿了？", "绝杀！夺取军旗，游戏通关！"
        )
    }

    fun getBattleResultText(result: BattleResult, index: Int): String {
        return when (result) {
            BattleResult.ATTACKER_WON -> textAttackerWon.getOrElse(index) { textAttackerWon.first() }
            BattleResult.DEFENDER_WON -> textDefenderWon.getOrElse(index) { textDefenderWon.first() }
            BattleResult.BOTH_DIED -> textBothDied.getOrElse(index) { textBothDied.first() }
            BattleResult.FLAG_CAPTURED -> textFlagCaptured.getOrElse(index) { textFlagCaptured.first() }
        }
    }

    /**
     * 展示高保真暗棋对决动画对话框 (2.5秒自动消散)
     */
    private fun showBattleDialog(attacker: ChessPiece, defender: ChessPiece, resultText: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battle, null)
        val tvAttacker = dialogView.findViewById<TextView>(R.id.fighter_self)
        val tvDefender = dialogView.findViewById<TextView>(R.id.fighter_enemy)
        val tvResult = dialogView.findViewById<TextView>(R.id.battle_result_text)

        // 渲染攻方（碰子瞬间亮明攻方身份）
        if (attacker.owner == Player.A) {
            tvAttacker.text = attacker.type.displayName
            tvAttacker.setTextColor(ContextCompat.getColor(this, R.color.piece_text_a))
            tvAttacker.setBackgroundResource(R.drawable.piece_a_bg)
        } else {
            tvAttacker.text = ""
            tvAttacker.setBackgroundResource(R.drawable.piece_enemy_bg)
        }

        // 渲染防守方：暗棋裁决不公开身份，仅显示锁 🔒
        if (defender.owner == Player.A) {
            tvDefender.text = defender.type.displayName
            tvDefender.setTextColor(ContextCompat.getColor(this, R.color.piece_text_a))
            tvDefender.setBackgroundResource(R.drawable.piece_a_bg)
        } else {
            tvDefender.text = ""
            tvDefender.setBackgroundResource(R.drawable.piece_enemy_bg)
        }

        tvResult.text = resultText

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        
        dialog.window?.let { window ->
            val width = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 320f, resources.displayMetrics).toInt()
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
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
            showToast("布防阵型不合法！请检查军旗、地雷及炸弹是否正确部署。", player)
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
            // 全局消息：双屏同时提示
            showToast("部署完毕！对决开始，由玩家 A 率先行动！")
            gamePresentation?.showTip("部署完毕！对决开始，由玩家 A 率先行动！")
        }
        refreshUI()
    }

    private fun getClassicFormations(): List<Pair<String, List<PieceType>>> {
        val PLATOON = PieceType.PLATOON_COMMANDER
        val COMPANY = PieceType.COMPANY_COMMANDER
        val BATTALION = PieceType.BATTALION_COMMANDER
        val REGIMENT = PieceType.REGIMENT_COMMANDER
        val BRIGADE = PieceType.BRIGADE_COMMANDER
        val DIVISION = PieceType.DIVISION_COMMANDER
        val CORPS = PieceType.CORPS_COMMANDER
        val COMMANDER = PieceType.COMMANDER
        val ENGINEER = PieceType.ENGINEER
        val BOMB = PieceType.BOMB
        val LANDMINE = PieceType.LANDMINE
        val FLAG = PieceType.FLAG

        val formations = mutableListOf<Pair<String, List<PieceType>>>()
        
        // 阵型按"填充顺序"书写，每行对应棋盘真实可用行（行营已挖空），列数依次 5/3/4/3/5/5：
        // row6 前锋 c0~c4 │ row7 c0,c2,c4 │ row8 c0,c1,c3,c4 │ row9 c0,c2,c4 │ row10 c0~c4 │ row11 底线/大本营 c0~c4
        // 战术约束（预设与随机一致）：司令压最前两排(row6-7)；旅/师/军长禁第一排(row6)。
        // 铁壁阵 (重防)：司令回防 row7 中路，旅/师/军长集结中后排织成铁壁
        val ironwall = listOf(
            COMPANY, PLATOON, ENGINEER, PLATOON, COMPANY,     // row6
            REGIMENT, COMMANDER, REGIMENT,                    // row7
            BRIGADE, BOMB, BOMB, BRIGADE,                     // row8
            DIVISION, CORPS, DIVISION,                        // row9
            COMPANY, BATTALION, ENGINEER, LANDMINE, LANDMINE, // row10
            PLATOON, FLAG, LANDMINE, ENGINEER, BATTALION      // row11
        )
        formations.add(Pair("铁壁阵 (重防)", ironwall))
        
        // 飞花阵 (重攻)：司令压前锋正中 (6,2) 当敢死队，师/旅长跟进二线
        val blitz = listOf(
            COMPANY, PLATOON, COMMANDER, PLATOON, COMPANY,    // row6
            ENGINEER, REGIMENT, ENGINEER,                     // row7
            DIVISION, BOMB, BOMB, DIVISION,                   // row8
            BRIGADE, CORPS, BRIGADE,                          // row9
            REGIMENT, COMPANY, ENGINEER, LANDMINE, LANDMINE,  // row10
            BATTALION, FLAG, LANDMINE, PLATOON, BATTALION     // row11
        )
        formations.add(Pair("飞花阵 (重攻)", blitz))
        
        // 均衡阵 (攻守兼备)：司令 row7 角领衔，高阶子前后分散
        val balance = listOf(
            ENGINEER, COMPANY, PLATOON, COMPANY, ENGINEER,    // row6
            COMMANDER, REGIMENT, BRIGADE,                     // row7
            DIVISION, BOMB, BOMB, REGIMENT,                   // row8
            BRIGADE, CORPS, DIVISION,                         // row9
            COMPANY, BATTALION, PLATOON, LANDMINE, LANDMINE,  // row10
            ENGINEER, BATTALION, LANDMINE, FLAG, PLATOON      // row11
        )
        formations.add(Pair("均衡阵 (攻守兼备)", balance))

        return formations
    }

    /**
     * 一键布防 (Auto Layout) 入口：按该玩家已布子数分三种行为。
     * - 1..24（部分布防）：保留已布子，只把托盘剩余子合法填入空位（增量布防）。
     * - 0 或 25（空盘/满盘）：整盘收回托盘后重新洗牌生成（满盘即"再点一次换阵"）。
     */
    fun autoLayout(player: Player) {
        if (gameStage != GameStage.LAYOUT) return
        val onBoard = boardState.count { it.value.owner == player }
        if (onBoard in 1..24) incrementalLayout(player) else fullAutoLayout(player)
    }

    /** 整盘布阵：先收回该玩家全部子，再 50% 预设 / 50% 随机生成并校验。 */
    private fun fullAutoLayout(player: Player) {
        // 1. 清理已有布防，重新收回托盘
        val keysToRemove = boardState.filter { it.value.owner == player }.keys.toList()
        for (k in keysToRemove) {
            val p = boardState.remove(k)
            if (p != null) {
                if (player == Player.A) trayPiecesA.add(p) else trayPiecesB.add(p)
            }
        }
        val myPieces = if (player == Player.A) trayPiecesA else trayPiecesB

        // 2. 25 个待部署物理格（行优先：前锋排→底线，底线含两个大本营；跳过行营）
        val availablePositions = collectAvailablePositions(player) { true }

        // 3. 反复生成，直到通过 合法性 + 战术约束 + 困死 校验
        var typeLayout: Map<Position, PieceType> = emptyMap()
        var formationName = "随机阵"
        var attempts = 0
        while (attempts < 200) {
            attempts++
            val plan = if (Math.random() < 0.5) curatedTypeLayout(availablePositions)
                       else randomTypeLayout(availablePositions, player) ?: continue
            val probe = plan.first.mapValues { ChessPiece("probe", it.value, player) }
            if (GameEngine.verifyLayout(probe, player) &&
                GameEngine.satisfiesAutoConstraints(probe, player) &&
                !GameEngine.hasForbiddenTrappedPiece(probe, player)) {
                typeLayout = plan.first
                formationName = plan.second
                break
            }
        }

        val playerStr = if (player == Player.A) "玩家 A" else "玩家 B"
        if (typeLayout.isEmpty()) {
            showToast("$playerStr：自动布阵生成失败，请重试", player)
            refreshUI()
            return
        }

        // 4. 用托盘中的真实棋子实例填充方案
        val piecesMap = HashMap<Position, ChessPiece>()
        val pool = myPieces.toMutableList()
        for ((pos, type) in typeLayout) {
            val piece = pool.first { it.type == type }
            piecesMap[pos] = piece
            pool.remove(piece)
        }

        // 5. 同步到全局 BoardState
        boardState.putAll(piecesMap)
        if (player == Player.A) trayPiecesA.clear() else trayPiecesB.clear()

        refreshUI()
        showToast("$playerStr 启用阵法：$formationName", player)
    }

    /** 增量布阵：保留已布子，只把托盘里剩余子合法填入空位；无解则提示并保持原状。 */
    private fun incrementalLayout(player: Player) {
        val tray = if (player == Player.A) trayPiecesA else trayPiecesB
        val occupied = boardState.keys.toSet()
        val freePositions = collectAvailablePositions(player) { it !in occupied }
        val remainingTypes = tray.map { it.type }

        var fill: Map<Position, PieceType>? = null
        var attempts = 0
        while (attempts < 200 && fill == null) {
            attempts++
            val candidate = constrainedFill(freePositions, remainingTypes, player) ?: continue
            // 已布子 + 新填子合并成 probe，整体校验
            val probe = HashMap<Position, ChessPiece>()
            for ((p, pc) in boardState) if (pc.owner == player) probe[p] = pc
            for ((p, t) in candidate) probe[p] = ChessPiece("probe", t, player)
            if (GameEngine.verifyLayout(probe, player) &&
                GameEngine.satisfiesAutoConstraints(probe, player) &&
                !GameEngine.hasForbiddenTrappedPiece(probe, player)) {
                fill = candidate
            }
        }

        val playerStr = if (player == Player.A) "玩家 A" else "玩家 B"
        if (fill == null) {
            showToast("$playerStr：当前布局无法自动补全，请调整后重试", player)
            return
        }

        // 仅填空位，已布子保持不动；从托盘移除对应子
        val pool = tray.toMutableList()
        for ((pos, type) in fill) {
            val piece = pool.first { it.type == type }
            boardState[pos] = piece
            pool.remove(piece)
            tray.remove(piece)
        }
        refreshUI()
        showToast("$playerStr：已为你补全剩余布阵", player)
    }

    /** 收集该玩家可布防的物理格（行优先、跳过行营），再按谓词过滤。 */
    private fun collectAvailablePositions(player: Player, keep: (Position) -> Boolean): ArrayList<Position> {
        val result = ArrayList<Position>()
        val rowOrder = if (player == Player.A) (6..11).toList() else (5 downTo 0).toList()
        for (r in rowOrder) {
            for (c in 0 until GameEngine.COLS) {
                val pos = Position(r, c)
                if (!GameEngine.isCamp(pos) && keep(pos)) result.add(pos)
            }
        }
        return result
    }

    /** 取一套预设阵型，50% 概率按列做真实镜像翻转 (c→4-c)，返回「位置→兵种」方案。 */
    private fun curatedTypeLayout(availablePositions: List<Position>): Pair<Map<Position, PieceType>, String> {
        val (name, types) = getClassicFormations().random()
        var layout = availablePositions.indices.associate { availablePositions[it] to types[it] }
        if (Math.random() < 0.5) {
            // 行营/大本营均左右对称，列翻转后仍合法，且是真正的左右镜像
            layout = layout.mapKeys { Position(it.key.row, GameEngine.COLS - 1 - it.key.col) }
        }
        return layout to name
    }

    /** 受约束随机布阵：全编制 25 子按约束铺满 25 空位；放不下返回 null。 */
    private fun randomTypeLayout(availablePositions: List<Position>, player: Player): Pair<Map<Position, PieceType>, String>? {
        val allPieces = mutableListOf<PieceType>()
        for (t in PieceType.values()) repeat(t.count) { allPieces.add(t) }
        val layout = constrainedFill(availablePositions, allPieces, player) ?: return null
        return layout to "随机阵"
    }

    /**
     * 约束感知填充：把 pieces 按"军旗→地雷→司令(前2排)→旅/师/军长(非第一排)→炸弹(非前2排)→其余随机"
     * 顺序放入 positions；任一类无合法空位则返回 null。positions 与 pieces 数量应相等。
     */
    private fun constrainedFill(positions: List<Position>, pieces: List<PieceType>, player: Player): Map<Position, PieceType>? {
        val freeCells = positions.toMutableList()
        val pool = pieces.toMutableList()
        val layout = HashMap<Position, PieceType>()
        val mineRows = if (player == Player.A) 10..11 else 0..1
        val frontTwoRows = if (player == Player.A) 6..7 else 4..5
        val firstRow = if (player == Player.A) 6 else 5

        // 取若干个满足 candidate 的空位放置该兵种；空位不足则失败
        fun place(type: PieceType, candidate: (Position) -> Boolean): Boolean {
            repeat(pool.count { it == type }) {
                val spot = freeCells.filter(candidate).randomOrNull() ?: return false
                layout[spot] = type
                freeCells.remove(spot)
                pool.remove(type)
            }
            return true
        }

        if (!place(PieceType.FLAG) { GameEngine.isHq(it) }) return null
        if (!place(PieceType.LANDMINE) { it.row in mineRows }) return null
        if (!place(PieceType.COMMANDER) { it.row in frontTwoRows }) return null
        for (t in listOf(PieceType.BRIGADE_COMMANDER, PieceType.DIVISION_COMMANDER, PieceType.CORPS_COMMANDER)) {
            if (!place(t) { it.row != firstRow }) return null
        }
        if (!place(PieceType.BOMB) { it.row !in frontTwoRows }) return null

        // 其余子（团/营/连/排/工）随机铺满剩余空位
        freeCells.shuffle()
        if (pool.size != freeCells.size) return null
        for (i in pool.indices) layout[freeCells[i]] = pool[i]
        return layout
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
                binding.tvStatusText.text = if (winReason.isEmpty()) "游戏结束" else "游戏结束：$winReason"
                binding.tvTurnIndicator.text = if (winner == Player.A) "🎉 恭喜！您获胜了！" else "❌ 您输了！"
            }
        }

        // 2. 刷新主屏待部署棋子托盘 (对弈/结束阶段改为显示游戏规则速查)
        if (gameStage == GameStage.LAYOUT && !isReadyA) {
            binding.tvTrayLabel.visibility = View.VISIBLE
            binding.rvPieceTray.visibility = View.VISIBLE
            binding.svRules.visibility = View.GONE
            trayAdapter?.updateList(trayPiecesA, selectedPieceA)
        } else {
            binding.tvTrayLabel.visibility = View.GONE
            binding.rvPieceTray.visibility = View.GONE
            binding.svRules.visibility = View.VISIBLE
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

            // 渲染最后一步高亮
            val vLastMove = cellView.findViewById<View>(R.id.v_last_move_highlight)
            if (pos == lastMoveFrom || pos == lastMoveTo) {
                vLastMove.visibility = View.VISIBLE
            } else {
                vLastMove.visibility = View.GONE
            }

            // 渲染棋子实体
            if (piece != null) {
                tvPiece.visibility = View.VISIBLE
                tvPiece.text = piece.type.displayName
                
                if (piece.owner == Player.A) {
                    // 己方：明子
                    tvPiece.setBackgroundResource(R.drawable.piece_a_bg)
                    tvPiece.setTextColor(ContextCompat.getColor(this, R.color.piece_text_a))
                    if (selectedCellA == pos) {
                        tvPiece.setBackgroundResource(R.drawable.piece_a_selected_bg)
                    }
                } else {
                    // 敌方：暗子
                    if (gameStage == GameStage.FINISHED) {
                        tvPiece.setBackgroundResource(R.drawable.piece_b_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.piece_text_b))
                    } else if (piece.type == PieceType.FLAG && revealedFlagOwners.contains(piece.owner)) {
                        // 司令阵亡，敌方军旗强制亮明
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.piece_text_b))
                    } else {
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.text = ""
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.piece_text_b))
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
            val customTypeface = androidx.core.content.res.ResourcesCompat.getFont(parent.context, R.font.zcool_kuaile)
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    parent.context.resources.getDimensionPixelSize(R.dimen.piece_tray_height)
                )
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                typeface = customTypeface
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
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.piece_text_a))
            } else {
                tv.setBackgroundResource(R.drawable.piece_a_bg)
                tv.setTextColor(ContextCompat.getColor(tv.context, R.color.piece_text_a))
            }

            tv.setOnClickListener { onSelect(piece) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    /**
     * 按触发方路由提示：B 在副屏操作触发的提示显示到副屏提示条
     * （Android Toast 只会显示在主屏，Presentation 副屏无法显示 Toast）
     */
    private var tipHideRunnable: Runnable? = null

    fun showToast(msg: String, target: Player = Player.A) {
        if (target == Player.B && gamePresentation != null) {
            gamePresentation?.showTip(msg)
            return
        }
        binding.tvTip.text = msg
        binding.tvTip.visibility = View.VISIBLE
        tipHideRunnable?.let { binding.tvTip.removeCallbacks(it) }
        val hide = Runnable { binding.tvTip.visibility = View.GONE }
        tipHideRunnable = hide
        binding.tvTip.postDelayed(hide, 2000)
    }

}