package dotki.server

import dotki.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BotTest {
    private fun stone(x: Int, y: Int, player: Player) = Stone(Point(x, y), player)
    private fun dots(vararg stones: Stone, passes: Int = 0) = GameState(GameKind.DOTS, stones.toList(), turn = Player.BLUE, passes = passes)

    @Test fun takesWinBeforeBlockingOpponent() = runTest {
        val state = GameState(GameKind.FIVE, (0..3).flatMap {
            listOf(stone(it, 0, Player.BLUE), stone(it, 2, Player.RED))
        }, turn = Player.BLUE)
        assertEquals(Player.BLUE, Bot.play(state, Bot.choose(state)).winner)
    }

    @Test fun blocksTheOnlyWinningCellInEveryDirection() = runTest {
        for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
            val stones = (0..3).map { stone(it * dx, it * dy, Player.RED) } + stone(-dx, -dy, Player.BLUE)
            val state = GameState(GameKind.FIVE, stones, turn = Player.BLUE)
            assertEquals(BotMove.Place(Point(4 * dx, 4 * dy)), Bot.choose(state))
        }
    }

    @Test fun blocksAnInternalGapInFourStones() = runTest {
        val state = GameState(GameKind.FIVE, listOf(0, 1, 3, 4).map { stone(it, 0, Player.RED) }, turn = Player.BLUE)
        assertEquals(BotMove.Place(Point(2, 0)), Bot.choose(state))
    }

    @Test fun handlesFarCoordinatesWithoutWrapping() = runTest {
        val state = GameState(GameKind.FIVE, (0..3).map { stone(Int.MAX_VALUE - it, Int.MIN_VALUE, Player.BLUE) }, turn = Player.BLUE)
        val choice = Bot.choose(state)
        assertEquals(BotMove.Place(Point(Int.MAX_VALUE - 4, Int.MIN_VALUE)), choice)
        assertEquals(Player.BLUE, Bot.play(state, choice).winner)
    }

    @Test fun dotsCompletesCaptureEvenAfterHumanPass() = runTest {
        val state = dots(stone(4, 5, Player.BLUE), stone(5, 4, Player.BLUE), stone(6, 5, Player.BLUE), stone(5, 5, Player.RED), passes = 1)
        val move = Bot.choose(state)
        assertEquals(BotMove.Place(Point(5, 6)), move)
        assertEquals(1, Bot.play(state, move).blueScore)
    }

    @Test fun dotsBlocksImmediateCapture() = runTest {
        val state = dots(stone(4, 5, Player.RED), stone(5, 4, Player.RED), stone(6, 5, Player.RED), stone(5, 5, Player.BLUE))
        assertEquals(BotMove.Place(Point(5, 6)), Bot.choose(state))
    }

    @Test fun dotsAvoidsEnteringEnemyHouse() = runTest {
        val state = dots(stone(4, 5, Player.RED), stone(5, 4, Player.RED), stone(6, 5, Player.RED), stone(5, 6, Player.RED))
        val move = assertIs<BotMove.Place>(Bot.choose(state))
        assertNotEquals(Point(5, 5), move.point)
        val result = Bot.play(state, move)
        assertEquals(0, result.redScore)
    }

    @Test fun dotsPassesWhenOnlyAvailablePointWouldBeCaptured() = runTest {
        val stones = buildList {
            for (x in 0 until DOTS_COLUMNS) for (y in 0 until DOTS_ROWS) {
                if (x != 5 || y != 5) add(stone(x, y, Player.RED))
            }
        }
        assertEquals(BotMove.Pass, Bot.choose(GameState(GameKind.DOTS, stones, turn = Player.BLUE)))
    }

    @Test fun dotsAcceptsTwoPassEndingWithoutImmediateCapture() = runTest {
        val state = GameState(GameKind.DOTS, turn = Player.BLUE, passes = 1)
        val move = Bot.choose(state)
        assertEquals(BotMove.Pass, move)
        assertTrue(Bot.play(state, move).finished)
    }

    @Test fun choicesAreLegalAndDeterministic() = runTest {
        for (kind in GameKind.entries) {
            val state = Rules.move(GameState(kind), Player.RED, Point(14, 21))
            val first = Bot.choose(state)
            assertEquals(first, Bot.choose(state))
            val next = Bot.play(state, first)
            assertEquals(2, next.revision)
            assertEquals(Player.RED, next.turn)
            assertEquals(2, next.stones.size)
        }
    }

    @Test fun finishedGamesAreNotSearched() = runTest {
        assertFailsWith<IllegalArgumentException> { Bot.choose(GameState(GameKind.FIVE, finished = true)) }
    }
}
