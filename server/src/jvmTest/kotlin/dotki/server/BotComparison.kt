package dotki.server

import dotki.*
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/** Reproducible paired openings, swapped colors. Truncated games are reported, never called wins. */
fun main(args: Array<String>) = runBlocking {
    for (kind in GameKind.entries.filter { args.firstOrNull() in listOf(null, "ALL", it.name) }) for (opening in 0..1) for (hard in Player.entries) {
        var state = GameState(kind)
        val start = if (kind == GameKind.DOTS) Point(14 + opening, 20) else Point(opening, 0)
        state = Rules.move(state, state.turn, start)
        if (opening == 1) {
            val more = if (kind == GameKind.DOTS) listOf(Point(16,21), Point(15,22), Point(17,20)) else listOf(Point(2,0), Point(1,1), Point(0,-1))
            more.forEach { state = Rules.move(state, state.turn, it) }
        }
        val clock = TimeSource.Monotonic.markNow()
        var moves = 0
        while (!state.finished && moves < 120) {
            val level = if (state.turn == hard) BotLevel.HARD else BotLevel.MEDIUM
            state = Bot.play(state, Bot.choose(state, level)); moves++
        }
        val result = if (!state.finished) "unfinished" else if (state.winner == null) "draw" else if (state.winner == hard) "hard wins" else "medium wins"
        println("$kind opening=$opening hard=$hard $result red=${state.redScore} blue=${state.blueScore} moves=$moves ms=${clock.elapsedNow().inWholeMilliseconds}")
    }
}
