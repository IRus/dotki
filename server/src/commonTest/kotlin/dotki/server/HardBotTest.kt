package dotki.server

import dotki.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class HardBotTest {
    @Test fun searchSimulatorMatchesRulesIncludingHousesAndRecaptures() {
        val random = Random(613)
        repeat(4) {
            var state = GameState(GameKind.DOTS)
            repeat(80) {
                if (!state.finished) {
                    val occupied = state.stones.map { it.point }.toSet() + state.territories.map { it.point }
                    val free = (3..11).flatMap { x -> (3..11).map { y -> Point(x, y) } }.filter { it !in occupied }
                    val move = if (free.isEmpty()) BotMove.Pass else BotMove.Place(free.random(random))
                    val index = if (move is BotMove.Place) DotsPosition.index(move.point) else DotsPosition.PASS
                    val fast = DotsPosition.from(state).move(index)
                    state = Bot.play(state, move)
                    val reference = DotsPosition.from(state)
                    assertContentEquals(reference.stones, fast.stones)
                    assertContentEquals(reference.territory, fast.territory)
                    assertEquals(reference.red, fast.red); assertEquals(reference.blue, fast.blue)
                    assertEquals(reference.finished, fast.finished); assertEquals(reference.turn, fast.turn)
                }
            }
        }
    }

    @Test fun hardDoesNotAcceptALosingPassLikeMedium() = runTest {
        val stones = listOf(Point(4,5), Point(5,4), Point(6,5), Point(5,6)).map { Stone(it, Player.RED) } + Stone(Point(5,5), Player.BLUE)
        val state = GameState(GameKind.DOTS, stones, listOf(Territory(Point(5,5), Player.RED)), turn = Player.BLUE, passes = 1, redScore = 1)
        assertEquals(BotMove.Pass, Bot.choose(state, BotLevel.MEDIUM))
        assertIs<BotMove.Place>(Bot.choose(state, BotLevel.HARD))
    }

    @Test fun hardDefendsTwoPointsInsteadOfTakingOne() = runTest {
        val stones = buildList {
            addAll(listOf(Point(4,5), Point(5,4), Point(6,5)).map { Stone(it, Player.BLUE) })
            add(Stone(Point(5,5), Player.RED))
            for (x in 10..14) for (y in 10..16) {
                if (x == 10 || y == 16 || x == 14 && y >= 12 || y == 10 && x <= 12) add(Stone(Point(x,y), Player.RED))
            }
            add(Stone(Point(11,14), Player.BLUE)); add(Stone(Point(13,14), Player.BLUE))
        }
        val state = GameState(GameKind.DOTS, stones, turn = Player.BLUE)
        val medium = Bot.choose(state, BotLevel.MEDIUM)
        val hard = Bot.choose(state, BotLevel.HARD)
        assertEquals(BotMove.Place(Point(5,6)), medium, "Preserve the original greedy policy")
        assertEquals(BotMove.Place(Point(13,11)), hard, "Avoid losing two points on the reply")
    }

    @Test fun hardFiveTakesWinsAndBlocksGapsInAllDirections() = runTest {
        for ((dx,dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
            val stones = listOf(0,1,3,4).map { Stone(Point(it*dx,it*dy), Player.RED) }
            val state = GameState(GameKind.FIVE, stones, turn = Player.BLUE)
            assertEquals(BotMove.Place(Point(2*dx,2*dy)), Bot.choose(state, BotLevel.HARD))
            val win = state.copy(stones = stones.map { it.copy(player = Player.BLUE) })
            assertEquals(Player.BLUE, Bot.play(win, Bot.choose(win, BotLevel.HARD)).winner)
        }
    }

    @Test fun omittedLevelStillMeansOriginalMedium() = runTest {
        val state = Rules.move(GameState(GameKind.DOTS), Player.RED, Point(14,21))
        assertEquals(DotsMedium.choose(state), Bot.choose(state))
        assertEquals(BotLevel.MEDIUM, wireJson.decodeFromString<ClientMessage>("""{"type":"create"}""").botLevel)
    }
}
