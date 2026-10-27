@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dotki.server

import dotki.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*

class AiRoomsTest {
    private suspend fun create(rooms: Rooms, kind: GameKind = GameKind.FIVE): Pair<Membership, ServerMessage> {
        val peer = Peer()
        val member = rooms.enter(ClientMessage("create", kind = kind, mode = GameMode.AI), peer)
        val welcome = peer.messages.receive()
        peer.messages.receive()
        return member to welcome
    }

    private fun latest(peer: Peer) = generateSequence { peer.messages.tryReceive().getOrNull() }.last().room!!
    private fun move(revision: Int) = ClientMessage("move", x = 0, y = 0, revision = revision)

    @Test fun aiSeatIsReadyAndCannotBeClaimedByAnotherClient() = runTest {
        val rooms = Rooms(backgroundScope)
        val (member, welcome) = create(rooms)
        val room = assertNotNull(welcome.room)
        assertEquals(GameMode.AI, room.mode)
        val bot = room.seats.single { it.bot }
        assertEquals(Player.BLUE, bot.player)
        assertTrue(bot.online)
        assertFailsWith<ClientError> { rooms.enter(ClientMessage("join", room = member.room), Peer()) }
        assertFailsWith<ClientError> { rooms.enter(ClientMessage("join", room = member.room, token = ""), Peer()) }
        assertFailsWith<ClientError> { rooms.command(member.copy(player = Player.BLUE), move(0)) }
    }

    @Test fun exactlyOneReplyIsCommittedAndRematchNeedsOnlyHuman() = runTest {
        var searches = 0
        val rooms = Rooms(backgroundScope, think = { state, _ -> searches++; BotMove.Place(Point(1, 0)) })
        val (member, _) = create(rooms)
        rooms.command(member, move(0))
        assertEquals(Player.BLUE, latest(member.peer).game.turn)
        assertFailsWith<ClientError> { rooms.command(member, move(0)) }
        advanceTimeBy(400); runCurrent()
        val reply = latest(member.peer).game
        assertEquals(2, reply.stones.size)
        assertEquals(Player.RED, reply.turn)
        assertEquals(1, searches)
        rooms.command(member, ClientMessage("resign", revision = 2))
        rooms.command(member, ClientMessage("rematch", revision = 3))
        val restart = latest(member.peer).game
        assertFalse(restart.finished)
        assertTrue(restart.stones.isEmpty())
        assertEquals(4, restart.revision)
    }

    @Test fun disconnectCancelsThinkingAndResumeSchedulesOneReply() = runTest {
        var searches = 0
        val rooms = Rooms(backgroundScope, think = { state, _ -> searches++; BotMove.Place(Point(1, 0)) })
        val (member, welcome) = create(rooms)
        rooms.command(member, move(0))
        rooms.leave(member)
        advanceTimeBy(500); runCurrent()
        assertEquals(0, searches)
        val peer = Peer()
        rooms.enter(ClientMessage("join", room = member.room, token = welcome.token), peer)
        advanceTimeBy(400); runCurrent()
        assertEquals(1, searches)
        assertEquals(2, latest(peer).game.stones.size)
    }

    @Test fun staleSearchCannotCommitAfterResignationAndRematch() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val rooms = Rooms(backgroundScope, think = { state, _ ->
            started.complete(Unit)
            // Simulate a backend that returns despite cancellation; the commit guard must still hold.
            withContext(NonCancellable) { release.await() }
            BotMove.Place(Point(1, 0))
        })
        val (member, _) = create(rooms)
        rooms.command(member, move(0))
        advanceTimeBy(400); runCurrent()
        assertTrue(started.isCompleted)
        rooms.command(member, ClientMessage("resign", revision = 1))
        rooms.command(member, ClientMessage("rematch", revision = 2))
        release.complete(Unit); runCurrent()
        val state = latest(member.peer).game
        assertEquals(3, state.revision)
        assertTrue(state.stones.isEmpty())
        assertFalse(state.finished)
    }

    @Test fun slowAiDoesNotHoldTheGlobalRoomMutex() = runTest {
        val release = CompletableDeferred<Unit>()
        val rooms = Rooms(backgroundScope, think = { state, _ -> release.await(); BotMove.Place(Point(1, 0)) })
        val (member, _) = create(rooms)
        rooms.command(member, move(0))
        advanceTimeBy(400); runCurrent()
        val other = rooms.enter(ClientMessage("create"), Peer())
        rooms.enter(ClientMessage("join", room = other.room), Peer())
        rooms.command(other, move(0))
        assertEquals(1, latest(other.peer).game.revision)
        release.complete(Unit); runCurrent()
        assertEquals(2, latest(member.peer).game.revision)
    }

    @Test fun botDoesNotKeepAnAbandonedRoomAlive() = runTest {
        var time = 0L
        val rooms = Rooms(backgroundScope, now = { time })
        val (member, _) = create(rooms)
        rooms.leave(member)
        time = 7 * 60 * 60 * 1000L
        rooms.cleanup()
        assertFailsWith<ClientError> { rooms.enter(ClientMessage("join", room = member.room), Peer()) }
    }

    @Test fun dotsBotCanFinishByPassing() = runTest {
        val rooms = Rooms(backgroundScope, think = { state, _ -> Bot.choose(state) })
        val (member, _) = create(rooms, GameKind.DOTS)
        rooms.command(member, ClientMessage("pass", revision = 0))
        advanceTimeBy(400); runCurrent()
        val state = latest(member.peer).game
        assertTrue(state.finished)
        assertNull(state.winner)
        assertEquals(2, state.passes)
    }
}
