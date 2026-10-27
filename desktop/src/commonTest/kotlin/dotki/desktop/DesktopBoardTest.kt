package dotki.desktop

import dotki.*
import io.heapy.kinetica.canvas.*
import kotlin.test.*

class DesktopBoardTest {
    @Test fun fiveMarksAndClicksUseCellCenters() {
        val b=DesktopBoard();b.update(GameState(GameKind.FIVE))
        val x=b.width/2;val y=b.height/2
        assertEquals(Point(0,0),b.pointAt(x,y))
        assertEquals(Point(0,0),b.pointAt(x+15,y+15))
        assertEquals(Point(1,1),b.pointAt(x+17,y+17))
        assertEquals(Point(-1,-1),b.pointAt(x-17,y-17))
        val lines=b.model().shapes.filterIsInstance<CanvasShape.Line>()
        assertTrue(lines.any { it.x1==x-16 && it.x2==x-16 })
        assertFalse(lines.any { it.x1==x && it.x2==x })
    }
    @Test fun draggingDoesNotPlaceAndZoomKeepsPointerPosition() {
        val b=DesktopBoard();b.update(GameState(GameKind.FIVE));val moves=mutableListOf<Point>()
        b.input(CanvasInput.Pointer(PointerPhase.DOWN,320.0,310.0),true,moves::add)
        b.input(CanvasInput.Pointer(PointerPhase.MOVE,384.0,310.0),true,moves::add)
        b.input(CanvasInput.Pointer(PointerPhase.UP,384.0,310.0),true,moves::add)
        assertTrue(moves.isEmpty())
        val before=b.pointAt(400.0,320.0);b.zoom(1.5,400.0,320.0)
        assertEquals(before,b.pointAt(400.0,320.0))
        b.input(CanvasInput.Pointer(PointerPhase.DOWN,400.0,320.0),false,moves::add)
        b.input(CanvasInput.Pointer(PointerPhase.UP,400.0,320.0),false,moves::add)
        assertTrue(moves.isEmpty())
    }
    @Test fun dotsFitAllA5IntersectionsAndKeyboardCannotLeaveSheet() {
        val b=DesktopBoard();b.fit();val moves=mutableListOf<Point>()
        assertNull(b.pointAt(-1000.0,-1000.0))
        repeat(100){b.input(CanvasInput.Key("ArrowLeft"),true,moves::add)}
        b.input(CanvasInput.Key("Enter"),true,moves::add)
        assertEquals(Point(0,21),moves.single())
        assertTrue(b.model().shapes.size<500)
    }
    @Test fun farFivePositionsDoNotWrapAtIntBoundary() {
        val b=DesktopBoard()
        b.update(GameState(GameKind.FIVE,lastMove=Point(Int.MAX_VALUE,Int.MIN_VALUE)))
        b.center()
        val moves=mutableListOf<Point>()
        b.input(CanvasInput.Key("ArrowRight"),true,moves::add)
        b.input(CanvasInput.Key("ArrowUp"),true,moves::add)
        b.input(CanvasInput.Key("Enter"),true,moves::add)
        assertEquals(Point(Int.MAX_VALUE,Int.MIN_VALUE),moves.single())
    }
}
