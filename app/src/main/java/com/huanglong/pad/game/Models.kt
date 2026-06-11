package com.huanglong.pad.game

/**
 * 玩家阵营定义
 */
enum class Player {
    A, // 玩家 A (主屏，默认棋盘下方)
    B  // 玩家 B (副屏，默认棋盘下方，坐标翻转)
}

/**
 * 棋格类型定义
 */
enum class CellType {
    STATION, // 普通兵站
    CAMP,    // 行营 (避难所，不可吃子，只能放一个子)
    HQ,      // 大本营 (司令部，军旗防守区；非军旗棋子可正常进出，不锁定)
    RIVER    // 界河 (排 5 与 6 之间)
}

/**
 * 棋子类型及其战斗力定义
 */
enum class PieceType(val displayName: String, val power: Int, val count: Int) {
    COMMANDER("司令", 9, 1),
    CORPS_COMMANDER("军长", 8, 1),
    DIVISION_COMMANDER("师长", 7, 2),
    BRIGADE_COMMANDER("旅长", 6, 2),
    REGIMENT_COMMANDER("团长", 5, 2),
    BATTALION_COMMANDER("营长", 4, 2),
    COMPANY_COMMANDER("连长", 3, 3),
    PLATOON_COMMANDER("排长", 2, 3),
    ENGINEER("工兵", 1, 3),   // 战力最低，但唯一能排雷，并在铁路上拐弯走子
    LANDMINE("地雷", 0, 3),   // 战力为0，不能移动；工兵挖雷活、雷死；其余棋子（含炸弹）碰雷同归于尽
    BOMB("炸弹", 0, 2),       // 战力为0，移动后与任何相碰的子同归于尽
    FLAG("军旗", 0, 1)        // 战力为0，不能移动，被吃则全盘皆输
}

/**
 * 棋子实体数据结构
 */
data class ChessPiece(
    val id: String,
    val type: PieceType,
    val owner: Player
)

/**
 * 棋盘物理位置坐标
 * 在 A 的视角中，Row 6-11 是 A 的阵营，0-5 是 B 的阵营。
 * 在 B 的视角中，Row 6-11 是 B 的阵营，0-5 是 A 的阵营。
 */
data class Position(val row: Int, val col: Int) {
    /**
     * 将本方的视角位置转换为对方视角位置 (180度翻转)
     */
    fun reverse(): Position {
        return Position(11 - row, 4 - col)
    }

    /**
     * 判断位置是否合法
     */
    fun isValid(): Boolean {
        return row in 0..11 && col in 0..4
    }
}
