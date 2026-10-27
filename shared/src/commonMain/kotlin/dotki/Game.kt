package dotki

/** A5: 29 × 42 complete 5 mm squares, hence 30 × 43 intersections. */
const val DOTS_COLUMNS = 30
const val DOTS_ROWS = 43

class InvalidMove(message: String) : IllegalArgumentException(message)

/** Pure rules; the server alone commits the returned state. */
object Rules {
    fun move(state: GameState, player: Player, point: Point): GameState {
        requireTurn(state, player)
        if (state.stones.any { it.point == point }) throw InvalidMove(if (state.kind == GameKind.DOTS) "Эта точка уже занята" else "Эта клетка уже занята")
        if (state.kind == GameKind.DOTS) {
            if (point.x !in 0 until DOTS_COLUMNS || point.y !in 0 until DOTS_ROWS) {
                throw InvalidMove("Ход за пределами листа")
            }
            if (state.territories.any { it.point == point }) throw InvalidMove("Область уже захвачена")
        }
        val next = state.copy(
            stones = state.stones + Stone(point, player),
            turn = player.other(), lastMove = point, passes = 0, revision = state.revision + 1,
        )
        return when (state.kind) {
            GameKind.FIVE -> five(next, player, point)
            GameKind.DOTS -> dots(next, player)
        }
    }

    fun pass(state: GameState, player: Player): GameState {
        requireTurn(state, player)
        if (state.kind != GameKind.DOTS) throw InvalidMove("В пяти в ряд нельзя пропускать ход")
        val next = state.copy(turn = player.other(), passes = state.passes + 1, revision = state.revision + 1)
        return if (next.passes >= 2) finishDots(next) else next
    }

    fun resign(state: GameState, player: Player): GameState {
        if (state.finished) throw InvalidMove("Партия уже завершена")
        return state.copy(finished = true, winner = player.other(), revision = state.revision + 1)
    }

    private fun requireTurn(state: GameState, player: Player) {
        if (state.finished) throw InvalidMove("Партия уже завершена")
        if (state.turn != player) throw InvalidMove("Сейчас ход соперника")
    }

    private fun five(state: GameState, player: Player, point: Point): GameState {
        val occupied = state.stones.associate { it.point to it.player }
        for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
            fun ray(sign: Int): List<Point> = buildList {
                for (step in 1..4) {
                    // Long arithmetic prevents Int.MIN/MAX_VALUE wrapping into a false line.
                    val x = point.x.toLong() + dx.toLong() * step * sign
                    val y = point.y.toLong() + dy.toLong() * step * sign
                    if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                        y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) break
                    val p = Point(x.toInt(), y.toInt())
                    if (occupied[p] != player) break
                    add(p)
                }
            }
            val line = ray(-1).reversed() + point + ray(1)
            if (line.size >= 5) return state.copy(finished = true, winner = player, winningLine = line)
        }
        return state
    }

    private fun dots(state: GameState, player: Player): GameState {
        val stones = state.stones.associate { it.point to it.player }
        val ownership = state.territories.associate { it.point to it.player }.toMutableMap()
        // A diagonal chain closes a contour. Four-way flood fill is its planar dual.
        // The padded outside ring makes the paper edge NOT a capturing wall.
        fun capture(owner: Player): Boolean {
            val walls = stones.filter { (p, s) -> s == owner && ownership[p] != owner.other() }.keys
            val visited = mutableSetOf<Point>()
            var captured = false
            for (x in -1..DOTS_COLUMNS) for (y in -1..DOTS_ROWS) {
                val start = Point(x, y)
                if (start in walls || !visited.add(start)) continue
                val region = mutableListOf<Point>()
                val queue = ArrayDeque<Point>()
                queue.add(start)
                var outside = false
                while (queue.isNotEmpty()) {
                    val p = queue.removeFirst()
                    region.add(p)
                    if (p.x == -1 || p.x == DOTS_COLUMNS || p.y == -1 || p.y == DOTS_ROWS) outside = true
                    for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val n = Point(p.x + dx, p.y + dy)
                        if (n.x in -1..DOTS_COLUMNS && n.y in -1..DOTS_ROWS && n !in walls && visited.add(n)) {
                            queue.add(n)
                        }
                    }
                }
                if (!outside && region.any { stones[it] == owner.other() && ownership[it] != owner }) {
                    region.forEach { ownership[it] = owner }
                    captured = true
                }
            }
            return captured
        }
        // An empty enclosure is a house. Entering it is captured unless the move itself captures.
        if (!capture(player)) capture(player.other())
        val next = state.copy(
            territories = ownership.map { (p, owner) -> Territory(p, owner) },
            redScore = stones.count { (p, s) -> s == Player.BLUE && ownership[p] == Player.RED },
            blueScore = stones.count { (p, s) -> s == Player.RED && ownership[p] == Player.BLUE },
        )
        return if (stones.size + ownership.keys.count { it !in stones } == DOTS_COLUMNS * DOTS_ROWS) finishDots(next) else next
    }

    private fun finishDots(state: GameState) = state.copy(
        finished = true,
        winner = when {
            state.redScore > state.blueScore -> Player.RED
            state.blueScore > state.redScore -> Player.BLUE
            else -> null
        },
    )
}
