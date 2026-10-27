package dotki.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource

internal class SearchExhausted : Exception()

/** A hard ceiling plus a time ceiling, checked between simulations. Cancellation is never swallowed. */
internal class SearchBudget(private val maxNodes: Int = 15_000, private val maxMillis: Long = 3_000) {
    private val started = TimeSource.Monotonic.markNow()
    private var nodes = 0
    suspend fun visit() {
        currentCoroutineContext().ensureActive()
        if (++nodes > maxNodes || nodes % 16 == 0 && started.elapsedNow().inWholeMilliseconds >= maxMillis) throw SearchExhausted()
    }
}
