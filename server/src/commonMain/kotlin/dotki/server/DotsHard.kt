package dotki.server

import dotki.*
import kotlin.math.abs

internal object DotsHard {
    private data class Option(val move: Int, val next: DotsPosition, val value: Int)

    suspend fun choose(state: GameState): BotMove {
        val position = DotsPosition.from(state)
        val budget = SearchBudget()
        // A legal fallback exists even if a heavily populated board exhausts the first iteration.
        var best = candidates(position, 1).firstOrNull() ?: DotsPosition.PASS
        try {
            val roots = ordered(position, 128, budget).take(12)
            if (roots.isEmpty()) return BotMove.Pass
            best = roots.first().move
            // Publish only a fully evaluated iteration; partial deeper searches cannot bias the result.
            for (depth in 2..3) {
                var iterationBest = best
                var value = Int.MIN_VALUE
                for (option in roots.sortedByDescending { it.move == best }) {
                    val score = -search(option.next, depth - 1, -100_000_000, -value.coerceAtLeast(-100_000_000), budget)
                    if (score > value) { value = score; iterationBest = option.move }
                }
                best = iterationBest
            }
        } catch (_: SearchExhausted) { /* Keep the last completed iteration. */ }
        return if (best == DotsPosition.PASS) BotMove.Pass else BotMove.Place(DotsPosition.point(best))
    }

    private suspend fun search(p: DotsPosition, depth: Int, alpha: Int, beta: Int, budget: SearchBudget): Int {
        budget.visit()
        if (p.finished || depth == 0) return evaluate(p, p.turn)
        var value = -100_000_000
        var bound = alpha
        for (option in ordered(p, 48, budget).take(if (depth == 1) 6 else 8)) {
            value = maxOf(value, -search(option.next, depth - 1, -beta, -bound, budget))
            bound = maxOf(bound, value)
            if (bound >= beta) break
        }
        return if (value == -100_000_000) evaluate(p, p.turn) else value
    }

    private suspend fun ordered(p: DotsPosition, width: Int, budget: SearchBudget): List<Option> {
        val result = mutableListOf<Option>()
        for (move in candidates(p, width)) {
            budget.visit()
            val next = p.move(move)
            budget.visit()
            val enemy = p.forTurn(3 - p.turn).move(move)
            val prevented = (enemy.difference(3 - p.turn) - p.difference(3 - p.turn)).coerceAtLeast(0)
            result.add(Option(move, next, evaluate(next, p.turn) + prevented * 10_000))
        }
        // Passing is a real alternative, including when the only placements are suicidal.
        val pass = p.move(DotsPosition.PASS)
        result.add(Option(DotsPosition.PASS, pass, evaluate(pass, p.turn) - if (p.passes == 0) 30 else 0))
        return result.sortedByDescending { it.value }
    }

    private fun candidates(p: DotsPosition, width: Int): List<Int> = DotsPosition.playable.asSequence().filter(p::legal)
        .sortedWith(compareByDescending<Int> { i ->
            var own = 0; var enemy = 0
            for (n in DotsPosition.around[i]) {
                if (p.active(n) == p.turn) own++
                else if (p.active(n) != 0 && !p.edgeSafe[n]) enemy++
            }
            // Keep closing moves and blocks ahead of positional moves.
            own * 35 + enemy * 30 + (if (own >= 3) 2_000 else 0) + (if (enemy >= 3) 1_900 else 0)
        }.thenBy { abs(it / DotsPosition.HEIGHT - 16) + abs(it % DotsPosition.HEIGHT - 22) })
        .take(width).toList()

    private fun evaluate(p: DotsPosition, player: Int): Int {
        val diff = p.difference(player)
        if (p.finished) return diff.coerceIn(-1, 1) * 1_000_000 + diff * 10_000
        var shape = 0
        for (i in DotsPosition.playable) {
            val owner = p.active(i)
            if (owner == 0) continue
            var friendly = 0; var hostile = 0; var exits = 0
            for (n in DotsPosition.around[i]) if (p.active(n) == owner) friendly++
            for (n in DotsPosition.neighbors[i]) {
                if (p.active(n) == 3 - owner) hostile++
                if (p.legal(n)) exits++
            }
            // Surrounding an edge-connected group can never capture it. Do not
            // reward attacking it, or waste moves defending its apparent lack of exits.
            val safety = if (p.edgeSafe[i]) 0 else -hostile * hostile * 24 + exits * 3
            val quality = friendly.coerceAtMost(3) * 8 + safety
            shape += if (owner == player) quality else -quality
        }
        return diff * 10_000 + shape.coerceIn(-3_000, 3_000)
    }
}
