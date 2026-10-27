@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dotki.server

import dotki.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

class UndoRoomsTest {
    private suspend fun pair(rooms: Rooms): Pair<Membership, Membership> {
        val red = rooms.enter(ClientMessage("create", kind = GameKind.FIVE), Peer())
        val blue = rooms.enter(ClientMessage("join", room = red.room), Peer())
        drain(red.peer); drain(blue.peer)
        return red to blue
    }
    private fun drain(peer: Peer) = generateSequence { peer.messages.tryReceive().getOrNull() }.lastOrNull()?.room
    private fun move(revision: Int, x: Int = 0, y: Int = 0) = ClientMessage("move", revision = revision, x = x, y = y)

    @Test fun undoAt4999msRestoresTurnButAdvancesRevision() = runTest {
        var time = 0L
        val rooms = Rooms(backgroundScope, elapsedMillis = { time })
        val (red, blue) = pair(rooms)
        rooms.command(red, move(0))
        assertEquals(5_000, drain(red.peer)!!.undo!!.remainingMillis)
        time = 4_999
        rooms.command(red, ClientMessage("undo", revision = 1))
        val state = drain(blue.peer)!!.game
        assertTrue(state.stones.isEmpty()); assertEquals(Player.RED, state.turn)
        assertEquals(2, state.revision)
        assertFailsWith<ClientError> { rooms.command(red, move(0)) }
        rooms.command(red, move(2, 1))
    }

    @Test fun undoAt5000msIsRejectedEvenWithNoOpponentReply() = runTest {
        var time = 0L
        val rooms = Rooms(backgroundScope, elapsedMillis = { time })
        val (red, _) = pair(rooms)
        rooms.command(red, move(0))
        time = 5_000
        assertFailsWith<ClientError> { rooms.command(red, ClientMessage("undo", revision = 1)) }
    }

    @Test fun onlyLastMoverCanUndoAndNoDoubleUndo() = runTest {
        val rooms = Rooms(backgroundScope)
        val (red, blue) = pair(rooms)
        rooms.command(red, move(0))
        assertFailsWith<ClientError> { rooms.command(blue, ClientMessage("undo", revision = 1)) }
        rooms.command(blue, move(1, 1))
        assertFailsWith<ClientError> { rooms.command(red, ClientMessage("undo", revision = 2)) }
        rooms.command(blue, ClientMessage("undo", revision = 2))
        assertEquals(1, drain(red.peer)!!.game.stones.size)
        assertFailsWith<ClientError> { rooms.command(blue, ClientMessage("undo", revision = 3)) }
    }

    @Test fun undoWorksWhenOpponentDisconnects() = runTest {
        val rooms = Rooms(backgroundScope)
        val (red, blue) = pair(rooms)
        rooms.command(red, move(0)); rooms.leave(blue)
        rooms.command(red, ClientMessage("undo", revision = 1))
        assertTrue(drain(red.peer)!!.game.stones.isEmpty())
    }

    @Test fun winningMoveCanBeUndoneAndStaleRematchVotesAreCleared() = runTest {
        val rooms = Rooms(backgroundScope)
        val (red, blue) = pair(rooms)
        for (x in 0..3) {
            rooms.command(red, move(x*2, x))
            rooms.command(blue, move(x*2+1, x, 2))
        }
        rooms.command(red, move(8, 4))
        assertTrue(drain(red.peer)!!.game.finished)
        rooms.command(blue, ClientMessage("rematch", revision = 9))
        rooms.command(red, ClientMessage("undo", revision = 9))
        val restored = drain(red.peer)!!
        assertFalse(restored.game.finished); assertNull(restored.game.winner)
        assertTrue(restored.game.winningLine.isEmpty()); assertTrue(restored.rematch.isEmpty())
        assertEquals(8, restored.game.stones.size)
    }

    @Test fun undoCancelsPendingHardBotAndPreservesDifficultyOnResumeAndRematch() = runTest {
        val release = CompletableDeferred<Unit>()
        val levels = mutableListOf<BotLevel>()
        val rooms = Rooms(backgroundScope, think = { _, level -> levels.add(level); release.await(); BotMove.Place(Point(1,0)) })
        val peer = Peer()
        var red = rooms.enter(ClientMessage("create", kind = GameKind.FIVE, mode = GameMode.AI, botLevel = BotLevel.HARD), peer)
        val token = peer.messages.receive().token
        drain(peer)
        rooms.command(red, move(0))
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf(BotLevel.HARD), levels)
        rooms.command(red, ClientMessage("undo", revision = 1))
        release.complete(Unit); runCurrent()
        assertTrue(drain(peer)!!.game.stones.isEmpty())
        rooms.leave(red)
        val replacement = Peer()
        red = rooms.enter(ClientMessage("join", room = red.room, token = token), replacement)
        assertEquals(BotLevel.HARD, drain(replacement)!!.botLevel)
        rooms.command(red, ClientMessage("resign", revision = 2))
        rooms.command(red, ClientMessage("rematch", revision = 3))
        assertEquals(BotLevel.HARD, drain(replacement)!!.botLevel)
    }

    @Test fun completedBotReplyClosesUndoWindow() = runTest {
        val rooms = Rooms(backgroundScope, think = { _, _ -> BotMove.Place(Point(1,0)) })
        val red = rooms.enter(ClientMessage("create", kind = GameKind.FIVE, mode = GameMode.AI), Peer())
        rooms.command(red, move(0))
        advanceTimeBy(400); runCurrent()
        assertNull(drain(red.peer)!!.undo)
        assertFailsWith<ClientError> { rooms.command(red, ClientMessage("undo", revision = 2)) }
    }

    @Test fun undoRestoresCapturedDotsAndScores() = runTest {
        val rooms = Rooms(backgroundScope)
        val red = rooms.enter(ClientMessage("create"), Peer())
        val blue = rooms.enter(ClientMessage("join", room = red.room), Peer())
        val sequence = listOf(red to Point(4,5), blue to Point(5,5), red to Point(5,4), blue to Point(20,20), red to Point(6,5), blue to Point(21,20), red to Point(5,6))
        sequence.forEachIndexed { revision, (member, p) -> rooms.command(member, move(revision, p.x, p.y)) }
        assertEquals(1, drain(red.peer)!!.game.redScore)
        rooms.command(red, ClientMessage("undo", revision = 7))
        val game = drain(red.peer)!!.game
        assertEquals(0, game.redScore); assertTrue(game.territories.isEmpty())
        assertEquals(6, game.stones.size); assertEquals(Point(21,20), game.lastMove)
    }
}
