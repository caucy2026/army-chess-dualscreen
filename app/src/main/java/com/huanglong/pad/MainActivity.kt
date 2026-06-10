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
            playBattleSound(result)
            
            val textIndex = (0..9).random()
            val resultText = getBattleResultText(result, textIndex)
            
            showBattleDialog(attacker, defender, resultText)
            gamePresentation?.showBattleDialog(attacker, defender, resultText)

            // 语音播报：谁主动出击就播放谁那边的本地预置搞笑音频（0延迟）
            playTtsVoice(result, textIndex)

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
    private fun playTtsVoice(result: BattleResult, textIndex: Int) {
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
            tvAttacker.setTextColor(ContextCompat.getColor(this, R.color.white))
            tvAttacker.setBackgroundResource(R.drawable.piece_a_bg)
        } else {
            tvAttacker.text = ""
            tvAttacker.setBackgroundResource(R.drawable.piece_enemy_bg)
        }

        // 渲染防守方：暗棋裁决不公开身份，仅显示锁 🔒
        if (defender.owner == Player.A) {
            tvDefender.text = defender.type.displayName
            tvDefender.setTextColor(ContextCompat.getColor(this, R.color.white))
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
        
        // 乌龟阵 (重防守)
        val turtle = listOf(
            PLATOON, COMPANY, ENGINEER, PLATOON, COMPANY,
            COMMANDER, REGIMENT, BRIGADE, ENGINEER, PLATOON,
            BOMB,    DIVISION, BATTALION,
            REGIMENT, CORPS, ENGINEER, BOMB,
            DIVISION, LANDMINE, LANDMINE,
            BATTALION, COMPANY, LANDMINE, FLAG, BRIGADE
        )
        formations.add(Pair("乌龟阵 (重防)", turtle))
        
        // 飞花阵 (重进攻) - 司令居中，第一排无军长师长
        val blitz = listOf(
            COMPANY, PLATOON, COMMANDER, PLATOON, COMPANY,
            ENGINEER, REGIMENT, DIVISION, REGIMENT, BRIGADE,
            BOMB, DIVISION, BOMB,
            ENGINEER, CORPS, BATTALION, COMPANY,
            ENGINEER, LANDMINE, LANDMINE,
            BRIGADE, FLAG, LANDMINE, PLATOON, BATTALION
        )
        formations.add(Pair("飞花阵 (重攻)", blitz))
        
        // 均衡阵 (攻守兼备) - 司令在第一排两边，第一排无军长师长
        val balance = listOf(
            COMMANDER, COMPANY, ENGINEER, PLATOON, COMPANY,
            DIVISION, PLATOON, ENGINEER, BRIGADE, REGIMENT,
            BOMB, CORPS, COMPANY,
            BRIGADE, ENGINEER, DIVISION, BOMB,
            BATTALION, LANDMINE, LANDMINE,
            BATTALION, PLATOON, LANDMINE, FLAG, REGIMENT
        )
        formations.add(Pair("均衡阵 (攻守兼备)", balance))

        return formations
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
        if (player == Player.A) {
            for (r in 6..11) { // 6 是前锋界河边，11 是底线
                for (c in 0 until GameEngine.COLS) {
                    val pos = Position(r, c)
                    if (!GameEngine.isCamp(pos)) availablePositions.add(pos)
                }
            }
        } else {
            for (r in 5 downTo 0) { // 5 是前锋界河边，0 是底线
                for (c in 0 until GameEngine.COLS) {
                    val pos = Position(r, c)
                    if (!GameEngine.isCamp(pos)) availablePositions.add(pos)
                }
            }
        }

        // 3. 获取阵法并部署
        val classicFormations = getClassicFormations()
        val chosen = classicFormations.random()
        val formationName = chosen.first
        var typesLayout = chosen.second

        // 50% 概率左右镜像翻转
        if (Math.random() > 0.5) {
            val row0 = typesLayout.subList(0, 5).reversed()
            val row1 = typesLayout.subList(5, 10).reversed()
            val row2 = typesLayout.subList(10, 13).reversed()
            val row3 = typesLayout.subList(13, 17).reversed()
            val row4 = typesLayout.subList(17, 20).reversed()
            val row5 = typesLayout.subList(20, 25).reversed()
            typesLayout = row0 + row1 + row2 + row3 + row4 + row5
        }

        // 4. 将预设的棋子类型与玩家托盘中的真实棋子实例匹配填充
        for (i in 0 until 25) {
            val type = typesLayout[i]
            val piece = myPieces.first { it.type == type }
            piecesMap[availablePositions[i]] = piece
            myPieces.remove(piece)
        }

        // 5. 将布局同步到全局 BoardState
        boardState.putAll(piecesMap)
        if (player == Player.A) trayPiecesA.clear() else trayPiecesB.clear()

        refreshUI()
        val playerStr = if (player == Player.A) "玩家 A" else "玩家 B"
        showToast("$playerStr 启用阵法：$formationName", player)
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
                    tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    if (selectedCellA == pos) {
                        tvPiece.setBackgroundResource(R.drawable.piece_a_selected_bg)
                    }
                } else {
                    // 敌方：暗子
                    if (gameStage == GameStage.FINISHED) {
                        tvPiece.setBackgroundResource(R.drawable.piece_b_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    } else if (piece.type == PieceType.FLAG && revealedFlagOwners.contains(piece.owner)) {
                        // 司令阵亡，敌方军旗强制亮明（保留文字"军旗"，红底天窗）
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.setTextColor(ContextCompat.getColor(this, R.color.white))
                    } else {
                        tvPiece.setBackgroundResource(R.drawable.piece_enemy_bg)
                        tvPiece.text = ""
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