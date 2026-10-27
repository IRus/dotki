package dotki.server

import dotki.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface BotMove {
    data class Place(val point: Point) : BotMove
    data object Pass : BotMove
}

/** Bounded, deterministic tactical search. It uses no network services or credentials. */
object Bot {
    suspend fun choose(state: GameState, level: BotLevel = BotLevel.MEDIUM): BotMove {
        require(!state.finished) { "Cannot search a finished game" }
        return when (state.kind) {
            GameKind.FIVE -> if (level == BotLevel.HARD) FiveHard.choose(state) else five(state)
            GameKind.DOTS -> if (level == BotLevel.HARD) DotsHard.choose(state) else DotsMedium.choose(state)
        }
    }

    fun play(state: GameState, move: BotMove): GameState = when (move) {
        is BotMove.Place -> Rules.move(state, state.turn, move.point)
        BotMove.Pass -> Rules.pass(state, state.turn)
    }

    private fun offset(p: Point, dx: Int, dy: Int): Point? {
        val x = p.x.toLong() + dx
        val y = p.y.toLong() + dy
        return if (x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) Point(x.toInt(), y.toInt()) else null
    }

    private val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    internal const val WIN = 1_000_000_000L

    private suspend fun five(state: GameState): BotMove {
        val stones = state.stones.associate { it.point to it.player }
        if (stones.isEmpty()) return BotMove.Place(Point(0, 0))
        val candidates = linkedSetOf<Point>()
        for (p in stones.keys) {
            currentCoroutineContext().ensureActive()
            for (dx in -2..2) for (dy in -2..2) {
                offset(p, dx, dy)?.takeIf { it !in stones }?.let(candidates::add)
            }
        }
        var best = candidates.first()
        var bestScore = Long.MIN_VALUE
        for (p in candidates) {
            currentCoroutineContext().ensureActive()
            val attack = lineScore(stones, p, state.turn)
            if (attack >= WIN) return BotMove.Place(p)
            val defense = lineScore(stones, p, state.turn.other())
            val score = if (defense >= WIN) WIN * 5 else attack * 10 + defense * 11
            if (score > bestScore) { best = p; bestScore = score }
        }
        return BotMove.Place(best)
    }

    /** Open ends distinguish a live threat from a line blocked by the opponent or integer boundary. */
    internal fun lineScore(stones: Map<Point, Player>, p: Point, player: Player): Long {
        var score = 0L
        for ((dx, dy) in directions) {
            var length = 1
            var open = 0
            for (sign in listOf(-1, 1)) {
                for (distance in 1..5) {
                    val next = offset(p, dx * distance * sign, dy * distance * sign) ?: break
                    if (stones[next] == player) length++
                    else { if (next !in stones) open++; break }
                }
            }
            if (length >= 5) return WIN
            if (open > 0) score += when (length) {
                4 -> if (open == 2) 1_000_000 else 100_000
                3 -> if (open == 2) 20_000 else 1_000
                2 -> if (open == 2) 400 else 50
                else -> 10
            }
            // Also value broken lines (for example XX_X), which contiguous rays miss.
            for (start in -4..0) {
                var own = 0
                var blocked = false
                for (i in start..start + 4) {
                    val q = offset(p, dx * i, dy * i)
                    if (q == null || stones[q] == player.other()) { blocked = true; break }
                    if (q == p || stones[q] == player) own++
                }
                if (!blocked) score += when (own) { 4 -> 2_000; 3 -> 100; 2 -> 10; else -> 0 }
            }
        }
        return score
    }

}
