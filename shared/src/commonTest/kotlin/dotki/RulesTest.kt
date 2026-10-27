package dotki

import kotlin.test.*

class RulesTest {
    private val red = Player.RED
    private val blue = Player.BLUE
    private fun stone(x: Int, y: Int, p: Player = red) = Stone(Point(x, y), p)
    private fun position(kind: GameKind, vararg stones: Stone, turn: Player = red) = GameState(kind, stones.toList(), turn = turn)

    @Test fun paperHasA5Dimensions() {
        assertEquals(145, (DOTS_COLUMNS - 1) * 5)
        assertEquals(210, (DOTS_ROWS - 1) * 5)
        val last = Rules.move(GameState(GameKind.DOTS), red, Point(29, 42))
        assertEquals(Point(29, 42), last.lastMove)
        assertFailsWith<InvalidMove> { Rules.move(GameState(GameKind.DOTS), red, Point(30, 42)) }
        assertFailsWith<InvalidMove> { Rules.move(GameState(GameKind.DOTS), red, Point(-1, 0)) }
    }

    @Test fun turnAndOccupancyAreEnforced() {
        val state = Rules.move(GameState(GameKind.FIVE), red, Point(0, 0))
        assertEquals(blue, state.turn)
        assertFailsWith<InvalidMove> { Rules.move(state, red, Point(1, 0)) }
        assertFailsWith<InvalidMove> { Rules.move(state, blue, Point(0, 0)) }
        assertEquals(1, state.stones.size)
    }

    @Test fun fiveWinsInEveryDirectionAndOnNegativeCoordinates() {
        for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
            val stones = (0..3).map { stone(-100 + it * dx, -200 + it * dy) }
            val result = Rules.move(GameState(GameKind.FIVE, stones), red, Point(-100 + 4 * dx, -200 + 4 * dy))
            assertTrue(result.finished)
            assertEquals(red, result.winner)
            assertEquals(5, result.winningLine.size)
            assertFailsWith<InvalidMove> { Rules.move(result, blue, Point(0, 0)) }
        }
    }

    @Test fun gapsAndOpponentsDoNotCount() {
        val state = position(GameKind.FIVE, stone(0, 0), stone(1, 0), stone(2, 0, blue), stone(3, 0), stone(4, 0))
        assertFalse(Rules.move(state, red, Point(5, 0)).finished)
    }

    @Test fun overlineIsAWinWhenGapIsFilled() {
        val state = position(GameKind.FIVE, stone(0, 0), stone(1, 0), stone(3, 0), stone(4, 0), stone(5, 0))
        assertEquals(red, Rules.move(state, red, Point(2, 0)).winner)
    }

    @Test fun infiniteBoardUsesSparseCoordinatesAndDoesNotWrap() {
        val far = Rules.move(GameState(GameKind.FIVE), red, Point(1_000_000_000, -1_000_000_000))
        assertEquals(1, far.stones.size)
        val state = position(GameKind.FIVE, stone(Int.MAX_VALUE, 0), stone(Int.MIN_VALUE, 0), stone(Int.MIN_VALUE + 1, 0), stone(Int.MIN_VALUE + 2, 0))
        assertFalse(Rules.move(state, red, Point(Int.MAX_VALUE - 1, 0)).finished)
    }

    @Test fun diagonalDiamondCapturesAnEnemy() {
        val state = position(GameKind.DOTS, stone(4, 5), stone(5, 4), stone(6, 5), stone(5, 5, blue))
        val next = Rules.move(state, red, Point(5, 6))
        assertEquals(1, next.redScore)
        assertEquals(listOf(Territory(Point(5, 5), red)), next.territories)
    }

    @Test fun openContourAndPaperEdgeDoNotCapture() {
        val state = position(GameKind.DOTS, stone(0, 4), stone(1, 5), stone(0, 5, blue))
        assertEquals(0, Rules.move(state, red, Point(0, 6)).redScore)
        val open = position(GameKind.DOTS, stone(4, 5), stone(5, 4), stone(5, 5, blue))
        assertEquals(0, Rules.move(open, red, Point(6, 5)).redScore)
    }

    @Test fun emptyHouseStaysPlayableButCapturesAnEnteringEnemy() {
        val state = position(GameKind.DOTS, stone(4, 5), stone(5, 4), stone(6, 5))
        val house = Rules.move(state, red, Point(5, 6))
        assertTrue(house.territories.isEmpty())
        val entered = Rules.move(house, blue, Point(5, 5))
        assertEquals(1, entered.redScore)
        assertEquals(red, entered.territories.single().player)
    }

    @Test fun capturedEmptyIntersectionsAreLocked() {
        val wall = buildList {
            for (i in 3..7) { add(stone(i, 3)); add(stone(i, 7)) }
            for (i in 4..6) { add(stone(3, i)); add(stone(7, i)) }
        }.filter { it.point != Point(5, 3) }
        val state = GameState(GameKind.DOTS, wall + stone(5, 5, blue))
        val result = Rules.move(state, red, Point(5, 3))
        assertEquals(1, result.redScore)
        assertFailsWith<InvalidMove> { Rules.move(result, blue, Point(4, 4)) }
        val later = Rules.move(result, blue, Point(20, 20))
        assertEquals(1, later.redScore)
    }

    @Test fun largerCaptureFreesPreviouslyCapturedPoints() {
        val redDiamond = listOf(stone(4, 5), stone(5, 4), stone(6, 5), stone(5, 6))
        val outer = buildList {
            for (i in 2..8) { add(stone(i, 2, blue)); add(stone(i, 8, blue)) }
            for (i in 3..7) { add(stone(2, i, blue)); add(stone(8, i, blue)) }
        }.filter { it.point != Point(5, 2) }
        val state = GameState(GameKind.DOTS, redDiamond + outer + stone(5, 5, blue),
            territories = listOf(Territory(Point(5, 5), red)), turn = blue, redScore = 1)
        val result = Rules.move(state, blue, Point(5, 2))
        assertEquals(0, result.redScore)
        assertEquals(4, result.blueScore)
        assertEquals(blue, result.territories.find { it.point == Point(5, 5) }?.player)
    }

    @Test fun twoPassesFinishDotsAndMoveResetsPassCount() {
        val first = Rules.pass(GameState(GameKind.DOTS), red)
        assertFalse(first.finished)
        assertEquals(0, Rules.move(first, blue, Point(2, 2)).passes)
        val end = Rules.pass(first, blue)
        assertTrue(end.finished); assertNull(end.winner)
        val score = GameState(GameKind.DOTS, redScore = 2, blueScore = 1)
        assertEquals(red, Rules.pass(Rules.pass(score, red), blue).winner)
        assertFailsWith<InvalidMove> { Rules.pass(GameState(GameKind.FIVE), red) }
    }

    @Test fun resignationDoesNotNeedYourTurn() {
        val end = Rules.resign(GameState(GameKind.FIVE), blue)
        assertTrue(end.finished); assertEquals(red, end.winner)
        assertFailsWith<InvalidMove> { Rules.resign(end, red) }
    }

    @Test fun protocolRoundTripsCoordinatesAndCaptures() {
        val message = ServerMessage("state", RoomView("ABCDEF12", GameState(GameKind.DOTS,
            stones = listOf(stone(2, 3)), territories = listOf(Territory(Point(5, 6), blue))), emptyList()))
        assertEquals(message, wireJson.decodeFromString<ServerMessage>(wireJson.encodeToString(message)))
    }
}
