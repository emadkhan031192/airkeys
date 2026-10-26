package com.airkeys

import kotlin.math.abs
import kotlin.math.hypot

/**
 * UI-agnostic touchpad state machine. The Compose layer translates pointer
 * events into [PointerAction] calls; this class decides what mouse events
 * they mean. Fully unit-testable with no Android dependency.
 */
sealed class MouseEvent {
    data class Move(val dx: Int, val dy: Int) : MouseEvent()
    data class Button(val button: Int, val down: Boolean) : MouseEvent() // 0=L,1=R,2=M
    data class Scroll(val dx: Int, val dy: Int) : MouseEvent()
    object Click : MouseEvent() // convenience: Button(L,down)+Button(L,up) handled by caller if needed
}

enum class PointerAction { DOWN, MOVE, UP, CANCEL }

/**
 * Scroll-wheel strip accumulator (v1.3): converts drag pixels into
 * whole wheel clicks, keeping the remainder so slow drags still scroll
 * smoothly. 24 px per click. Works for both axes at once: drag up/down
 * = vertical clicks, drag left/right = horizontal clicks, diagonal =
 * both. Pure and unit-testable.
 */
class ScrollWheelAccumulator(private val pxPerClick: Float = 24f) {
    private var accX = 0f
    private var accY = 0f
    fun accumulate(dxPx: Float, dyPx: Float): Pair<Int, Int> {
        accX += dxPx; accY += dyPx
        val cx = (accX / pxPerClick).toInt()
        val cy = (accY / pxPerClick).toInt()
        accX -= cx * pxPerClick; accY -= cy * pxPerClick
        return cx to cy
    }
}

class TouchpadGestureProcessor(
    private val tapMaxMs: Long = 200L,
    private val tapMaxMovePx: Float = 12f,
    private val doubleTapMaxMs: Long = 300L,
    private val holdMs: Long = 400L,
    private val sensitivity: Float = 1.0f,
    private val palmMinArea: Float = 0f, // reserved: UI passes major-axis; >threshold = palm
    private val tapToClickEnabled: Boolean = true,
) {
    private data class Ptr(val id: Int, var x: Float, var y: Float, var downAt: Long,
                           var moved: Boolean, var startX: Float, var startY: Float)
    private val pointers = mutableMapOf<Int, Ptr>()
    private var lastTapUpMs: Long = -1
    private var lastTapX = 0f; private var lastTapY = 0f
    private var pendingDoubleTap = false
    private var dragging = false
    private var dragButtonHeld = false
    private var scrolling = false
    private var scrollLastX = 0f; private var scrollLastY = 0f

    /** Returns mouse events for this pointer action. Caller sends them in order. */
    fun onPointer(id: Int, action: PointerAction, x: Float, y: Float, nowMs: Long): List<MouseEvent> {
        val out = mutableListOf<MouseEvent>()
        when (action) {
            PointerAction.DOWN -> {
                val countBefore = pointers.size
                pointers[id] = Ptr(id, x, y, nowMs, false, x, y)
                if (countBefore == 0) {
                    // Possible double-tap-drag: second tap down quickly near last tap.
                    // Disabled with tap-to-click, since it also auto-clicks.
                    if (tapToClickEnabled && pendingDoubleTap && nowMs - lastTapUpMs <= doubleTapMaxMs &&
                        hypot(x - lastTapX, y - lastTapY) < 40f) {
                        dragging = true; dragButtonHeld = true
                        out += MouseEvent.Button(0, true)
                    }
                    pendingDoubleTap = false
                } else if (countBefore == 1) {
                    scrolling = true; scrollLastX = x; scrollLastY = y
                }
            }
            PointerAction.MOVE -> {
                val p = pointers[id] ?: return out
                val dx = x - p.x; val dy = y - p.y
                if (hypot(x - p.startX, y - p.startY) > tapMaxMovePx) p.moved = true
                p.x = x; p.y = y
                if (scrolling && pointers.size == 2) {
                    // Two-finger drag = scroll. Use the moving finger's delta.
                    out += MouseEvent.Scroll((dx * sensitivity).toInt(), (dy * sensitivity).toInt())
                    scrollLastX = x; scrollLastY = y
                } else if (pointers.size == 1) {
                    if (dragging) {
                        out += MouseEvent.Move((dx * sensitivity).toInt(), (dy * sensitivity).toInt())
                    } else if (nowMs - p.downAt >= holdMs && !p.moved) {
                        // Tap-and-hold will become a drag on first real move;
                        // the hold itself does not click yet.
                    } else {
                        out += MouseEvent.Move((dx * sensitivity).toInt(), (dy * sensitivity).toInt())
                    }
                }
            }
            PointerAction.UP, PointerAction.CANCEL -> {
                val p = pointers.remove(id)
                if (p == null) return out
                val duration = nowMs - p.downAt
                val wasTap = !p.moved && duration <= tapMaxMs && action == PointerAction.UP
                if (pointers.isEmpty()) {
                    if (dragButtonHeld) { out += MouseEvent.Button(0, false); dragButtonHeld = false; dragging = false }
                    if (scrolling) scrolling = false
                    if (wasTap && !tapToClickEnabled) {
                        // Tap-to-click off: a lift is just a lift, never a click.
                        pendingDoubleTap = false
                    } else if (wasTap) {
                        // Finger count at tap time is approximated by whether a
                        // second finger was ever present; the UI layer passes
                        // multi-tap via tapWithFingers() instead (see below).
                        if (pendingDoubleTap) {
                            // This UP was the second tap: emit double click.
                            out += MouseEvent.Button(0, true); out += MouseEvent.Button(0, false)
                            out += MouseEvent.Button(0, true); out += MouseEvent.Button(0, false)
                            pendingDoubleTap = false
                        } else {
                            out += MouseEvent.Button(0, true); out += MouseEvent.Button(0, false)
                            pendingDoubleTap = true; lastTapUpMs = nowMs; lastTapX = p.x; lastTapY = p.y
                        }
                    } else if (duration >= holdMs && !p.moved) {
                        // Long-press without move: treat as start of drag is
                        // handled by the UI holding; no click emitted here.
                    }
                } else {
                    // A finger lifted while others remain: if it was a quick
                    // multi-finger tap, the UI calls tapWithFingers().
                    if (scrolling && pointers.size < 2) scrolling = false
                }
            }
        }
        return out
    }

    /** Explicit multi-finger tap (UI detects all fingers down+up quickly). */
    fun tapWithFingers(fingers: Int): List<MouseEvent> {
        if (!tapToClickEnabled) return emptyList()
        val button = when (fingers) { 2 -> 1; 3 -> 2; else -> 0 }
        pendingDoubleTap = false
        return listOf(MouseEvent.Button(button, true), MouseEvent.Button(button, false))
    }

    /** Called by UI when a one-finger hold exceeds holdMs: begin drag. */
    fun beginHoldDrag(): List<MouseEvent> {
        if (pointers.size == 1 && !dragButtonHeld) {
            dragging = true; dragButtonHeld = true
            return listOf(MouseEvent.Button(0, true))
        }
        return emptyList()
    }

    fun reset() {
        pointers.clear(); pendingDoubleTap = false; dragging = false
        dragButtonHeld = false; scrolling = false
    }
}
