package com.huanglong.pad.dualscreen

import com.huanglong.pad.MainActivity
import com.huanglong.pad.game.ChessPiece
import com.huanglong.pad.game.Player
import com.huanglong.pad.game.Position

/**
 * 对局会话单例。
 *
 * 为什么状态不放在 Activity 里：交换屏幕时窗口要迁到另一块 display，迁移方式是
 * 用 `launchDisplayId` 重建同一个 Activity（见 [BattleDisplayActivity.bind]），
 * 旧实例会被 finish 掉。如果状态挂在 Activity 字段上，迁移一次就把整局棋丢了。
 * 所以两块屏的 Activity 各自持有自己的 UI，对局状态始终读这一份单例。
 *
 * 视图里只留 `get()` 引用，不要 `onCreate` 里存一份副本——会话被替换后
 * 缓存的那份会指向旧对象，表现为「状态栏说轮到你、棋盘却判定不是你的回合」。
 */
object GameSession {

    /** 当前存活的玩家 A 窗口（游戏控制中心）。玩家 B 窗口通过它回传交互。 */
    var mainActivity: MainActivity? = null

    /** 会话是否已经建立。换屏迁移会重建 Activity，重建时不得重新发牌。 */
    var initialized = false

    var gameStage: MainActivity.GameStage = MainActivity.GameStage.LAYOUT
    var currentTurn: Player = Player.A
    var isReadyA = false
    var isReadyB = false
    var winner: Player? = null
    var winReason = ""

    /** 棋盘：物理位置 → 棋子。 */
    val boardState = HashMap<Position, ChessPiece>()

    /** 双方尚未布置的棋子。 */
    val trayPiecesA = ArrayList<ChessPiece>()
    val trayPiecesB = ArrayList<ChessPiece>()

    var selectedPieceA: ChessPiece? = null
    var selectedPieceB: ChessPiece? = null
    var selectedCellA: Position? = null
    var selectedCellB: Position? = null

    /** 可行军高亮目标点。 */
    val highlightedTargets = HashSet<Position>()

    var lastMoveFrom: Position? = null
    var lastMoveTo: Position? = null

    /** 司令阵亡后该方军旗强制亮明。 */
    val revealedFlagOwners = HashSet<Player>()

    /** 双边确认重开：已发起重置请求的玩家。 */
    val resetRequests = HashSet<Player>()

    /** 两个玩家各自选择的音效方言索引。 */
    var voiceIndexA = 0
    var voiceIndexB = 0

    /** 会话唯一标识，便于日志对账。 */
    val sessionId: String = java.util.UUID.randomUUID().toString()
}
