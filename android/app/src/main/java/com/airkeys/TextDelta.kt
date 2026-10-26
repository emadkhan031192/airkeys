package com.airkeys

/**
 * Pure delta logic for the hidden keyboard field.
 * The phone's keyboard (Gboard/SwiftKey, swipe, voice, predictive) edits a
 * hidden text buffer; we only ever send what changed, never the whole buffer.
 * This keeps predictive/swipe text correct on the PC and avoids echoing.
 */
data class TextDelta(val deleted: Int, val inserted: String) {
    companion object {
        fun compute(old: String, new: String): TextDelta {
            if (old == new) return TextDelta(0, "")
            var prefix = 0
            val minLen = minOf(old.length, new.length)
            while (prefix < minLen && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < minLen - prefix &&
                old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            val deleted = old.length - prefix - suffix
            val inserted = new.substring(prefix, new.length - suffix)
            return TextDelta(deleted, inserted)
        }
    }
}

/**
 * Coalesces mouse moves: accumulate deltas, flush at most once per window.
 * Callers call add() on every move and flushIfDue(nowMs) on a ~8 ms ticker.
 * Stale moves are dropped by design — only the latest accumulated delta ships.
 */
class MouseMoveCoalescer(private val windowMs: Long = 8L) {
    private var accX = 0; private var accY = 0
    private var lastFlushMs: Long = -1
    private var hasData = false

    fun add(dx: Int, dy: Int) { accX += dx; accY += dy; hasData = true }

    /** Returns the accumulated delta if the window elapsed, else null. */
    fun flushIfDue(nowMs: Long): Pair<Int, Int>? {
        if (!hasData) return null
        if (lastFlushMs >= 0 && nowMs - lastFlushMs < windowMs) return null
        val out = accX to accY; accX = 0; accY = 0; hasData = false; lastFlushMs = nowMs
        return out
    }

    /** Immediate flush, used on finger-up so the final delta is never lost. */
    fun flushNow(nowMs: Long): Pair<Int, Int>? {
        if (!hasData) return null
        val out = accX to accY; accX = 0; accY = 0; hasData = false; lastFlushMs = nowMs
        return out
    }
}
