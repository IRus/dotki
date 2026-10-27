package dotki.server

import dotki.*

/** Allocation-light search position. Its transitions are differential-tested against Rules. */
internal class DotsPosition(
    val stones: ByteArray,
    val territory: ByteArray,
    val turn: Int,
    val passes: Int,
    val red: Int,
    val blue: Int,
    val finished: Boolean,
) {
    fun legal(i: Int) = !outside[i] && stones[i] == 0.toByte() && territory[i] == 0.toByte()
    fun active(i: Int) = stones[i].toInt().takeIf { it != 0 && territory[i].toInt() != 3 - it } ?: 0
    fun difference(player: Int) = if (player == 1) red - blue else blue - red
    fun forTurn(player: Int) = DotsPosition(stones, territory, player, passes, red, blue, finished)

    /** A four-way chain to the outside cannot be cut by any future enemy contour.
     * Diagonal contact is deliberately excluded: it does not provide a flood-fill escape. */
    val edgeSafe: BooleanArray by lazy(LazyThreadSafetyMode.NONE) {
        val safe = BooleanArray(SIZE)
        val queue = IntArray(SIZE)
        var head = 0
        var tail = 0
        for (i in playable) if (active(i) != 0 && neighbors[i].any { outside[it] }) {
            safe[i] = true
            queue[tail++] = i
        }
        while (head < tail) {
            val i = queue[head++]
            for (n in neighbors[i]) if (!safe[n] && active(n) == active(i)) {
                safe[n] = true
                queue[tail++] = n
            }
        }
        safe
    }

    fun move(i: Int): DotsPosition {
        if (i == PASS) return DotsPosition(stones, territory, 3 - turn, passes + 1, red, blue, passes >= 1)
        check(legal(i))
        val next = stones.copyOf().also { it[i] = turn.toByte() }
        val ownership = territory.copyOf()
        fun capture(player: Int): Boolean {
            val walls = BooleanArray(SIZE) { next[it].toInt() == player && ownership[it].toInt() != 3 - player }
            val visited = BooleanArray(SIZE)
            val queue = IntArray(SIZE)
            var captured = false
            for (start in 0 until SIZE) {
                if (walls[start] || visited[start]) continue
                var head = 0
                var tail = 1
                queue[0] = start; visited[start] = true
                var open = false
                var enemy = false
                while (head < tail) {
                    val p = queue[head++]
                    if (outside[p]) open = true
                    if (next[p].toInt() == 3 - player && ownership[p].toInt() != player) enemy = true
                    for (n in neighbors[p]) if (!walls[n] && !visited[n]) {
                        visited[n] = true; queue[tail++] = n
                    }
                }
                if (!open && enemy) {
                    for (j in 0 until tail) ownership[queue[j]] = player.toByte()
                    captured = true
                }
            }
            return captured
        }
        if (!capture(turn)) capture(3 - turn)
        var r = 0; var b = 0; var free = false
        for (p in playable) {
            if (next[p] == 2.toByte() && ownership[p] == 1.toByte()) r++
            if (next[p] == 1.toByte() && ownership[p] == 2.toByte()) b++
            if (next[p] == 0.toByte() && ownership[p] == 0.toByte()) free = true
        }
        return DotsPosition(next, ownership, 3 - turn, 0, r, b, !free)
    }

    companion object {
        const val HEIGHT = DOTS_ROWS + 2
        const val WIDTH = DOTS_COLUMNS + 2
        const val SIZE = HEIGHT * WIDTH
        const val PASS = -1
        val outside = BooleanArray(SIZE) { it / HEIGHT == 0 || it / HEIGHT == WIDTH - 1 || it % HEIGHT == 0 || it % HEIGHT == HEIGHT - 1 }
        val playable = (0 until SIZE).filter { !outside[it] }.toIntArray()
        val neighbors = Array(SIZE) { i ->
            buildList {
                if (i / HEIGHT > 0) add(i - HEIGHT)
                if (i / HEIGHT < WIDTH - 1) add(i + HEIGHT)
                if (i % HEIGHT > 0) add(i - 1)
                if (i % HEIGHT < HEIGHT - 1) add(i + 1)
            }.toIntArray()
        }
        val around = Array(SIZE) { i ->
            buildList {
                for (dx in -1..1) for (dy in -1..1) {
                    val x = i / HEIGHT + dx; val y = i % HEIGHT + dy
                    if ((dx != 0 || dy != 0) && x in 0 until WIDTH && y in 0 until HEIGHT) add(x * HEIGHT + y)
                }
            }.toIntArray()
        }
        fun index(p: Point) = (p.x + 1) * HEIGHT + p.y + 1
        fun point(i: Int) = Point(i / HEIGHT - 1, i % HEIGHT - 1)
        fun from(state: GameState): DotsPosition {
            val stones = ByteArray(SIZE); val territory = ByteArray(SIZE)
            state.stones.forEach { stones[index(it.point)] = (it.player.ordinal + 1).toByte() }
            state.territories.forEach { territory[index(it.point)] = (it.player.ordinal + 1).toByte() }
            return DotsPosition(stones, territory, state.turn.ordinal + 1, state.passes, state.redScore, state.blueScore, state.finished)
        }
    }
}
