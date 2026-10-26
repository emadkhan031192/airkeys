package com.airkeys

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDeltaTest {
    @Test fun insertion() {
        assertEquals(TextDelta(0, "a"), TextDelta.compute("", "a"))
        assertEquals(TextDelta(0, "llo"), TextDelta.compute("he", "hello"))
    }
    @Test fun deletion() {
        assertEquals(TextDelta(1, ""), TextDelta.compute("hello", "hell"))
        assertEquals(TextDelta(3, ""), TextDelta.compute("hello", "he"))
    }
    @Test fun replaceMiddle() {
        assertEquals(TextDelta(1, "a"), TextDelta.compute("hello", "healo"))
    }
    @Test fun swipeLikeWholeWordReplace() {
        // Gboard swipe often replaces a whole composing word at once.
        assertEquals(TextDelta(3, "world"), TextDelta.compute("hel", "world"))
    }
    @Test fun noChange() { assertEquals(TextDelta(0, ""), TextDelta.compute("abc", "abc")) }
    @Test fun unicodeInsertion() {
        assertEquals(TextDelta(0, "سلام"), TextDelta.compute("", "سلام"))
    }
}

class CoalescerTest {
    @Test fun accumulatesThenFlushes() {
        val c = MouseMoveCoalescer(8L)
        c.add(5, 0)
        val first = c.flushIfDue(100)
        assertEquals(5 to 0, first)
        c.add(1, 1); c.add(2, 2)
        assertEquals(null, c.flushIfDue(105)) // within window
        assertEquals(3 to 3, c.flushIfDue(200))
        assertEquals(null, c.flushNow(201)) // nothing left
    }
}
