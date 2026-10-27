package dotki.server

import dotki.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RoomsTest {
    private suspend fun create(rooms: Rooms, kind: GameKind = GameKind.FIVE): Pair<Membership, ServerMessage> {
        val peer = Peer()
        val membership = rooms.enter(ClientMessage("create", name = "Первый", kind = kind), peer)
        val welcome = peer.messages.receive()
        peer.messages.receive()
        return membership to welcome
    }
    private suspend fun join(rooms: Rooms, code: String): Membership {
        val peer = Peer()
        val membership = rooms.enter(ClientMessage("join", room = code, name = "Второй"), peer)
        peer.messages.receive(); peer.messages.receive()
        return membership
    }
    private fun move(revision: Int, x: Int = 0) = ClientMessage("move", x = x, y = 0, revision = revision)

    @Test fun requiresOpponentAndLimitsRoomToTwoPlayers() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        assertFailsWith<ClientError> { rooms.command(first, move(0)) }
        val second = join(rooms, first.room)
        assertEquals(Player.BLUE, second.player)
        assertFailsWith<ClientError> { join(rooms, first.room) }
    }

    @Test fun onlyOneConcurrentCommandWithSameRevisionCanCommit() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        join(rooms, first.room)
        first.peer.messages.receive()
        val results = (1..2).map { x -> async {
            try { rooms.command(first, move(0, x)); true } catch (_: ClientError) { false }
        } }.awaitAll()
        assertEquals(1, results.count { it })
        val snapshot = first.peer.messages.receive().room!!.game
        assertEquals(1, snapshot.stones.size)
        assertEquals(1, snapshot.revision)
    }

    @Test fun rejectsOutOfTurnStaleAndForgedCommands() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        val second = join(rooms, first.room)
        assertFailsWith<InvalidMove> { rooms.command(second, move(0)) }
        assertFailsWith<ClientError> { rooms.command(first.copy(peer = Peer()), move(0)) }
        rooms.command(first, move(0))
        assertFailsWith<ClientError> { rooms.command(second, move(0, 1)) }
        assertFailsWith<InvalidMove> { rooms.command(second, move(1)) }
    }

    @Test fun reconnectKeepsSeatAndOldSocketCannotDisconnectReplacement() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, welcome) = create(rooms)
        join(rooms, first.room)
        rooms.command(first, move(0))
        val peer = Peer()
        val resumed = rooms.enter(ClientMessage("join", room = first.room, token = welcome.token), peer)
        assertEquals(first.player, resumed.player)
        assertEquals(1, peer.messages.receive().room!!.game.stones.size)
        peer.messages.receive()
        rooms.leave(first)
        assertFailsWith<ClientError> { rooms.command(first, ClientMessage("resign", revision = 1)) }
        rooms.command(resumed, ClientMessage("resign", revision = 1))
        assertTrue(peer.messages.receive().room!!.game.finished)
    }

    @Test fun invalidResumeTokenDoesNotOccupySecondSeat() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        assertFailsWith<ClientError> { rooms.enter(ClientMessage("join", room = first.room, token = "forged"), Peer()) }
        assertEquals(Player.BLUE, join(rooms, first.room).player)
    }

    @Test fun disconnectPausesMovesUntilResume() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        val second = join(rooms, first.room)
        rooms.leave(second)
        assertFailsWith<ClientError> { rooms.command(first, move(0)) }
    }

    @Test fun rematchNeedsBothVotesAndKeepsRevisionMonotonic() = runTest {
        val rooms = Rooms(backgroundScope)
        val (first, _) = create(rooms)
        val second = join(rooms, first.room)
        assertFailsWith<ClientError> { rooms.command(first, ClientMessage("rematch", revision = 0)) }
        rooms.command(first, ClientMessage("resign", revision = 0))
        rooms.command(first, ClientMessage("rematch", revision = 1))
        rooms.command(first, ClientMessage("rematch", revision = 1))
        rooms.command(second, ClientMessage("rematch", revision = 1))
        val states = generateSequence { first.peer.messages.tryReceive().getOrNull() }.toList()
        val game = states.last().room!!.game
        assertFalse(game.finished)
        assertEquals(2, game.revision)
        assertFailsWith<ClientError> { rooms.command(first, move(0)) }
    }

    @Test fun emptyRoomsExpireButLiveRoomsRemain() = runTest {
        var time = 0L
        val rooms = Rooms(backgroundScope, now = { time })
        val (first, _) = create(rooms)
        rooms.leave(first)
        val (live, _) = create(rooms)
        time = 7 * 60 * 60 * 1000L
        rooms.cleanup()
        assertFailsWith<ClientError> { join(rooms, first.room) }
        assertEquals(Player.BLUE, join(rooms, live.room).player)
    }
}
