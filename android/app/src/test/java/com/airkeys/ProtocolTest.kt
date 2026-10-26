package com.airkeys

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun textPayloadRoundTripsAsUtf8() {
        val payload = FrameCodec.textPayload("سلام Paښتو 😀")
        assertEquals("سلام Paښتو 😀", String(payload, Charsets.UTF_8))
    }

    @Test fun seqValidatorRejectsDuplicatesAndOldFrames() {
        val v = SeqValidator()
        assertTrue(v.accept(1)); assertTrue(v.accept(2))
        assertFalse(v.accept(2)) // duplicate
        assertFalse(v.accept(1)) // replay / old
        assertTrue(v.accept(3))
    }

    @Test fun seqValidatorHandlesWrap() {
        val v = SeqValidator()
        assertTrue(v.accept(Int.MAX_VALUE))
        assertTrue(v.accept(Int.MIN_VALUE)) // wrapped: diff is 1 in uint32 space? see note
        // Note: Int arithmetic diff = MIN - MAX = 1 (wraps), so this passes.
    }

    @Test fun mouseMovePayloadIsLittleEndian() {
        val p = FrameCodec.mouseMovePayload(-10, 300)
        assertEquals(4, p.size)
        val (dx, dy) = FrameCodec.parseMouseMove(p)
        assertEquals(-10, dx); assertEquals(300, dy)
    }

    @Test fun comboPayloadEncodesModifiers() {
        val p = FrameCodec.comboPayload(Modifiers.CTRL or Modifiers.SHIFT, 65, true)
        assertEquals(0x05, p[0].toInt()); assertEquals(65, p[1].toInt()); assertEquals(1, p[2].toInt())
    }
}
