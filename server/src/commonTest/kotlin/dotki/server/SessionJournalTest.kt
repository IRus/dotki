@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dotki.server

import dotki.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlin.test.*

class SessionJournalTest {
    private fun drain(peer: Peer) { while (peer.messages.tryReceive().isSuccess) { } }

    @Test fun recordsBothGamesWithUndoPassesRematchesAndReconnectWithoutTokens() = runTest {
        for (kind in GameKind.entries) {
            val events = mutableListOf<SessionEvent>()
            var time = 0L
            val rooms = Rooms(backgroundScope, now = { time }, elapsedMillis = { time }, journal = SessionJournal { events += it })
            val red = rooms.enter(ClientMessage("create", kind = kind), Peer())
            val welcome = red.peer.messages.receive()
            val blue = rooms.enter(ClientMessage("join", room = red.room), Peer())
            rooms.command(red, ClientMessage("move", x = 3, y = 4, revision = 0))
            rooms.command(red, ClientMessage("undo", revision = 1))
            assertEquals(1, events.last().before!!.stones.size)
            assertTrue(events.last().room.game.stones.isEmpty())
            var revision = 2
            if (kind == GameKind.DOTS) {
                rooms.command(red, ClientMessage("pass", revision = revision++))
                rooms.command(blue, ClientMessage("pass", revision = revision++))
            } else rooms.command(red, ClientMessage("resign", revision = revision++))
            assertTrue(events.last().room.game.finished)
            rooms.command(red, ClientMessage("rematch", revision = revision))
            rooms.command(blue, ClientMessage("rematch", revision = revision))
            assertFalse(events.last().room.game.finished)
            rooms.leave(red)
            val resumed = rooms.enter(ClientMessage("join", room = red.room, token = welcome.token), Peer())
            assertEquals("reconnect", events.last().type)
            rooms.leave(resumed); rooms.leave(blue)
            time = 7 * 60 * 60 * 1000L
            rooms.cleanup()
            assertEquals("expire", events.last().type)
            assertEquals(1, events.map { it.sessionId }.distinct().size)
            assertEquals(listOf("create", "join", "move", "undo"), events.take(4).map { it.type })
            assertFalse(events.joinToString { wireJson.encodeToString(it) }.contains(welcome.token!!))
            assertFailsWith<ClientError> { rooms.enter(ClientMessage("join", room = red.room), Peer()) }
        }
    }

    @Test fun recordsCommittedAiReplyAndCancelledSearchWithActualSearchDuration() = runTest {
        val events = mutableListOf<SessionEvent>()
        var block = false
        val release = CompletableDeferred<Unit>()
        val rooms = Rooms(backgroundScope, elapsedMillis = { testScheduler.currentTime }, think = { _, _ ->
            if (block) release.await() else delay(120)
            BotMove.Place(Point(1, 1))
        }, journal = SessionJournal { events += it })
        val red = rooms.enter(ClientMessage("create", kind = GameKind.FIVE, mode = GameMode.AI, botLevel = BotLevel.HARD), Peer())
        rooms.command(red, ClientMessage("move", x = 0, y = 0, revision = 0))
        advanceTimeBy(500); runCurrent()
        val reply = events.single { it.type == "ai_move" }
        assertEquals(120L, reply.aiDurationMillis)
        assertEquals(Point(1, 1), reply.point)
        assertEquals(1, reply.before!!.revision)
        assertEquals(2, reply.room.game.revision)
        block = true
        rooms.command(red, ClientMessage("move", x = 2, y = 0, revision = 2))
        advanceTimeBy(400); runCurrent()
        rooms.command(red, ClientMessage("undo", revision = 3))
        runCurrent()
        val cancelled = events.single { it.type == "ai_cancelled" }
        assertEquals(50L, cancelled.aiDurationMillis)
        assertEquals(3, cancelled.before!!.revision)
        assertEquals(4, cancelled.room.game.revision)
        assertEquals(1, events.count { it.type == "ai_move" })
    }

    @Test fun failedWriteIsNotAcknowledgedAndFurtherGamesAreRejected() = runTest {
        var fail = false
        val rooms = Rooms(backgroundScope, journal = SessionJournal { if (fail) error("Disk full") })
        val red = rooms.enter(ClientMessage("create"), Peer())
        val blue = rooms.enter(ClientMessage("join", room = red.room), Peer())
        drain(red.peer); drain(blue.peer)
        fail = true
        assertFailsWith<ArchiveUnavailable> { rooms.command(red, ClientMessage("move", x = 3, y = 4, revision = 0)) }
        assertFalse(rooms.healthy)
        assertFalse(red.peer.messages.tryReceive().isSuccess)
        assertFalse(blue.peer.messages.tryReceive().isSuccess)
        assertFailsWith<ClientError> { rooms.enter(ClientMessage("create"), Peer()) }
    }
}
