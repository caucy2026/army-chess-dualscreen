package com.huanglong.pad.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    @Test
    fun `position reverse is a round trip`() {
        val position = Position(2, 4)
        assertEquals(position, position.reverse().reverse())
        assertEquals(Position(9, 0), position.reverse())
    }

    @Test
    fun `battle resolution covers ranks bombs mines and flag`() {
        assertEquals(BattleResult.ATTACKER_WON, battle(PieceType.COMMANDER, PieceType.CORPS_COMMANDER))
        assertEquals(BattleResult.DEFENDER_WON, battle(PieceType.ENGINEER, PieceType.COMMANDER))
        assertEquals(BattleResult.BOTH_DIED, battle(PieceType.DIVISION_COMMANDER, PieceType.DIVISION_COMMANDER))
        assertEquals(BattleResult.BOTH_DIED, battle(PieceType.BOMB, PieceType.COMMANDER))
        assertEquals(BattleResult.ATTACKER_WON, battle(PieceType.ENGINEER, PieceType.LANDMINE))
        assertEquals(BattleResult.BOTH_DIED, battle(PieceType.COMMANDER, PieceType.LANDMINE))
        assertEquals(BattleResult.FLAG_CAPTURED, battle(PieceType.BOMB, PieceType.FLAG))
    }

    @Test
    fun `immobile enemy and friendly pieces cannot be moved`() {
        val origin = Position(6, 2)
        val target = Position(6, 3)

        assertFalse(GameEngine.isValidMove(origin, target, mapOf(origin to piece(PieceType.FLAG)), Player.A))
        assertFalse(GameEngine.isValidMove(origin, target, mapOf(origin to piece(PieceType.LANDMINE)), Player.A))
        assertFalse(GameEngine.isValidMove(origin, target, mapOf(origin to piece(PieceType.COMMANDER, Player.B)), Player.A))
        assertFalse(
            GameEngine.isValidMove(
                origin,
                target,
                mapOf(origin to piece(PieceType.COMMANDER), target to piece(PieceType.ENGINEER)),
                Player.A
            )
        )
    }

    @Test
    fun `camp diagonal is connected but occupied camp is protected`() {
        val origin = Position(6, 2)
        val camp = Position(7, 1)
        val attacker = piece(PieceType.COMMANDER)

        assertTrue(GameEngine.isRoadConnected(origin, camp))
        assertTrue(GameEngine.isValidMove(origin, camp, mapOf(origin to attacker), Player.A))
        assertFalse(
            GameEngine.isValidMove(
                origin,
                camp,
                mapOf(origin to attacker, camp to piece(PieceType.ENGINEER, Player.B)),
                Player.A
            )
        )
    }

    @Test
    fun `ordinary railway move is blocked by an intermediate piece`() {
        val origin = Position(1, 0)
        val target = Position(1, 4)
        val board = mutableMapOf(origin to piece(PieceType.COMMANDER))

        assertTrue(GameEngine.isValidMove(origin, target, board, Player.A))
        board[Position(1, 2)] = piece(PieceType.ENGINEER, Player.B)
        assertFalse(GameEngine.isValidMove(origin, target, board, Player.A))
    }

    @Test
    fun `engineer can turn on railway while ordinary piece cannot`() {
        val origin = Position(1, 0)
        val target = Position(5, 4)

        assertTrue(
            GameEngine.isValidMove(origin, target, mapOf(origin to piece(PieceType.ENGINEER)), Player.A)
        )
        assertFalse(
            GameEngine.isValidMove(origin, target, mapOf(origin to piece(PieceType.COMMANDER)), Player.A)
        )
    }

    @Test
    fun `river can only be crossed at columns zero two and four`() {
        assertFalse(
            GameEngine.isValidMove(
                Position(5, 1),
                Position(6, 1),
                mapOf(Position(5, 1) to piece(PieceType.COMMANDER)),
                Player.A
            )
        )
        assertTrue(
            GameEngine.isValidMove(
                Position(5, 2),
                Position(6, 2),
                mapOf(Position(5, 2) to piece(PieceType.COMMANDER)),
                Player.A
            )
        )
    }

    @Test
    fun `valid move query agrees with individual validation`() {
        val origin = Position(6, 2)
        val board = mapOf(origin to piece(PieceType.COMMANDER))
        val moves = GameEngine.validMovesFrom(origin, board, Player.A)

        assertTrue(Position(6, 1) in moves)
        assertTrue(Position(7, 1) in moves)
        assertFalse(origin in moves)
        assertTrue(moves.all { GameEngine.isValidMove(origin, it, board, Player.A) })
    }

    @Test
    fun `complete layouts validate symmetrically for both players`() {
        val layoutA = validLayout(Player.A)
        val layoutB = layoutA.mapKeys { (position, _) -> position.reverse() }
            .mapValues { (_, chessPiece) -> chessPiece.copy(owner = Player.B) }

        assertTrue(GameEngine.verifyLayout(layoutA, Player.A))
        assertTrue(GameEngine.verifyLayout(layoutB, Player.B))
        assertTrue(GameEngine.satisfiesAutoConstraints(layoutA, Player.A))
        assertTrue(GameEngine.satisfiesAutoConstraints(layoutB, Player.B))
    }

    @Test
    fun `layout validation rejects camp flag mine and bomb violations`() {
        assertFalse(swapTypes(validLayout(), Position(11, 3), Position(6, 0)).let {
            GameEngine.verifyLayout(it, Player.A)
        })
        assertFalse(swapTypes(validLayout(), Position(10, 3), Position(6, 0)).let {
            GameEngine.verifyLayout(it, Player.A)
        })
        assertFalse(swapTypes(validLayout(), Position(8, 1), Position(6, 0)).let {
            GameEngine.verifyLayout(it, Player.A)
        })

        val campLayout = validLayout().toMutableMap()
        campLayout[Position(7, 1)] = campLayout.remove(Position(6, 0))!!
        assertFalse(GameEngine.verifyLayout(campLayout, Player.A))
    }

    @Test
    fun `trapped valuable piece is rejected but low rank piece is allowed`() {
        fun trappedBoard(type: PieceType) = mapOf(
            Position(11, 0) to piece(type),
            Position(10, 0) to piece(PieceType.LANDMINE),
            Position(11, 1) to piece(PieceType.FLAG)
        )

        assertTrue(GameEngine.hasForbiddenTrappedPiece(trappedBoard(PieceType.ENGINEER), Player.A))
        assertFalse(GameEngine.hasForbiddenTrappedPiece(trappedBoard(PieceType.BATTALION_COMMANDER), Player.A))
    }

    @Test
    fun `player with only immobile pieces has no valid move`() {
        val immobileBoard = mapOf(
            Position(11, 1) to piece(PieceType.FLAG),
            Position(10, 0) to piece(PieceType.LANDMINE)
        )
        assertFalse(GameEngine.hasAnyValidMove(immobileBoard, Player.A))

        val movableBoard = immobileBoard + (Position(6, 2) to piece(PieceType.COMMANDER))
        assertTrue(GameEngine.hasAnyValidMove(movableBoard, Player.A))
    }

    private fun validLayout(owner: Player = Player.A): Map<Position, ChessPiece> {
        val positions = buildList {
            for (row in 6..11) {
                for (col in 0 until GameEngine.COLS) {
                    val position = Position(row, col)
                    if (!GameEngine.isCamp(position)) add(position)
                }
            }
        }
        val types = listOf(
            PieceType.ENGINEER, PieceType.COMPANY_COMMANDER, PieceType.PLATOON_COMMANDER,
            PieceType.COMPANY_COMMANDER, PieceType.ENGINEER,
            PieceType.COMMANDER, PieceType.REGIMENT_COMMANDER, PieceType.BRIGADE_COMMANDER,
            PieceType.DIVISION_COMMANDER, PieceType.BOMB, PieceType.BOMB, PieceType.REGIMENT_COMMANDER,
            PieceType.BRIGADE_COMMANDER, PieceType.CORPS_COMMANDER, PieceType.DIVISION_COMMANDER,
            PieceType.COMPANY_COMMANDER, PieceType.BATTALION_COMMANDER, PieceType.PLATOON_COMMANDER,
            PieceType.LANDMINE, PieceType.LANDMINE,
            PieceType.ENGINEER, PieceType.BATTALION_COMMANDER, PieceType.LANDMINE,
            PieceType.FLAG, PieceType.PLATOON_COMMANDER
        )
        return positions.indices.associate { index ->
            positions[index] to piece(types[index], owner, "piece-$index")
        }
    }

    private fun swapTypes(
        layout: Map<Position, ChessPiece>,
        first: Position,
        second: Position
    ): Map<Position, ChessPiece> {
        val result = layout.toMutableMap()
        val firstPiece = result.getValue(first)
        val secondPiece = result.getValue(second)
        result[first] = firstPiece.copy(type = secondPiece.type)
        result[second] = secondPiece.copy(type = firstPiece.type)
        return result
    }

    private fun battle(attacker: PieceType, defender: PieceType): BattleResult =
        GameEngine.resolveBattle(piece(attacker), piece(defender, Player.B))

    private fun piece(
        type: PieceType,
        owner: Player = Player.A,
        id: String = "${owner.name}-${type.name}"
    ) = ChessPiece(id, type, owner)
}
