package dotki.server

import dotki.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Original dots policy, preserved as the medium difficulty baseline. */
internal object DotsMedium {
    suspend fun choose(state: GameState): BotMove {
        val occupied = state.stones.associate { it.point to it.player }
        val territory = state.territories.associate { it.point to it.player }
        val active = occupied.filter { (p, owner) -> territory[p] != owner.other() }
        val player = state.turn
        fun neighborhood(p: Point): Int {
            var own = 0
            var enemy = 0
            for (dx in -1..1) for (dy in -1..1) {
                when (active[Point(p.x + dx, p.y + dy)]) {
                    player -> own++
                    player.other() -> enemy++
                    else -> Unit
                }
            }
            return own * 30 + enemy * 20 + (if (own >= 3) 2_000 else 0) + (if (enemy >= 3) 1_800 else 0)
        }
        val candidates = buildList {
            for (x in 0 until DOTS_COLUMNS) for (y in 0 until DOTS_ROWS) {
                val p = Point(x, y)
                if (p !in occupied && p !in territory) add(p)
            }
        }.sortedWith(compareByDescending<Point> { neighborhood(it) }
            .thenBy { abs(it.x - DOTS_COLUMNS / 2) + abs(it.y - DOTS_ROWS / 2) }).take(96)
        if (candidates.isEmpty()) return BotMove.Pass
        var best: Point? = null
        var bestScore = Long.MIN_VALUE
        var canCapture = false
        for (p in candidates) {
            currentCoroutineContext().ensureActive()
            val next = Rules.move(state, player, p)
            val gain = score(next, player) - score(state, player)
            val loss = score(next, player.other()) - score(state, player.other())
            val trapped = next.territories.any { it.point == p && it.player != player }
            if (gain > 0) canCapture = true
            if (trapped || loss > 0) continue
            val enemyMove = Rules.move(state.copy(turn = player.other()), player.other(), p)
            val blockedCapture = (score(enemyMove, player.other()) - score(state, player.other())).coerceAtLeast(0)
            val value = gain * 1_000_000L + blockedCapture * 500_000L + neighborhood(p)
            if (value > bestScore) { best = p; bestScore = value }
        }
        // Accept the human's proposed ending if there is no immediate capture to make.
        if (state.passes > 0 && !canCapture) return BotMove.Pass
        return best?.let(BotMove::Place) ?: BotMove.Pass
    }


    private fun score(state: GameState, player: Player) = if (player == Player.RED) state.redScore else state.blueScore
}
