package dotki.web

import dotki.*
import io.heapy.kinetica.KineticaRuntime
import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.MessageEvent
import org.w3c.dom.CloseEvent
import kotlin.math.ceil

data class UiState(
    val name: String = "",
    val code: String = "",
    val room: RoomView? = null,
    val player: Player? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val undoSeconds: Int = 0,
)

class Client(private val runtime: KineticaRuntime) {
    var ui = UiState(name = stored("dotki.name").orEmpty(), code = roomInUrl())
        private set
    private var socket: WebSocket? = null
    private var generation = 0
    private var retry = 0
    private var reconnectTimer: Int? = null
    var board: Board? = null
    private var undoDeadline = 0.0

    init {
        window.setInterval({
            val remaining = if (ui.connected && ui.room?.undo?.player == ui.player) {
                ceil((undoDeadline - window.performance.now()) / 1000).toInt().coerceAtLeast(0)
            } else 0
            if (remaining != ui.undoSeconds) change(ui.copy(undoSeconds = remaining))
        }, 100)
    }

    private fun receiveRoom(room: RoomView?) {
        undoDeadline = window.performance.now() + (room?.undo?.remainingMillis?.toDouble() ?: 0.0)
        val seconds = if (ui.connected && room?.undo?.player == ui.player) ceil((room?.undo?.remainingMillis?.toDouble() ?: 0.0) / 1000).toInt() else 0
        change(ui.copy(room = room, undoSeconds = seconds))
    }

    private fun change(next: UiState) { ui = next; runtime.invalidate("client") }
    fun name(value: String) { change(ui.copy(name = value.take(24))); store("dotki.name", ui.name) }
    fun code(value: String) = change(ui.copy(code = value.trim().uppercase().take(8)))
    fun dismiss() = change(ui.copy(error = null, notice = null))

    fun start(kind: GameKind, mode: GameMode = GameMode.MULTIPLAYER, level: BotLevel = BotLevel.MEDIUM) = connect(ClientMessage("create", name = ui.name, kind = kind, mode = mode, botLevel = level))
    fun join() {
        if (!Regex("[A-F0-9]{8}").matches(ui.code)) {
            change(ui.copy(error = "Код комнаты — 8 символов из ссылки приглашения")); return
        }
        connect(ClientMessage("join", room = ui.code, name = ui.name, token = stored("dotki.token.${ui.code}")))
    }
    fun resume() { if (ui.code.isNotBlank() && stored("dotki.token.${ui.code}") != null) join() }

    private fun connect(hello: ClientMessage, reconnecting: Boolean = false) {
        reconnectTimer?.let(window::clearTimeout)
        val current = ++generation
        socket?.close()
        if (!reconnecting) retry = 0
        change(ui.copy(connecting = true, connected = false, error = null))
        val protocol = if (window.location.protocol == "https:") "wss" else "ws"
        val ws = WebSocket("$protocol://${window.location.host}/ws")
        socket = ws
        var rejected = false
        ws.onopen = { if (generation == current) ws.send(wireJson.encodeToString(hello)) }
        ws.onmessage = { event: MessageEvent ->
            if (generation == current) {
                val message = wireJson.decodeFromString<ServerMessage>(event.data.toString())
                when (message.type) {
                    "welcome" -> {
                        retry = 0
                        val room = message.room!!
                        store("dotki.token.${room.code}", message.token!!)
                        window.history.replaceState(null, "", "?room=${room.code}")
                        change(ui.copy(room = room, code = room.code, player = message.player, connected = true, connecting = false))
                        receiveRoom(room)
                    }
                    "state" -> receiveRoom(message.room)
                    "error" -> {
                        if (!ui.connected) rejected = true
                        change(ui.copy(error = message.error, connecting = false))
                    }
                }
            }
        }
        ws.onclose = { event ->
            if (generation == current) {
                if ((event as CloseEvent).code.toInt() == 4001) {
                    rejected = true
                    change(ui.copy(error = "Игра открыта в другой вкладке. Продолжай там или вернись в комнату вручную"))
                }
                change(ui.copy(connected = false, connecting = false))
                val room = ui.room
                if (!rejected && room != null) {
                    val delay = minOf(15_000, 750 * (1 shl minOf(retry++, 4)))
                    reconnectTimer = window.setTimeout({
                        connect(ClientMessage("join", room = room.code, token = stored("dotki.token.${room.code}"), name = ui.name), true)
                    }, delay)
                } else if (!rejected) change(ui.copy(error = "Не удалось подключиться к серверу. Попробуй ещё раз"))
            }
        }
    }

    fun canMove(): Boolean = ui.connected && ui.room?.let {
        it.seats.size == 2 && it.seats.all(Seat::online) && !it.game.finished && it.game.turn == ui.player
    } == true

    fun move(point: Point) { if (canMove()) send("move", point) }
    fun send(type: String, point: Point? = null) {
        if (!ui.connected) return
        socket?.send(wireJson.encodeToString(ClientMessage(type, x = point?.x, y = point?.y, revision = ui.room?.game?.revision)))
        dismiss()
    }

    fun leave() {
        generation++
        reconnectTimer?.let(window::clearTimeout)
        socket?.close(); socket = null
        window.history.replaceState(null, "", window.location.pathname)
        change(UiState(name = ui.name))
    }

    fun invite() {
        val url = "${window.location.origin}${window.location.pathname}?room=${ui.room?.code}"
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null) clipboard.writeText(url).then({ _: dynamic ->
            change(ui.copy(notice = "Ссылка скопирована. Отправь её другу"))
        }, { _: dynamic -> change(ui.copy(notice = url)) })
        else change(ui.copy(notice = url))
    }

    companion object {
        private fun stored(key: String): String? = try { window.sessionStorage.getItem(key) } catch (_: Throwable) { null }
        private fun store(key: String, value: String) { try { window.sessionStorage.setItem(key, value) } catch (_: Throwable) { /* private mode */ } }
        private fun roomInUrl(): String = window.location.search.removePrefix("?").split("&")
            .firstOrNull { it.startsWith("room=") }?.substringAfter("=")?.uppercase()?.take(8).orEmpty()
    }
}
