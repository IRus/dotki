package dotki.web

import dotki.*
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.render.HostWidget
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.*

/** A Kinetica custom host: owns only canvas drawing and pointer/keyboard input. */
class Board(private val client: Client) : HostWidget<Element> {
    private val canvas = (document.createElement("canvas") as HTMLCanvasElement).apply {
        className = "game-canvas"
        tabIndex = 0
        setAttribute("role", "application")
        textContent = "Используй стрелки для выбора позиции и Enter для хода."
    }
    override val view: Element = canvas
    private val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    private var game = GameState(GameKind.DOTS)
    private var enabled = false
    private var initialized = false
    private var width = 0.0
    private var height = 0.0
    private var step = 24.0
    private var cx = 0.0
    private var cy = 0.0
    private var cursor: Point? = null
    private var keyboard = false
    private val pointers = mutableMapOf<Int, Pair<Double, Double>>()
    private var down: Pair<Double, Double>? = null
    private var dragged = false
    private val resize: (Event) -> Unit = { size() }

    init {
        window.addEventListener("resize", resize)
        canvas.asDynamic().onpointerdown = { e: dynamic ->
            canvas.focus()
            keyboard = false
            val point = local(e.clientX as Double, e.clientY as Double)
            pointers[e.pointerId as Int] = point
            canvas.asDynamic().setPointerCapture(e.pointerId)
            if (pointers.size == 1) { down = point; dragged = false } else dragged = true
            e.preventDefault()
        }
        canvas.asDynamic().onpointermove = { e: dynamic ->
            val p = local(e.clientX as Double, e.clientY as Double)
            val id = e.pointerId as Int
            val previous = pointers[id]
            if (previous != null) {
                val origin = down
                if (origin != null && hypot(p.first - origin.first, p.second - origin.second) > 5) dragged = true
                if (pointers.size >= 2) {
                    val other = pointers.entries.first { it.key != id }.value
                    val oldDistance = hypot(previous.first - other.first, previous.second - other.second)
                    val newDistance = hypot(p.first - other.first, p.second - other.second)
                    if (oldDistance > 0) zoomAt(newDistance / oldDistance, (p.first + other.first) / 2, (p.second + other.second) / 2)
                } else if (dragged) {
                    cx -= (p.first - previous.first) / step
                    cy -= (p.second - previous.second) / step
                    constrainCenter()
                }
                pointers[id] = p
                cursor = null
            } else cursor = pointAt(p.first, p.second)
            draw()
        }
        canvas.asDynamic().onpointerup = { e: dynamic ->
            val p = local(e.clientX as Double, e.clientY as Double)
            pointers.remove(e.pointerId as Int)
            if (!dragged && enabled) pointAt(p.first, p.second)?.let(client::move)
            if (pointers.isEmpty()) down = null
            draw()
        }
        canvas.asDynamic().onpointercancel = { e: dynamic -> pointers.remove(e.pointerId as Int); dragged = true; down = null }
        canvas.asDynamic().onpointerleave = { _: dynamic -> if (pointers.isEmpty()) { cursor = null; draw() } }
        canvas.asDynamic().onwheel = { e: dynamic ->
            e.preventDefault()
            val p = local(e.clientX as Double, e.clientY as Double)
            zoomAt(exp(-(e.deltaY as Double) * 0.0015), p.first, p.second)
        }
        canvas.addEventListener("keydown", { event ->
            val e = event as KeyboardEvent
            val p = cursor ?: game.lastMove ?: if (game.kind == GameKind.DOTS) Point(14, 21) else Point(0, 0)
            val next = when (e.key) {
                "ArrowLeft" -> Point(maxOf(Int.MIN_VALUE.toLong(), p.x.toLong() - 1).toInt(), p.y)
                "ArrowRight" -> Point(minOf(Int.MAX_VALUE.toLong(), p.x.toLong() + 1).toInt(), p.y)
                "ArrowUp" -> Point(p.x, maxOf(Int.MIN_VALUE.toLong(), p.y.toLong() - 1).toInt())
                "ArrowDown" -> Point(p.x, minOf(Int.MAX_VALUE.toLong(), p.y.toLong() + 1).toInt())
                else -> null
            }
            when {
                next != null -> {
                    cursor = if (game.kind == GameKind.DOTS) Point(next.x.coerceIn(0, DOTS_COLUMNS - 1), next.y.coerceIn(0, DOTS_ROWS - 1)) else next
                    keyboard = true
                    val selected = cursor!!
                    if (abs((selected.x - cx) * step) > width / 2 - 40 || abs((selected.y - cy) * step) > height / 2 - 40) {
                        cx = selected.x.toDouble(); cy = selected.y.toDouble()
                    }
                    canvas.setAttribute("aria-label", "${if (game.kind == GameKind.DOTS) "Точка" else "Клетка"} ${selected.x}, ${selected.y}. Enter — сделать ход")
                }
                e.key == "Enter" || e.key == " " -> { cursor = p; if (enabled) client.move(p) }
                e.key == "+" || e.key == "=" -> zoom(1.25)
                e.key == "-" -> zoom(0.8)
                e.key == "Home" -> center()
                else -> return@addEventListener
            }
            e.preventDefault(); draw()
        })
        window.requestAnimationFrame { size() }
    }

    override fun update(node: HostNode) {
        val next = wireJson.decodeFromString<GameState>(node.props.getValue("state"))
        val reset = initialized && (next.kind != game.kind || next.stones.isEmpty() && game.stones.isNotEmpty())
        game = next
        enabled = node.props["enabled"] == "true"
        canvas.setAttribute("data-revision", game.revision.toString())
        canvas.setAttribute("data-stones", game.stones.size.toString())
        if (!keyboard) canvas.setAttribute("aria-label", "Игровое поле. Стрелки — выбор ${if (game.kind == GameKind.DOTS) "точки" else "клетки"}, Enter — ход, плюс и минус — масштаб")
        if (reset) fit()
        draw()
    }

    private fun size() {
        val rect = canvas.getBoundingClientRect()
        width = rect.width
        height = rect.height
        if (width <= 0 || height <= 0) return
        val ratio = window.devicePixelRatio
        canvas.width = (width * ratio).toInt()
        canvas.height = (height * ratio).toInt()
        ctx.setTransform(ratio, 0.0, 0.0, ratio, 0.0, 0.0)
        if (!initialized) { initialized = true; fit() }
        draw()
    }

    private fun fit() {
        if (game.kind == GameKind.DOTS) {
            step = minOf((width - 48) / (DOTS_COLUMNS - 1), (height - 48) / (DOTS_ROWS - 1)).coerceAtLeast(5.0)
            cx = (DOTS_COLUMNS - 1) / 2.0; cy = (DOTS_ROWS - 1) / 2.0
        } else {
            step = 32.0; cx = game.lastMove?.x?.toDouble() ?: 0.0; cy = game.lastMove?.y?.toDouble() ?: 0.0
        }
        cursor = null
    }

    fun center() {
        val last = game.lastMove
        if (last == null) fit() else { cx = last.x.toDouble(); cy = last.y.toDouble() }
        draw()
    }

    fun zoom(factor: Double) = zoomAt(factor, width / 2, height / 2)
    private fun zoomAt(factor: Double, x: Double, y: Double) {
        val bx = cx + (x - width / 2) / step
        val by = cy + (y - height / 2) / step
        step = (step * factor).coerceIn(5.0, 80.0)
        cx = bx - (x - width / 2) / step
        cy = by - (y - height / 2) / step
        constrainCenter(); draw()
    }

    private fun constrainCenter() {
        if (game.kind == GameKind.DOTS) { cx = cx.coerceIn(-5.0, DOTS_COLUMNS + 5.0); cy = cy.coerceIn(-5.0, DOTS_ROWS + 5.0) }
        else { cx = cx.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()); cy = cy.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()) }
    }

    private fun local(x: Double, y: Double): Pair<Double, Double> {
        val rect = canvas.getBoundingClientRect()
        return (x - rect.left) to (y - rect.top)
    }

    private fun pointAt(x: Double, y: Double): Point? {
        fun index(coordinate: Double) = if (game.kind == GameKind.DOTS) round(coordinate) else floor(coordinate + 0.5)
        val gx = index(cx + (x - width / 2) / step)
        val gy = index(cy + (y - height / 2) / step)
        if (gx < Int.MIN_VALUE || gx > Int.MAX_VALUE || gy < Int.MIN_VALUE || gy > Int.MAX_VALUE) return null
        val p = Point(gx.toInt(), gy.toInt())
        return p.takeIf { game.kind == GameKind.FIVE || it.x in 0 until DOTS_COLUMNS && it.y in 0 until DOTS_ROWS }
    }

    private fun sx(x: Double) = width / 2 + (x - cx) * step
    private fun sy(y: Double) = height / 2 + (y - cy) * step
    private fun color(player: Player) = if (player == Player.RED) "#c95346" else "#416caf"

    private fun draw() {
        if (!initialized) return
        ctx.clearRect(0.0, 0.0, width, height)
        ctx.fillStyle = "#fffef9"; ctx.fillRect(0.0, 0.0, width, height)
        val dots = game.kind == GameKind.DOTS
        // Dots use intersections; five-in-a-row coordinates refer to cell centers.
        val gridOffset = if (dots) 0.0 else -0.5
        val x0 = floor(cx - width / 2 / step).toLong().coerceAtLeast(if (dots) 0L else Int.MIN_VALUE.toLong())
        val x1 = ceil(cx + width / 2 / step).toLong().coerceAtMost(if (dots) (DOTS_COLUMNS - 1).toLong() else Int.MAX_VALUE.toLong())
        val y0 = floor(cy - height / 2 / step).toLong().coerceAtLeast(if (dots) 0L else Int.MIN_VALUE.toLong())
        val y1 = ceil(cy + height / 2 / step).toLong().coerceAtMost(if (dots) (DOTS_ROWS - 1).toLong() else Int.MAX_VALUE.toLong())
        ctx.lineWidth = 1.0
        for (x in x0..x1) {
            ctx.strokeStyle = if (x % 5 == 0L) "#ccd9d5" else "#e0e8e2"
            ctx.beginPath(); ctx.moveTo(sx(x.toDouble() + gridOffset), if (dots) sy(0.0) else 0.0)
            ctx.lineTo(sx(x.toDouble() + gridOffset), if (dots) sy((DOTS_ROWS - 1).toDouble()) else height); ctx.stroke()
        }
        for (y in y0..y1) {
            ctx.strokeStyle = if (y % 5 == 0L) "#ccd9d5" else "#e0e8e2"
            ctx.beginPath(); ctx.moveTo(if (dots) sx(0.0) else 0.0, sy(y.toDouble() + gridOffset))
            ctx.lineTo(if (dots) sx((DOTS_COLUMNS - 1).toDouble()) else width, sy(y.toDouble() + gridOffset)); ctx.stroke()
        }
        if (dots) {
            ctx.strokeStyle = "#a4b9ae"; ctx.lineWidth = 1.5
            ctx.strokeRect(sx(0.0), sy(0.0), (DOTS_COLUMNS - 1) * step, (DOTS_ROWS - 1) * step)
        } else {
            ctx.fillStyle = "#91a297"; ctx.font = "10px monospace"
            for (x in x0..x1) if (x % 5 == 0L) ctx.fillText(x.toString(), sx(x.toDouble()) + 4, height - 10)
            for (y in y0..y1) if (y % 5 == 0L) ctx.fillText(y.toString(), 8.0, sy(y.toDouble()) - 4)
        }
        val captured = game.territories.associate { it.point to it.player }
        game.territories.forEach { t ->
            ctx.fillStyle = if (t.player == Player.RED) "rgba(201,83,70,.16)" else "rgba(65,108,175,.16)"
            val x = sx(t.point.x.toDouble()); val y = sy(t.point.y.toDouble())
            // A diamond follows diagonal enclosures without painting beyond their boundary.
            ctx.beginPath(); ctx.moveTo(x, y - step); ctx.lineTo(x + step, y); ctx.lineTo(x, y + step); ctx.lineTo(x - step, y); ctx.closePath(); ctx.fill()
        }
        for (stone in game.stones) {
            val x = sx(stone.point.x.toDouble()); val y = sy(stone.point.y.toDouble())
            if (x !in -step..width + step || y !in -step..height + step) continue
            ctx.globalAlpha = if (captured[stone.point] == stone.player.other()) 0.35 else 1.0
            mark(x, y, stone.player, dots)
        }
        ctx.globalAlpha = 1.0
        if (game.winningLine.isNotEmpty()) {
            val first = game.winningLine.first(); val last = game.winningLine.last()
            ctx.strokeStyle = "#2e6757"; ctx.lineWidth = maxOf(2.0, step * 0.1)
            ctx.beginPath(); ctx.moveTo(sx(first.x.toDouble()), sy(first.y.toDouble())); ctx.lineTo(sx(last.x.toDouble()), sy(last.y.toDouble())); ctx.stroke()
        }
        game.lastMove?.let { p ->
            ctx.strokeStyle = "#394d41"; ctx.lineWidth = 1.2
            val r = if (dots) step * 0.39 else step * 0.44
            ctx.strokeRect(sx(p.x.toDouble()) - r, sy(p.y.toDouble()) - r, r * 2, r * 2)
        }
        cursor?.let { p ->
            val x = sx(p.x.toDouble()); val y = sy(p.y.toDouble())
            if (keyboard) { ctx.strokeStyle = "#2e6757"; ctx.lineWidth = 2.0; ctx.strokeRect(x - step / 2, y - step / 2, step, step) }
            if (enabled && game.stones.none { it.point == p } && p !in captured) {
                ctx.globalAlpha = 0.4; mark(x, y, game.turn, dots); ctx.globalAlpha = 1.0
            }
        }
    }

    private fun mark(x: Double, y: Double, player: Player, dots: Boolean) {
        ctx.strokeStyle = color(player); ctx.fillStyle = color(player)
        ctx.lineWidth = (step * 0.085).coerceIn(1.5, 4.0); ctx.asDynamic().lineCap = "round"
        val r = if (dots) (step * 0.22).coerceAtLeast(2.2) else step * 0.29
        ctx.beginPath()
        if (dots || player == Player.BLUE) { ctx.arc(x, y, r, 0.0, PI * 2); if (dots) ctx.fill() else ctx.stroke() }
        else { ctx.moveTo(x - r, y - r); ctx.lineTo(x + r, y + r); ctx.moveTo(x + r, y - r); ctx.lineTo(x - r, y + r); ctx.stroke() }
    }

    override fun requestFocus(): Boolean { canvas.focus(); return true }
    override fun dispose() {
        window.removeEventListener("resize", resize)
        canvas.asDynamic().onpointerdown = null; canvas.asDynamic().onpointermove = null
        canvas.asDynamic().onpointerup = null; canvas.asDynamic().onwheel = null
        if (client.board === this) client.board = null
    }
}
