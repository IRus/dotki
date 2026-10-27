package dotki.server

import dotki.*

internal object FiveHard {
    private data class Option(val point: Point, val attack: Long, val defense: Long) {
        val order get() = if (attack >= Bot.WIN) Bot.WIN * 10 else if (defense >= Bot.WIN) Bot.WIN * 5 else attack * 10 + defense * 11
    }
    private const val MATE = 100_000_000_000L

    suspend fun choose(state: GameState): BotMove {
        if (state.stones.isEmpty()) return BotMove.Place(Point(0, 0))
        val board = state.stones.associate { it.point to it.player }.toMutableMap()
        val budget = SearchBudget(maxNodes = 300_000)
        var best = fallback(board)
        try {
            val candidates = options(board, state.turn, budget)
            val roots = if (candidates.first().attack >= Bot.WIN) candidates.take(1)
                else candidates.filter { it.defense >= Bot.WIN }.ifEmpty { candidates.take(12) }
            best = roots.first().point
            if (roots.first().attack >= Bot.WIN) return BotMove.Place(best)
            for (depth in 2..4) {
                var candidate = best
                var value = -MATE
                for (option in roots.sortedByDescending { it.point == best }) {
                    board[option.point] = state.turn
                    val score = try { -search(board, state.turn.other(), depth - 1, -MATE, -value, budget) }
                    finally { board.remove(option.point) }
                    if (score > value) { value = score; candidate = option.point }
                }
                best = candidate
            }
        } catch (_: SearchExhausted) { /* A completed shallower search is preferable to an incomplete comparison. */ }
        return BotMove.Place(best)
    }

    private suspend fun search(board: MutableMap<Point, Player>, player: Player, depth: Int, alpha: Long, beta: Long, budget: SearchBudget): Long {
        val choices = options(board, player, budget)
        if (choices.first().attack >= Bot.WIN) return MATE - (10 - depth)
        val forced = choices.filter { it.defense >= Bot.WIN }
        if (forced.size >= 2) return -MATE + (11 - depth)
        // Extend forced defenses past the nominal horizon. A static score must not hide a forced loss.
        if (depth <= 0 && (forced.isEmpty() || depth <= -4)) return (choices.maxOf { it.attack } * 10 - choices.maxOf { it.defense } * 11).coerceIn(-MATE / 2, MATE / 2)
        var value = -MATE
        var bound = alpha
        for (option in forced.ifEmpty { choices.take(8) }) {
            board[option.point] = player
            val score = try { -search(board, player.other(), depth - 1, -beta, -bound, budget) }
            finally { board.remove(option.point) }
            value = maxOf(value, score); bound = maxOf(bound, value)
            if (bound >= beta) break
        }
        return value
    }

    private suspend fun options(board: Map<Point, Player>, player: Player, budget: SearchBudget): List<Option> {
        val free = linkedSetOf<Point>()
        for (p in board.keys) for (dx in -2..2) for (dy in -2..2) {
            val x = p.x.toLong() + dx; val y = p.y.toLong() + dy
            if (x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                val point = Point(x.toInt(), y.toInt())
                if (point !in board) free.add(point)
            }
        }
        val result = mutableListOf<Option>()
        for (point in free) {
            budget.visit()
            result.add(Option(point, Bot.lineScore(board, point, player), Bot.lineScore(board, point, player.other())))
        }
        return result.sortedByDescending { it.order }
    }

    private fun fallback(board: Map<Point, Player>): Point {
        for (p in board.keys) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val x = p.x.toLong() + dx; val y = p.y.toLong() + dy
            if (x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                val q = Point(x.toInt(), y.toInt()); if (q !in board) return q
            }
        }
        error("Finite board has no frontier")
    }
}
