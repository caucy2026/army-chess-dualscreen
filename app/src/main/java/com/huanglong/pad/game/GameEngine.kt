package com.huanglong.pad.game

import java.util.LinkedList
import java.util.Queue

/**
 * 碰撞战斗裁决结果
 */
enum class BattleResult {
    ATTACKER_WON,   // 攻击方获胜，防守方阵亡，攻击方移入
    DEFENDER_WON,   // 防守方获胜，攻击方阵亡，防守方不动
    BOTH_DIED,      // 同归于尽，双方阵亡
    FLAG_CAPTURED   // 夺取军旗，游戏宣告结束
}

object GameEngine {

    // 棋盘大小
    const val ROWS = 12
    const val COLS = 5

    // 行营坐标集合 (绝对坐标，以 A 视角为准)
    val CAMPS = setOf(
        Position(2, 1), Position(2, 3), Position(3, 2), Position(4, 1), Position(4, 3),
        Position(7, 1), Position(7, 3), Position(8, 2), Position(9, 1), Position(9, 3)
    )

    // 大本营坐标集合
    val HQS = setOf(
        Position(0, 1), Position(0, 3), // B的大本营
        Position(11, 1), Position(11, 3) // A的大本营
    )

    // 铁路线坐标集合
    val RAILWAYS = initRailways()

    private fun initRailways(): Set<Position> {
        val railways = mutableSetOf<Position>()
        // 外围四边铁路 (Row 1 和 10，Col 0 和 4)
        for (c in 0 until COLS) {
            railways.add(Position(1, c))
            railways.add(Position(5, c))
            railways.add(Position(6, c))
            railways.add(Position(10, c))
        }
        for (r in 1..10) {
            railways.add(Position(r, 0))
            railways.add(Position(r, 4))
            railways.add(Position(r, 2)) // 中央铁路线 Col 2
        }
        return railways
    }

    /**
     * 判断两个位置是否在铁路上
     */
    fun isRailway(pos: Position): Boolean = RAILWAYS.contains(pos)

    /**
     * 判断位置是否是行营
     */
    fun isCamp(pos: Position): Boolean = CAMPS.contains(pos)

    /**
     * 判断位置是否是大本营
     */
    fun isHq(pos: Position): Boolean = HQS.contains(pos)

    /**
     * 判断是否是界河过河的合法普通连接 (公路或铁路)
     */
    private fun isRiverCrossValid(from: Position, to: Position): Boolean {
        if ((from.row == 5 && to.row == 6) || (from.row == 6 && to.row == 5)) {
            // 界河仅由三条纵向铁路 (Col 0, 2, 4) 直线跨越；
            // Col 1、Col 3 处为"山界"阻断，且不存在斜线跨河
            return from.col == to.col && (from.col == 0 || from.col == 2 || from.col == 4)
        }
        return true
    }

    /**
     * 判定棋步合法性
     */
    fun isValidMove(
        from: Position,
        to: Position,
        board: Map<Position, ChessPiece>,
        currentPlayer: Player
    ): Boolean {
        // 基础边界检查
        if (!from.isValid() || !to.isValid()) return false
        if (from == to) return false

        val piece = board[from] ?: return false
        // 只能移动己方棋子
        if (piece.owner != currentPlayer) return false

        // 地雷和军旗不能动
        if (piece.type == PieceType.LANDMINE || piece.type == PieceType.FLAG) return false

        // 进入大本营的棋子不能再移动（守家死子）
        if (isHq(from)) return false

        // 终点是己方棋子，不能走（不能踩自己人）
        val targetPiece = board[to]
        if (targetPiece != null && targetPiece.owner == currentPlayer) return false

        // 行营避难：行营内已有棋子时不可进入（无论敌友，行营内棋子不可被攻击）
        if (isCamp(to) && targetPiece != null) return false

        // 铁路行棋逻辑（行营内棋子每次只能走一步，不享受铁路直达）
        if (isRailway(from) && isRailway(to) && !isCamp(from)) {
            // 界河跨越校验
            if (isRiverCrossValid(from, to)) {
                // 如果是工兵，可以拐弯走行
                if (piece.type == PieceType.ENGINEER) {
                    if (canEngineerReach(from, to, board)) return true
                } else {
                    // 普通棋子在铁路上只能直行且不能有子阻挡 (塞车)
                    if (canPieceGoStraightOnRailway(from, to, board)) return true
                }
            }
        }

        // 普通公路走法 (只能移动一步，且必须满足相邻连线)
        if (isRoadConnected(from, to)) {
            return true
        }

        return false
    }

    /**
     * 判断两个点是否通过公路/行营斜线直接相连（一步距离）
     */
    fun isRoadConnected(from: Position, to: Position): Boolean {
        // 界河过河校验
        if (!isRiverCrossValid(from, to)) return false

        val rDiff = Math.abs(from.row - to.row)
        val cDiff = Math.abs(from.col - to.col)

        // 只能走相邻的一格
        if (rDiff > 1 || cDiff > 1) return false

        // 1. 直线相连 (上下或左右相邻，距离为1)
        if ((rDiff == 1 && cDiff == 0) || (rDiff == 0 && cDiff == 1)) {
            return true
        }

        // 2. 斜向相连 (距离为1的对角线)：仅行营是米字八向交叉点，可斜向走子；
        // 大本营无斜线，只能从正前方同列直线（或同排横向）进出
        if (rDiff == 1 && cDiff == 1) {
            if (isCamp(from) || isCamp(to)) {
                return true
            }
        }

        return false
    }

    /**
     * 判断棋子是否可以在铁路上直行无阻挡直达 (普通棋子铁路规则)
     */
    private fun canPieceGoStraightOnRailway(
        from: Position,
        to: Position,
        board: Map<Position, ChessPiece>
    ): Boolean {
        // 必须在同一直线铁路上（同Row或同Col）
        if (from.row != to.row && from.col != to.col) return false

        // 如果在同Col，只有 Col 0, 2, 4 构成从 Row 1..10 的纵向铁路
        if (from.col == to.col) {
            val col = from.col
            if (col != 0 && col != 2 && col != 4) return false
            
            val rStart = Math.min(from.row, to.row)
            val rEnd = Math.max(from.row, to.row)
            
            // 确保中间每一个点都是铁路，且没有子阻挡
            for (r in (rStart + 1) until rEnd) {
                val checkPos = Position(r, col)
                if (!isRailway(checkPos) || board.containsKey(checkPos)) {
                    return false
                }
            }
            return true
        }

        // 如果在同Row，只有 Row 1, 5, 6, 10 构成横向铁路
        if (from.row == to.row) {
            val row = from.row
            if (row != 1 && row != 5 && row != 6 && row != 10) return false
            
            val cStart = Math.min(from.col, to.col)
            val cEnd = Math.max(from.col, to.col)
            
            // 确保中间没有子阻挡
            for (c in (cStart + 1) until cEnd) {
                val checkPos = Position(row, c)
                if (board.containsKey(checkPos)) {
                    return false
                }
            }
            return true
        }

        return false
    }

    /**
     * 工兵通过 BFS 搜索铁路连通性（可沿空铁路拐弯，终点可以有敌人）
     */
    private fun canEngineerReach(
        from: Position,
        to: Position,
        board: Map<Position, ChessPiece>
    ): Boolean {
        val visited = mutableSetOf<Position>()
        val queue: Queue<Position> = LinkedList()

        queue.add(from)
        visited.add(from)

        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: break
            if (curr == to) return true

            // 获取周围四个方向相邻的铁路格
            val dirs = arrayOf(
                Position(curr.row - 1, curr.col),
                Position(curr.row + 1, curr.col),
                Position(curr.row, curr.col - 1),
                Position(curr.row, curr.col + 1)
            )

            for (next in dirs) {
                if (next.isValid() && isRailway(next) && !visited.contains(next) && isRiverCrossValid(curr, next)) {
                    // 如果是终点，直接允许（哪怕上面有子——吃子或占空地）
                    if (next == to) {
                        return true
                    }
                    // 如果不是终点，中途路径必须是空路（没有棋子阻挡）
                    if (!board.containsKey(next)) {
                        visited.add(next)
                        queue.add(next)
                    }
                }
            }
        }
        return false
    }

    /**
     * 吃子（碰子）裁决大小
     */
    fun resolveBattle(attacker: ChessPiece, defender: ChessPiece): BattleResult {
        // 1. 如果防守方是军旗 -> 游戏结束 (夺旗)
        if (defender.type == PieceType.FLAG) {
            return BattleResult.FLAG_CAPTURED
        }

        // 2. 如果碰上炸弹 (不管是攻击方是炸弹还是防守方是炸弹) -> 同归于尽
        if (attacker.type == PieceType.BOMB || defender.type == PieceType.BOMB) {
            return BattleResult.BOTH_DIED
        }

        // 3. 如果防守方是地雷
        if (defender.type == PieceType.LANDMINE) {
            return if (attacker.type == PieceType.ENGINEER) {
                // 工兵排雷，工兵活
                BattleResult.ATTACKER_WON
            } else {
                // 其他子踩雷，同归于尽
                BattleResult.BOTH_DIED
            }
        }

        // 4. 普通棋子大小判定
        return when {
            attacker.type.power > defender.type.power -> BattleResult.ATTACKER_WON
            attacker.type.power < defender.type.power -> BattleResult.DEFENDER_WON
            else -> BattleResult.BOTH_DIED // 战斗力相同，同归于尽
        }
    }

    /**
     * 布阵规则校验
     * A 视角的布阵范围：Row 6..11
     * B 视角的布阵范围：也是 Row 6..11 (在各自视角下，棋盘下半区都是己方布阵区)
     */
    fun verifyLayout(piecesLayout: Map<Position, ChessPiece>, player: Player): Boolean {
        // 获取该玩家的所有布兵信息
        val playerPieces = piecesLayout.filter { it.value.owner == player }

        // 数量必须是 25
        if (playerPieces.size != 25) return false

        // 根据玩家定义合法的逻辑坐标边界
        val validRows = if (player == Player.A) 6..11 else 0..5
        val hqs = if (player == Player.A) {
            listOf(Position(11, 1), Position(11, 3))
        } else {
            listOf(Position(0, 1), Position(0, 3))
        }
        val mineRows = if (player == Player.A) 10..11 else 0..1
        val firstRow = if (player == Player.A) 6 else 5

        for ((pos, piece) in playerPieces) {
            // 棋子布防区必须在己方半区
            if (pos.row !in validRows) return false

            // 行营不能放子
            if (isCamp(pos)) return false

            when (piece.type) {
                PieceType.FLAG -> {
                    // 军旗只能在大本营
                    if (!hqs.contains(pos)) return false
                }
                PieceType.LANDMINE -> {
                    // 地雷只能在最后两排
                    if (pos.row !in mineRows) return false
                }
                PieceType.BOMB -> {
                    // 炸弹不能在最前排
                    if (pos.row == firstRow) return false
                }
                else -> {}
            }
        }

        // 额外确认：大本营中必须且只能有一个被放置军旗
        val hq1Piece = piecesLayout[hqs[0]]
        val hq2Piece = piecesLayout[hqs[1]]
        val flagCount = (if (hq1Piece?.type == PieceType.FLAG) 1 else 0) +
                        (if (hq2Piece?.type == PieceType.FLAG) 1 else 0)
        if (flagCount != 1) return false

        return true
    }
}
