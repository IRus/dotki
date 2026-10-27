package dotki.server

import dotki.*
import kotlinx.serialization.Serializable

/** Deliberately excludes connection tokens, IP addresses and raw client messages. */
@Serializable
data class SessionEvent(
    val sessionId: String,
    val occurredAt: Long,
    val type: String,
    val actor: Player? = null,
    val before: GameState? = null,
    val room: RoomView,
    val point: Point? = null,
    val aiDurationMillis: Long? = null,
)

/** record must commit durably before returning; rooms acknowledge changes only afterwards. */
fun interface SessionJournal {
    suspend fun record(event: SessionEvent)

    companion object {
        val None = SessionJournal { }
    }
}

class ArchiveUnavailable : Exception("Session archive unavailable")
