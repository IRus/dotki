package dotki.web

import dotki.*
import io.heapy.kinetica.*
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.render.HostWidgetFactory
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element

fun main() {
    val runtime = KineticaRuntime(debug = false)
    val client = Client(runtime)
    val app = mountKineticaApp(document.getElementById("app")!!, runtime,
        hostWidgets = mapOf("game-board" to HostWidgetFactory<Element> { Board(client).also { client.board = it } }),
    ) { Dotki(client.ui, client) }
    var scheduled = false
    runtime.onInvalidation {
        if (!scheduled) {
            scheduled = true
            window.requestAnimationFrame { scheduled = false; app.render() }
        }
    }
    client.resume()
}

@UiComponent
fun ComponentScope.Label(tag: String, value: String, css: String = "") {
    host(tag, mapOf("class" to css)) { text(value, semantics = null) }
}

@UiComponent
fun ComponentScope.Action(label: String, css: String = "button", enabled: Boolean = true, pressed: Boolean? = null, action: () -> Unit) {
    val click = hostEvent { action() }
    host("button", buildMap {
        put("class", css); put("type", "button"); put("event:onClick", click)
        if (!enabled) put("disabled", "disabled")
        if (pressed != null) put("aria-pressed", pressed.toString())
    }) { text(label, semantics = null) }
}

@UiComponent
fun ComponentScope.Dotki(ui: UiState, client: Client) {
    host("div", mapOf("class" to "shell")) {
        host("header", mapOf("class" to "header")) {
            host("a", mapOf("href" to "/", "class" to "brand", "aria-label" to "Dotki — главная")) {
                Label("span", "▦", "brand-icon"); Label("span", "dotki")
            }
            Label("span", "Маленькая клетка. Большая игра.", "tagline")
            Label("span", if (ui.room != null) "КОМНАТА / ${ui.room.code}" else "ИГРЫ НА БУМАГЕ", "eyebrow header-note")
        }
        if (ui.room == null) Lobby(ui, client) else Game(ui, client)
        if (ui.error != null || ui.notice != null) {
            host("div", mapOf("class" to "toast ${if (ui.error != null) "error" else ""}", "role" to "status", "aria-live" to "polite")) {
                Label("span", ui.error ?: ui.notice.orEmpty())
                Action("Закрыть", "text-button") { client.dismiss() }
            }
        }
        host("footer", mapOf("class" to "footer")) {
            Label("span", "С другом или против ИИ · никаких регистраций")
            Label("span", "Сделай свой ход ↗", "footer-note")
        }
    }
}

@UiComponent
fun ComponentScope.Lobby(ui: UiState, client: Client) {
    host("main", mapOf("class" to "lobby")) {
        host("section", mapOf("class" to "intro")) {
            Label("span", "ПОМНИШЬ ПОСЛЕДНЮЮ ПАРТУ?", "eyebrow")
            host("h1") { text("Лист в клетку.", semantics = null); host("br"); Label("em", "И вы вдвоём.") }
            Label("p", "Любимые игры с полей тетрадки — теперь на любом расстоянии. Пригласи друга по ссылке или сыграй против ИИ.", "lead")
            host("div", mapOf("class" to "name-field")) {
                Label("label", "Как тебя зовут?", "field-label")
                textInput(ui.name, onInput = client::name, placeholder = "Твоё имя", semantics = Semantics(label = "Твоё имя"))
            }
            host("div", mapOf("class" to "join-box")) {
                Label("h2", "Уже есть приглашение?")
                host("div", mapOf("class" to "join-row")) {
                    textInput(ui.code, onInput = client::code, placeholder = "Код комнаты", semantics = Semantics(label = "Код комнаты"))
                    Action(if (ui.connecting) "Подключаем…" else "Войти ↗", enabled = !ui.connecting) { client.join() }
                }
            }
        }
        host("section", mapOf("class" to "game-cards", "aria-label" to "Выбери игру")) {
            GameCard(GameKind.DOTS, ui.connecting, client)
            GameCard(GameKind.FIVE, ui.connecting, client)
        }
    }
}

@UiComponent
fun ComponentScope.GameCard(kind: GameKind, busy: Boolean, client: Client) {
    val dots = kind == GameKind.DOTS
    var level by state { BotLevel.MEDIUM }
    host("article", mapOf("class" to "game-card ${if (dots) "dots-card" else "five-card"}")) {
        host("div", mapOf("class" to "card-top")) {
            Label("span", if (dots) "01 / ЗАХВАТ ТЕРРИТОРИИ" else "02 / ПЯТЬ В РЯД", "eyebrow")
            Label("span", "Друг / ИИ", "pill")
        }
        host("div", mapOf("class" to "mini-board ${if (dots) "mini-dots" else "mini-five"}", "aria-hidden" to "true")) {
            if (dots) {
                Label("span", "●", "ink red p1"); Label("span", "●", "ink red p2"); Label("span", "●", "ink red p3")
                Label("span", "●", "ink red p4"); Label("span", "●", "ink blue p5")
                host("div", mapOf("class" to "capture-demo"))
            } else {
                Label("span", "×", "ink red p1"); Label("span", "×", "ink red p2"); Label("span", "×", "ink red p3")
                Label("span", "○", "ink blue p4"); Label("span", "○", "ink blue p5")
                Label("span", "↗", "infinity-arrow")
            }
        }
        Label("h2", if (dots) "Точки" else "Крестики-нолики")
        Label("p", if (dots) "Окружай точки соперника и забирай территорию. Твой план помещается на одном листе." else "Собери пять в ряд. Дальше края? Просто двигай поле — место для нового плана найдётся всегда.", "card-copy")
        host("div", mapOf("class" to "card-bottom")) {
            Label("span", if (dots) "A5 · клетка 5 мм" else "∞ · бесконечное поле", "card-meta")
            Action("Начать ↗", "button ${if (dots) "" else "blue-button"}", !busy) { client.start(kind) }
        }
        host("div", mapOf("class" to "difficulty", "role" to "group", "aria-label" to "Сложность ИИ")) {
            Action("Medium", "tool-button", !busy, level == BotLevel.MEDIUM) { level = BotLevel.MEDIUM }
            Action("Hard", "tool-button", !busy, level == BotLevel.HARD) { level = BotLevel.HARD }
        }
        Action("Против ИИ", "button ai-button outline", !busy) { client.start(kind, GameMode.AI, level) }
    }
}

@UiComponent
fun ComponentScope.Game(ui: UiState, client: Client) {
    val room = ui.room!!
    val game = room.game
    val dots = game.kind == GameKind.DOTS
    val ai = room.mode == GameMode.AI
    val ready = ui.connected && room.seats.size == 2 && room.seats.all(Seat::online)
    var resignConfirm by state { false }
    val status = when {
        !ui.connected -> if (ui.connecting) "Восстанавливаем связь…" else "Нет соединения с комнатой"
        game.finished -> if (game.winner == null) "Ничья. Ещё одну?" else if (game.winner == ui.player) "Твоя победа! Красиво сыграно" else "Победа соперника. Реванш?"
        room.seats.size < 2 -> "Ждём друга. Отправь ссылку на комнату"
        room.seats.any { !it.online } -> "Соперник отключился. Ждём возвращения"
        game.turn == ui.player -> "Твой ход. ${if (dots) "Поставь точку" else if (ui.player == Player.RED) "Поставь крестик" else "Поставь нолик"}"
        ai -> "ИИ думает над ходом…"
        else -> "Ход соперника. Есть время подумать"
    }
    host("main", mapOf("class" to "game-page")) {
        host("div", mapOf("class" to "game-heading")) {
            host("div") {
                Action("← Все игры", "text-button") { client.leave() }
                Label("h1", if (dots) "Точки" else "Пять в ряд")
            }
            if (ai) Label("span", "ИИ · ${room.botLevel.name}", "pill ai-badge")
            else Action("Пригласить друга ↗", "button outline") { client.invite() }
        }
        host("div", mapOf("class" to "play-layout")) {
            host("section", mapOf("class" to "board-panel")) {
                host("div", mapOf("class" to "board-top")) {
                    Label("span", if (dots) "ЛИСТ № 01 / A5" else "ПОЛЕ БЕЗ ГРАНИЦ", "eyebrow")
                    Label("span", "Ходов: ${game.stones.size}", "move-count")
                }
                host("game-board", mapOf("state" to wireJson.encodeToString(game), "enabled" to client.canMove().toString()))
                host("div", mapOf("class" to "board-tools")) {
                    Label("span", "Тяни — двигай · колесо / щипок — масштаб", "board-hint")
                    host("div", mapOf("class" to "zoom-tools")) {
                        Action("−", "tool-button") { client.board?.zoom(0.8) }
                        Action("+", "tool-button") { client.board?.zoom(1.25) }
                        Action("⌖ Последний ход", "tool-button") { client.board?.center() }
                    }
                }
            }
            host("aside", mapOf("class" to "sidebar")) {
                host("section", mapOf("class" to "status-card")) {
                    Label("span", if (game.finished) "ПАРТИЯ ЗАВЕРШЕНА" else "ПАРТИЯ ИДЁТ", "eyebrow")
                    host("h2", mapOf("aria-live" to "polite", "data-testid" to "game-status")) { text(status, semantics = null) }
                    PlayerRow(Player.RED, ui)
                    PlayerRow(Player.BLUE, ui)
                    if (ui.undoSeconds > 0 && ui.connected) {
                        Action("Отменить ход · ${ui.undoSeconds} с", "button outline") { client.send("undo") }
                    }
                    if (game.finished) {
                        Action(if (ui.player in room.rematch) "Ждём согласия…" else "Сыграть ещё раз ↗", enabled = ready && ui.player !in room.rematch) { client.send("rematch") }
                    } else {
                        if (dots) Action(if (game.passes == 1) "Пас · завершить партию" else "Пропустить ход", "button outline", client.canMove()) { client.send("pass") }
                        if (resignConfirm) {
                            Action("Да, сдаться", "text-button danger", ready) { client.send("resign"); resignConfirm = false }
                            Action("Продолжить игру", "text-button") { resignConfirm = false }
                        } else Action("Сдаться", "text-button muted", ready) { resignConfirm = true }
                    }
                }
                host("section", mapOf("class" to "rules-card")) {
                    Label("span", "НА ПОЛЯХ ТЕТРАДКИ", "eyebrow")
                    Label("h3", "Правила за минуту")
                    Label("p", if (dots) "Ставьте точки по очереди на пересечениях. Замкните цепочку своих точек вокруг чужих — по сторонам или диагоналям. Каждая захваченная точка приносит очко." else "Крестики ходят первыми. Побеждает тот, кто соберёт пять или больше своих знаков подряд: по горизонтали, вертикали или диагонали.")
                    Label("p", if (dots) "Пустой контур — «домик». Он захватывает вошедшую точку, если её ход сам не захватил соперника. Край листа не считается стеной. Два паса подряд завершают игру." else "Никаких границ и запрещённых ходов. Передвигай поле в любую сторону; кнопка ⌖ вернёт к последнему ходу.")
                    if (ai) Label("p", if (room.botLevel == BotLevel.HARD) "Hard просчитывает ответы соперника и продолжения. На сложных позициях ход может занять чуть больше времени." else if (dots) "Medium ищет захваты и защищает свои точки. Если после твоего паса у него нет немедленного захвата, он тоже пасует." else "Medium ищет победные комбинации и перекрывает угрозы. Ты играешь крестиками и ходишь первым.")
                    Label("p", "С клавиатуры: Tab на поле, стрелки выбирают ${if (dots) "пересечение" else "клетку"}, Enter делает ход.", "keyboard-hint")
                }
            }
        }
    }
}

@UiComponent
fun ComponentScope.PlayerRow(player: Player, ui: UiState) {
    val room = ui.room!!
    val seat = room.seats.find { it.player == player }
    val active = !room.game.finished && room.game.turn == player
    host("div", mapOf("class" to "player-row ${if (active) "active" else ""}")) {
        Label("span", if (room.game.kind == GameKind.DOTS) "●" else if (player == Player.RED) "×" else "○", "avatar ${if (player == Player.RED) "red" else "blue"}")
        host("div", mapOf("class" to "player-info")) {
            Label("strong", (seat?.name ?: "Друг ещё в пути") + if (player == ui.player) " · ты" else "")
            Label("span", if (seat == null) "Место свободно" else if (seat.bot) "Компьютерный соперник" else if (seat.online) "В комнате" else "Не в сети", "presence")
        }
        if (room.game.kind == GameKind.DOTS) Label("strong", (if (player == Player.RED) room.game.redScore else room.game.blueScore).toString(), "score")
    }
}
