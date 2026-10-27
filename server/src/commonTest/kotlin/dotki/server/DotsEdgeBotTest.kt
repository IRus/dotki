package dotki.server

import dotki.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DotsEdgeBotTest {
    @Test fun doesNotStartAnImpossibleAttackOnAnEdgeStone() = runTest {
        for (point in listOf(Point(0, 21), Point(29, 21), Point(14, 0), Point(14, 42))) {
            val state = Rules.move(GameState(GameKind.DOTS), Player.RED, point)
            val move = assertIs<BotMove.Place>(Bot.choose(state, BotLevel.HARD))
            assertFalse(kotlin.math.abs(move.point.x - point.x) <= 1 && kotlin.math.abs(move.point.y - point.y) <= 1,
                "An edge stone cannot be surrounded: $point -> $move")
        }
    }

    @Test fun onlyUnbrokenOrthogonalChainsProvideAnEscapeToTheEdge() {
        val state = GameState(GameKind.DOTS, listOf(
            Stone(Point(0, 10), Player.RED), Stone(Point(1, 10), Player.RED), Stone(Point(2, 10), Player.RED),
            Stone(Point(3, 11), Player.RED), // Diagonal contact is NOT an escape.
            Stone(Point(4, 10), Player.RED), // A free gap may still be closed by the opponent.
            Stone(Point(2, 11), Player.BLUE), // Opponent contact is not a friendly chain.
        ))
        val p = DotsPosition.from(state)
        for (x in 0..2) assertTrue(p.edgeSafe[DotsPosition.index(Point(x, 10))])
        for (point in listOf(Point(3, 11), Point(4, 10), Point(2, 11)))
            assertFalse(p.edgeSafe[DotsPosition.index(point)], "$point must remain capturable")

        // Even if every other intersection becomes an enemy wall, the chain remains open.
        var filled = state.copy(stones = state.stones.filter { it.point.y == 10 && it.point.x <= 2 })
        val target = Point(10, 10)
        filled = filled.copy(stones = filled.stones + DotsPosition.playable.map(DotsPosition::point)
            .filter { it != target && it !in filled.stones.map(Stone::point) }.map { Stone(it, Player.BLUE) }, turn = Player.BLUE)
        assertEquals(0, Rules.move(filled, Player.BLUE, target).blueScore)
    }

    @Test fun doesNotChaseAnInteriorGroupConnectedToTheEdge() = runTest {
        var state = GameState(GameKind.DOTS)
        for (x in 0..4) {
            state = Rules.move(state, Player.RED, Point(x, 21))
            if (x < 4) state = Rules.pass(state, Player.BLUE)
        }
        val move = assertIs<BotMove.Place>(Bot.choose(state, BotLevel.HARD))
        assertFalse(move.point.x <= 5 && move.point.y in 20..22,
            "The interior end of an unbroken edge chain is also impossible to capture: $move")
    }

    @Test fun stillCapturesAnInteriorStoneUsingAContourOnTheEdge() = runTest {
        for (target in listOf(Point(1, 21), Point(28, 21), Point(14, 1), Point(14, 41))) {
            val ring = listOf(Point(target.x - 1, target.y), Point(target.x + 1, target.y),
                Point(target.x, target.y - 1), Point(target.x, target.y + 1))
            val missing = ring.last()
            val state = GameState(GameKind.DOTS, ring.dropLast(1).map { Stone(it, Player.BLUE) } + Stone(target, Player.RED), turn = Player.BLUE)
            val move = Bot.choose(state, BotLevel.HARD)
            assertEquals(BotMove.Place(missing), move)
            assertEquals(1, Bot.play(state, move).blueScore)
        }
    }
}
