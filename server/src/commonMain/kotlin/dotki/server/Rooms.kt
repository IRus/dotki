package dotki.server

import dotki.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ClientError(message: String) : IllegalArgumentException(message)
class PeerReplaced : Exception("Player opened another connection")

class Peer(val messages: Channel<ServerMessage> = Channel(32))
data class Membership(val room: String, val player: Player, val peer: Peer)

/** All joins and commands commit under one mutex; no network I/O runs inside it. */
class Rooms(
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val think: suspend (GameState, BotLevel) -> BotMove = { state, level -> withContext(Dispatchers.Default) { Bot.choose(state, level) } },
    private val elapsedMillis: () -> Long = TimeSource.Monotonic.markNow().let { start -> { start.elapsedNow().inWholeMilliseconds } },
    private val journal: SessionJournal = SessionJournal.None,
) {
    private data class UndoRecord(val before: GameState, val player: Player, val deadline: Long)
    private data class Member(val name: String, val token: String?, var peer: Peer?, val bot: Boolean = false)
    private class Room(val sessionId: String, val code: String, kind: GameKind, val mode: GameMode, val level: BotLevel, var touched: Long) {
        var game = GameState(kind)
        val members = linkedMapOf<Player, Member>()
        val rematch = mutableSetOf<Player>()
        var botJob: Job? = null
        var undo: UndoRecord? = null
        fun view(time: Long) = RoomView(code, game, members.map { (p, m) -> Seat(p, m.name, m.bot || m.peer != null, m.bot) }, rematch.toList(), mode, level,
            undo?.takeIf { it.deadline > time }?.let { UndoOffer(it.player, it.deadline - time) })
    }
    private val mutex = Mutex()
    private val rooms = mutableMapOf<String, Room>()
    private val archiveAvailable = MutableStateFlow(true)
    val healthy: Boolean get() = archiveAvailable.value

    @OptIn(ExperimentalUuidApi::class)
    suspend fun enter(message: ClientMessage, peer: Peer): Membership = mutex.withLock {
        requireArchive()
        prune()
        val room = when (message.type) {
            "create" -> {
                if (rooms.size >= 512) throw ClientError("Сервер занят. Попробуй позже")
                var code: String
                do { code = Uuid.random().toString().take(8).uppercase() } while (code in rooms)
                Room(Uuid.random().toString(), code, message.kind, message.mode, message.botLevel, now()).also { rooms[code] = it }
            }
            "join" -> rooms[message.room?.uppercase()] ?: throw ClientError("Комната не найдена или уже закрыта")
            else -> throw ClientError("Сначала создай комнату или присоединись")
        }
        val existing = message.token?.let { token -> room.members.entries.find { it.value.token == token } }
        if (message.token != null && existing == null) throw ClientError("Ключ игрока недействителен")
        val player: Player
        val member: Member
        if (existing != null) {
            player = existing.key
            member = existing.value
            member.peer?.messages?.close(PeerReplaced())
            member.peer = peer
        } else {
            player = Player.entries.firstOrNull { it !in room.members } ?: throw ClientError("В комнате уже два игрока")
            val name = message.name.trim().filter { !it.isISOControl() }.take(24).ifBlank { "Игрок ${room.members.size + 1}" }
            member = Member(name, Uuid.random().toString() + Uuid.random().toString(), peer)
            room.members[player] = member
            if (room.mode == GameMode.AI) room.members[Player.BLUE] = Member("ИИ · Dotki", null, null, bot = true)
        }
        room.touched = now()
        record(room, if (message.type == "create") "create" else if (existing != null) "reconnect" else "join", player)
        offer(peer, ServerMessage("welcome", room.view(elapsedMillis()), player, member.token))
        broadcast(room)
        scheduleBot(room)
        Membership(room.code, player, peer)
    }

    suspend fun command(membership: Membership, message: ClientMessage) = mutex.withLock {
        requireArchive()
        val room = rooms[membership.room] ?: throw ClientError("Комната закрыта")
        if (room.members[membership.player]?.peer !== membership.peer) throw ClientError("Соединение заменено новой вкладкой")
        if (message.type != "undo" && (room.members.size != 2 || room.members.values.any { !it.bot && it.peer == null })) {
            throw ClientError("Дождись подключения соперника")
        }
        if (message.revision != room.game.revision) throw ClientError("Поле обновилось. Повтори ход")
        val before = room.game
        room.game = when (message.type) {
            "undo" -> {
                val undo = room.undo ?: throw ClientError("Этот ход уже нельзя отменить")
                if (undo.player != membership.player) throw ClientError("Можно отменить только свой последний ход")
                if (elapsedMillis() >= undo.deadline) throw ClientError("Пять секунд на отмену уже прошли")
                undo.before.copy(revision = room.game.revision + 1)
            }
            "move" -> Rules.move(room.game, membership.player, Point(
                message.x ?: throw ClientError("Не указана координата X"),
                message.y ?: throw ClientError("Не указана координата Y"),
            ))
            "pass" -> Rules.pass(room.game, membership.player)
            "resign" -> Rules.resign(room.game, membership.player)
            "rematch" -> {
                if (!room.game.finished) throw ClientError("Сначала заверши текущую партию")
                room.rematch.add(membership.player)
                if (room.mode == GameMode.AI) room.rematch.add(Player.BLUE)
                if (room.rematch.size == 2) {
                    room.rematch.clear()
                    GameState(room.game.kind, revision = room.game.revision + 1)
                } else room.game
            }
            else -> throw ClientError("Неизвестная команда")
        }
        room.undo = when (message.type) {
            "move", "pass" -> UndoRecord(before, membership.player, elapsedMillis() + 5_000)
            "rematch" -> if (room.game === before) room.undo else null
            else -> null
        }
        if (message.type == "undo") room.rematch.clear()
        room.touched = now()
        room.botJob?.cancel()
        room.botJob = null
        record(room, message.type, membership.player, before,
            if (message.type == "move") room.game.lastMove else null)
        broadcast(room)
        scheduleBot(room)
    }

    suspend fun leave(membership: Membership) = mutex.withLock {
        if (!healthy) return@withLock
        val room = rooms[membership.room] ?: return@withLock
        val member = room.members[membership.player] ?: return@withLock
        if (member.peer === membership.peer) {
            member.peer = null
            room.botJob?.cancel()
            room.botJob = null
            room.touched = now()
            record(room, "disconnect", membership.player)
            broadcast(room)
        }
    }

    suspend fun cleanup() = mutex.withLock { if (healthy) prune() }

    private suspend fun prune() {
        val threshold = now() - 6 * 60 * 60 * 1000
        val expired = rooms.values.filter { room -> room.touched < threshold && room.members.values.all { it.bot || it.peer == null } }
        for (room in expired) {
            record(room, "expire")
            rooms.remove(room.code)
        }
    }

    /** Search runs outside the room mutex; disconnect, resignation and rematch invalidate its result. */
    private fun scheduleBot(room: Room) {
        if (room.mode != GameMode.AI || room.game.finished || room.game.turn != Player.BLUE ||
            room.members[Player.RED]?.peer == null || room.botJob?.isActive == true) return
        val snapshot = room.game
        room.botJob = scope.launch(start = CoroutineStart.LAZY) {
            val searchJob = currentCoroutineContext()[Job]
            delay(350)
            val started = elapsedMillis()
            val move = try { think(snapshot, room.level) }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (healthy) record(room, "ai_cancelled", Player.BLUE, snapshot,
                            aiDurationMillis = (elapsedMillis() - started).coerceAtLeast(0))
                    }
                }
                throw cancelled
            }
            val duration = (elapsedMillis() - started).coerceAtLeast(0)
            mutex.withLock {
                if (!healthy) return@withLock
                if (rooms[room.code] !== room || room.botJob !== searchJob || room.game.revision != snapshot.revision ||
                    room.game.finished || room.members[Player.RED]?.peer == null) {
                    record(room, "ai_discarded", Player.BLUE, snapshot, (move as? BotMove.Place)?.point, duration)
                    return@withLock
                }
                room.game = Bot.play(room.game, move)
                room.undo = null
                room.botJob = null
                room.touched = now()
                record(room, if (move is BotMove.Place) "ai_move" else "ai_pass", Player.BLUE, snapshot,
                    (move as? BotMove.Place)?.point, duration)
                broadcast(room)
            }
        }.also { it.start() }
    }

    private fun requireArchive() {
        if (!healthy) throw ClientError("Сохранение партий недоступно. Попробуй позже")
    }

    private suspend fun record(room: Room, type: String, actor: Player? = null, before: GameState? = null,
        point: Point? = null, aiDurationMillis: Long? = null) {
        try {
            val event = SessionEvent(room.sessionId, now(), type, actor, before, room.view(elapsedMillis()), point, aiDurationMillis)
            withContext(NonCancellable) { journal.record(event) }
        } catch (_: Exception) {
            // Fail closed: never acknowledge a move that was not archived. The healthcheck
            // makes the storage failure visible; a restart must not silently disable recording.
            archiveAvailable.value = false
            rooms.values.forEach { r ->
                r.botJob?.cancel()
                r.members.values.forEach { it.peer?.messages?.close() }
            }
            println("Session archive write failed; game service is unhealthy")
            throw ArchiveUnavailable()
        }
    }

    private fun broadcast(room: Room) {
        val message = ServerMessage("state", room.view(elapsedMillis()))
        room.members.values.forEach { it.peer?.let { peer -> offer(peer, message) } }
    }

    private fun offer(peer: Peer, message: ServerMessage) {
        // A slow peer reconnects for a fresh snapshot instead of blocking other rooms.
        if (peer.messages.trySend(message).isFailure) peer.messages.close()
    }
}
