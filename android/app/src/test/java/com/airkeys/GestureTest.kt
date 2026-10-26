package com.airkeys

import org.junit.Assert.*
import org.junit.Test

class GestureTest {
    @Test fun singleTapEmitsLeftClick() {
        val g = TouchpadGestureProcessor()
        assertTrue(g.onPointer(1, PointerAction.DOWN, 100f, 100f, 1000).isEmpty())
        val up = g.onPointer(1, PointerAction.UP, 101f, 101f, 1100)
        assertEquals(listOf(MouseEvent.Button(0, true), MouseEvent.Button(0, false)), up)
    }

    @Test fun oneFingerMoveEmitsMove() {
        val g = TouchpadGestureProcessor()
        g.onPointer(1, PointerAction.DOWN, 0f, 0f, 1000)
        val moves = g.onPointer(1, PointerAction.MOVE, 20f, 5f, 1010)
        assertEquals(listOf(MouseEvent.Move(20, 5)), moves)
    }

    @Test fun twoFingerTapEmitsRightClick() {
        val g = TouchpadGestureProcessor()
        assertEquals(listOf(MouseEvent.Button(1, true), MouseEvent.Button(1, false)),
            g.tapWithFingers(2))
    }

    @Test fun threeFingerTapEmitsMiddleClick() {
        val g = TouchpadGestureProcessor()
        assertEquals(listOf(MouseEvent.Button(2, true), MouseEvent.Button(2, false)),
            g.tapWithFingers(3))
    }

    @Test fun twoFingerDragEmitsScroll() {
        val g = TouchpadGestureProcessor()
        g.onPointer(1, PointerAction.DOWN, 0f, 0f, 1000)
        g.onPointer(2, PointerAction.DOWN, 50f, 0f, 1010)
        val scroll = g.onPointer(2, PointerAction.MOVE, 50f, -30f, 1020)
        assertEquals(listOf(MouseEvent.Scroll(0, -30)), scroll)
    }

    @Test fun tapToClickOffEmitsNoClick() {
        val g = TouchpadGestureProcessor(tapToClickEnabled = false)
        g.onPointer(1, PointerAction.DOWN, 100f, 100f, 1000)
        assertTrue(g.onPointer(1, PointerAction.UP, 101f, 101f, 1100).isEmpty())
        assertTrue(g.tapWithFingers(2).isEmpty())
    }

    @Test fun scrollWheelBothAxes() {
        val w = ScrollWheelAccumulator()
        // 50 px down = 2 vertical clicks, 2 px kept for later.
        assertEquals(0 to 2, w.accumulate(0f, 50f))
        // 30 px left + the kept 2 px vertical remainder: -1 horizontal,
        // vertical still below one click, so 0 vertical.
        assertEquals(-1 to 0, w.accumulate(-30f, 10f))
        // Diagonal: both axes at once.
        assertEquals(1 to 1, w.accumulate(30f, 30f))
    }

    @Test fun holdDragBeginsOnHold() {
        val g = TouchpadGestureProcessor()
        g.onPointer(1, PointerAction.DOWN, 10f, 10f, 1000)
        assertEquals(listOf(MouseEvent.Button(0, true)), g.beginHoldDrag())
    }
}
