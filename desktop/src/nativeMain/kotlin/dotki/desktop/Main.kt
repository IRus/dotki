package dotki.desktop

import dotki.*
import io.heapy.kinetica.*
import io.heapy.kinetica.application.ApplicationWindow
import io.heapy.kinetica.application.WindowSize
import io.heapy.kinetica.canvas.canvas
import io.heapy.kinetica.desktop.*

fun main(args: Array<String>) {
    val server = args.firstOrNull { it.startsWith("--server=") }?.substringAfter('=') ?: "https://dotki.heapyhop.com"
    require(server.startsWith("https://") || server.startsWith("http://127.0.0.1:"))
    val client = DesktopClient(server)
    runDesktopApplication("Dotki", ApplicationWindow("dotki", "Dotki — лист в клетку",
        size = WindowSize(1100.0, 860.0), minimumSize = WindowSize(850.0, 740.0)), release = client::close) {
        DotkiDesktop(client)
    }
}

@UiComponent
fun ComponentScope.DotkiDesktop(client: DesktopClient) {
    val ui = client.state.value
    host("column", props = mapOf("padding" to "20", "spacing" to "14")) {
        row {
            label("▦  dotki", fontSize = 28.0)
            label(if (ui.room == null) "Маленькая клетка. Большая игра." else "Комната ${ui.code}", secondary = true)
        }
        ui.error?.let { label(it, semantics = Semantics(testTag = "error")) }
        ui.notice?.let { label(it, semantics = Semantics(testTag = "notice")) }
        if (ui.room == null) Lobby(client) else Game(client, ui.room)
    }
}

@UiComponent
fun ComponentScope.Action(title: String, tag: String, enabled: Boolean = true, action: () -> Unit) {
    button(onClick = action, enabled = enabled, semantics = Semantics(testTag = tag, label = title, role = Role.Button, focusable = true)) { text(title) }
}

@UiComponent
fun ComponentScope.Lobby(client: DesktopClient) {
    val ui = client.state.value
    host("column", props = mapOf("spacing" to "20", "padding" to "16")) {
        label("Лист в клетку. И вы вдвоём.", fontSize = 30.0)
        label("Пригласи друга из браузера или приложения — либо сыграй против ИИ.", secondary = true)
        textInput(ui.name, onInput = client::name, placeholder = "Твоё имя",
            semantics = Semantics(testTag = "player-name", label = "Твоё имя", focusable = true))
        row {
            GameCard(client, GameKind.DOTS)
            GameCard(client, GameKind.FIVE)
        }
        label("Уже есть приглашение?", fontSize = 18.0)
        textInput(ui.code, onInput = client::code, onSubmit = client::join, placeholder = "Код комнаты или ссылка",
            semantics = Semantics(testTag = "room-code", label = "Код комнаты или ссылка", focusable = true))
        Action(if (ui.connecting) "Подключение…" else "Войти в комнату ↗", "join-room", !ui.connecting, client::join)
        label("С другом или против ИИ · никаких регистраций", secondary = true)
    }
}

@UiComponent
fun ComponentScope.GameCard(client: DesktopClient, kind: GameKind) {
    val ui = client.state.value
    val dots = kind == GameKind.DOTS
    val selected = if (dots) ui.dotsLevel else ui.fiveLevel
    val prefix = if (dots) "dots" else "five"
    host("column", props = mapOf("padding" to "16", "spacing" to "12", "minWidth" to "340")) {
        label(if (dots) "Точки" else "Крестики-нолики", fontSize = 23.0)
        label(if (dots) "Лист A5 · 30 × 43 точки" else "Пять в ряд · бесконечное поле", secondary = true)
        row {
            Action(if (selected == BotLevel.MEDIUM) "✓ Medium" else "Medium", "$prefix-medium") { client.level(kind, BotLevel.MEDIUM) }
            Action(if (selected == BotLevel.HARD) "✓ Hard" else "Hard", "$prefix-hard") { client.level(kind, BotLevel.HARD) }
        }
        Action("Против ИИ", "$prefix-ai", !ui.connecting) { client.start(kind, true) }
        Action("Создать игру с другом ↗", "$prefix-friend", !ui.connecting) { client.start(kind, false) }
    }
}

@UiComponent
fun ComponentScope.Game(client: DesktopClient, room: RoomView) {
    val ui = client.state.value
    val game = room.game
    row {
        Action("← Все игры", "leave-game", action = client::leave)
        label(if (game.kind == GameKind.DOTS) "Точки · A5" else "Крестики-нолики · пять в ряд", fontSize = 21.0)
        if (room.mode == GameMode.MULTIPLAYER) Action("Скопировать приглашение ↗", "invite") {
            copyTextToClipboard(client.invite()); client.notice("Ссылка скопирована — отправь её другу")
        }
    }
    row {
        canvas(client.board.model(), onInput = { client.board.input(it, client.canMove(), client::move) },
            semantics = Semantics(testTag = "game-board", label = "Игровое поле. Стрелки — выбор, Enter — ход", focusable = true),
            key = room.code, minWidth = 480.0, minHeight = 520.0)
        host("column", props = mapOf("width" to "270", "spacing" to "16", "padding" to "12")) {
            label(status(ui), fontSize = 19.0, semantics = Semantics(testTag = "game-status"))
            label("Ходов: ${game.stones.size}", semantics = Semantics(testTag = "move-count"))
            label("Ты — " + if (game.kind == GameKind.DOTS) {
                if (ui.player == Player.RED) "красные точки" else "синие точки"
            } else if (ui.player == Player.RED) "крестики" else "нолики", secondary = true)
            label("${if (game.kind == GameKind.DOTS) "Красные" else "✕"} · ${room.seats.firstOrNull { it.player == Player.RED }?.name ?: "Первый игрок"}")
            label("${if (game.kind == GameKind.DOTS) "Синие" else "○"} · ${room.seats.firstOrNull { it.player == Player.BLUE }?.name ?: "Ждём друга"}")
            if (game.kind == GameKind.DOTS) label("Счёт: ${game.redScore} : ${game.blueScore}", fontSize = 24.0, semantics = Semantics(testTag = "score"))
            if (room.mode == GameMode.AI) label("Компьютерный соперник · ${room.botLevel}", secondary = true)
            if (ui.undoSeconds > 0) Action("Отменить ход · ${ui.undoSeconds} с", "undo", ui.connected) { client.send("undo") }
            if (game.finished) Action(if (ui.player in room.rematch) "Ждём согласия…" else "Сыграть ещё раз ↗", "rematch",
                ui.connected && ui.player !in room.rematch) { client.send("rematch") }
            else {
                if (game.kind == GameKind.DOTS) Action(if (game.passes > 0) "Пас · завершить партию" else "Пропустить ход", "pass", client.canMove()) { client.send("pass") }
                if (ui.confirmResign) {
                    Action("Да, сдаться", "confirm-resign", ui.connected) { client.send("resign") }
                    Action("Продолжить игру", "cancel-resign") { client.confirmResign(false) }
                } else Action("Сдаться", "resign", ui.connected) { client.confirmResign(true) }
            }
            label(if (game.kind == GameKind.DOTS) "Окружай чужие точки.\nКрай листа не замыкает контур.\nДва паса завершают партию."
                else "Собери пять знаков подряд.\nЗнаки ставятся внутри клеток.\nПеретаскивай поле для обзора.", secondary = true)
        }
    }
    row {
        Action("−", "zoom-out") { client.board.zoom(.8) }
        Action("+", "zoom-in") { client.board.zoom(1.25) }
        Action("⌖ Последний ход", "center", action = client.board::center)
        Action("Всё поле", "fit", action = client.board::fit)
        label("Тяни — двигай · колесо / щипок — масштаб · стрелки и Enter — ход", secondary = true)
    }
}

private fun status(ui: DesktopState): String {
    val room = ui.room ?: return "Выбери игру"
    val game = room.game
    return when {
        !ui.connected -> if (ui.connecting) "Переподключение…" else "Нет соединения"
        game.finished -> when (game.winner) { null -> "Ничья. Реванш?"; ui.player -> "Твоя победа!"; else -> "Победа соперника" }
        room.seats.size < 2 -> "Ждём друга"
        room.seats.any { !it.online } -> "Соперник отключился"
        game.turn == ui.player -> "Твой ход"
        room.mode == GameMode.AI -> "ИИ думает…"
        else -> "Ход соперника"
    }
}
