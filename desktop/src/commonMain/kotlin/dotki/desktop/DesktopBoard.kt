package dotki.desktop

import dotki.*
import io.heapy.kinetica.canvas.*
import io.heapy.kinetica.store
import kotlin.math.*

/** All drawing and input are expressed in Kinetica values; no native view or browser APIs. */
class DesktopBoard {
    val changes = store(0)
    var game = GameState(GameKind.DOTS)
        private set
    var width = 640.0
        private set
    var height = 620.0
        private set
    var step = 13.0
        private set
    private var cx = 14.5
    private var cy = 21.0
    private var cursor: Point? = null
    private var down: Pair<Double, Double>? = null
    private var previous: Pair<Double, Double>? = null
    private var dragged = false
    private var navigated = false

    fun update(next: GameState) {
        val reset = next.kind != game.kind || next.stones.isEmpty() && game.stones.isNotEmpty()
        game = next
        if (reset) fit() else changes.value++
    }
    fun fit() {
        navigated = false
        if (game.kind == GameKind.DOTS) {
            cx = 14.5; cy = 21.0
            step = min((width - 48) / 29, (height - 48) / 42).coerceAtLeast(5.0)
        } else { cx = 0.0; cy = 0.0; step = 32.0 }
        cursor = null; changes.value++
    }
    fun center() {
        val last = game.lastMove
        if (last == null) fit() else { cx = last.x.toDouble(); cy = last.y.toDouble(); changes.value++ }
    }
    fun zoom(factor: Double, x: Double = width / 2, y: Double = height / 2) {
        if (!factor.isFinite() || factor <= 0) return
        val bx = cx + (x - width / 2) / step; val by = cy + (y - height / 2) / step
        step = (step * factor).coerceIn(5.0, 80.0)
        cx = bx - (x - width / 2) / step; cy = by - (y - height / 2) / step
        constrain(); navigated = true; changes.value++
    }
    fun pointAt(x: Double, y: Double): Point? {
        fun index(v: Double) = if (game.kind == GameKind.DOTS) round(v) else floor(v + 0.5)
        val gx = index(cx + (x - width / 2) / step); val gy = index(cy + (y - height / 2) / step)
        if (!gx.isFinite() || !gy.isFinite() || gx < Int.MIN_VALUE || gx > Int.MAX_VALUE || gy < Int.MIN_VALUE || gy > Int.MAX_VALUE) return null
        return Point(gx.toInt(), gy.toInt()).takeIf { game.kind == GameKind.FIVE || it.x in 0..29 && it.y in 0..42 }
    }
    fun input(input: CanvasInput, enabled: Boolean, move: (Point) -> Unit) {
        when (input) {
            is CanvasInput.Resize -> if (input.width > 0 && input.height > 0 && (width != input.width || height != input.height)) {
                width = input.width; height = input.height
                if (!navigated) fit() else changes.value++
            }
            is CanvasInput.Scroll -> zoom(exp(-input.deltaY * 0.003), input.x, input.y)
            is CanvasInput.Zoom -> zoom(input.factor, input.x, input.y)
            is CanvasInput.Pointer -> when (input.phase) {
                PointerPhase.DOWN -> { down = input.x to input.y; previous = down; dragged = false }
                PointerPhase.MOVE -> {
                    val start = down; val prev = previous
                    if (start != null && prev != null) {
                        if (hypot(input.x - start.first, input.y - start.second) > 5) dragged = true
                        if (dragged) { cx -= (input.x - prev.first) / step; cy -= (input.y - prev.second) / step; navigated = true; constrain(); changes.value++ }
                        previous = input.x to input.y
                    }
                }
                PointerPhase.UP -> {
                    if (down != null && !dragged && enabled) pointAt(input.x, input.y)?.let(move)
                    down = null; previous = null
                }
            }
            is CanvasInput.Key -> {
                val p = cursor ?: game.lastMove ?: if (game.kind == GameKind.DOTS) Point(14, 21) else Point(0, 0)
                fun offset(dx: Int, dy: Int) = Point((p.x.toLong() + dx).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                    (p.y.toLong() + dy).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                val next = when (input.key) { "ArrowLeft" -> offset(-1, 0); "ArrowRight" -> offset(1, 0); "ArrowUp" -> offset(0, -1); "ArrowDown" -> offset(0, 1); else -> null }
                when {
                    next != null -> {
                        cursor = if (game.kind == GameKind.DOTS) Point(next.x.coerceIn(0, 29), next.y.coerceIn(0, 42)) else next
                        val target = cursor!!
                        if (abs((target.x - cx) * step) > width / 2 - 32 || abs((target.y - cy) * step) > height / 2 - 32) { cx = target.x.toDouble(); cy = target.y.toDouble() }
                        changes.value++
                    }
                    input.key == "Enter" || input.key == " " -> if (enabled) move(p)
                    input.key == "+" || input.key == "=" -> zoom(1.25)
                    input.key == "-" -> zoom(0.8)
                    input.key == "Home" -> center()
                }
            }
        }
    }
    private fun constrain() {
        cx = cx.coerceIn(if (game.kind == GameKind.DOTS) -5.0 else Int.MIN_VALUE.toDouble(), if (game.kind == GameKind.DOTS) 35.0 else Int.MAX_VALUE.toDouble())
        cy = cy.coerceIn(if (game.kind == GameKind.DOTS) -5.0 else Int.MIN_VALUE.toDouble(), if (game.kind == GameKind.DOTS) 48.0 else Int.MAX_VALUE.toDouble())
    }
    private fun sx(x: Double) = width / 2 + (x - cx) * step
    private fun sy(y: Double) = height / 2 + (y - cy) * step
    private fun color(player: Player, alpha: Double = 1.0) = if (player == Player.RED) CanvasColor(.79, .325, .275, alpha) else CanvasColor(.255, .424, .686, alpha)

    fun model(): CanvasModel {
        changes.value
        val dots = game.kind == GameKind.DOTS
        val offset = if (dots) 0.0 else -0.5
        val x0 = floor(cx - width / 2 / step).toLong().coerceAtLeast(if (dots) 0 else Int.MIN_VALUE.toLong())
        val x1 = ceil(cx + width / 2 / step).toLong().coerceAtMost(if (dots) 29 else Int.MAX_VALUE.toLong())
        val y0 = floor(cy - height / 2 / step).toLong().coerceAtLeast(if (dots) 0 else Int.MIN_VALUE.toLong())
        val y1 = ceil(cy + height / 2 / step).toLong().coerceAtMost(if (dots) 42 else Int.MAX_VALUE.toLong())
        val shapes = buildList<CanvasShape> {
            fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: CanvasColor, width: Double = 1.0) { add(CanvasShape.Line(x1, y1, x2, y2, color, width)) }
            fun box(x: Double, y: Double, r: Double, color: CanvasColor) {
                line(x-r,y-r,x+r,y-r,color); line(x+r,y-r,x+r,y+r,color); line(x+r,y+r,x-r,y+r,color); line(x-r,y+r,x-r,y-r,color)
            }
            for (x in x0..x1) line(sx(x + offset), if (dots) sy(0.0) else 0.0, sx(x + offset), if (dots) sy(42.0) else height,
                if (x % 5 == 0L) CanvasColor(.80,.85,.83) else CanvasColor(.88,.91,.89))
            for (y in y0..y1) line(if (dots) sx(0.0) else 0.0, sy(y + offset), if (dots) sx(29.0) else width, sy(y + offset),
                if (y % 5 == 0L) CanvasColor(.80,.85,.83) else CanvasColor(.88,.91,.89))
            val captured = game.territories.associate { it.point to it.player }
            for (territory in game.territories) {
                val x = sx(territory.point.x.toDouble()); val y = sy(territory.point.y.toDouble())
                add(CanvasShape.Polygon(listOf(CanvasPoint(x,y-step),CanvasPoint(x+step,y),CanvasPoint(x,y+step),CanvasPoint(x-step,y)),color(territory.player,.16)))
            }
            for (stone in game.stones) {
                val x = sx(stone.point.x.toDouble()); val y = sy(stone.point.y.toDouble())
                if (x !in -step..width+step || y !in -step..height+step) continue
                val color = color(stone.player, if (captured[stone.point] == stone.player.other()) .35 else 1.0)
                val r = if (dots) (step * .22).coerceAtLeast(2.2) else step * .29
                if (dots) add(CanvasShape.Circle(x,y,r,fill=color))
                else if (stone.player == Player.BLUE) add(CanvasShape.Circle(x,y,r,stroke=color,strokeWidth=(step*.085).coerceIn(1.5,4.0)))
                else { line(x-r,y-r,x+r,y+r,color,2.5); line(x-r,y+r,x+r,y-r,color,2.5) }
            }
            if (game.winningLine.isNotEmpty()) {
                val a=game.winningLine.first(); val b=game.winningLine.last()
                line(sx(a.x.toDouble()),sy(a.y.toDouble()),sx(b.x.toDouble()),sy(b.y.toDouble()),CanvasColor(.18,.40,.34),3.0)
            }
            game.lastMove?.let { box(sx(it.x.toDouble()),sy(it.y.toDouble()),step*.43,CanvasColor(.22,.30,.25)) }
            cursor?.let { box(sx(it.x.toDouble()),sy(it.y.toDouble()),step*.48,CanvasColor(.18,.40,.34)) }
        }
        return CanvasModel(width,height,CanvasColor(1.0,.996,.976),shapes)
    }
}
