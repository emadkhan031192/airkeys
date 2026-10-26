package com.airkeys

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoTest {
    @Test fun encryptDecryptRoundTrip() {
        val key = Crypto.deriveKeyFromCode("123456")
        val frame = Crypto.encryptFrame(key, 0x00, FrameType.TEXT,
            42, "hello دنیا".toByteArray(Charsets.UTF_8))
        // Strip 2-byte length prefix to get the body.
        val body = frame.copyOfRange(2, frame.size)
        val out = Crypto.decryptFrame(key, 0x00, body)
        assertEquals(FrameType.TEXT, out.type)
        assertEquals(42, out.seq)
        assertEquals("hello دنیا", String(out.payload, Charsets.UTF_8))
    }

    @Test fun tamperedFrameFailsAuthentication() {
        val key = Crypto.deriveKeyFromCode("123456")
        val frame = Crypto.encryptFrame(key, 0x00, FrameType.TEXT, 7, "x".toByteArray())
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        try {
            Crypto.decryptFrame(key, 0x00, frame.copyOfRange(2, frame.size))
            fail("Expected AEADBadTagException")
        } catch (e: AEADBadTagException) { /* expected */ }
    }

    @Test fun wrongCodeCannotDecrypt() {
        val keyA = Crypto.deriveKeyFromCode("123456")
        val keyB = Crypto.deriveKeyFromCode("654321")
        val frame = Crypto.encryptFrame(keyA, 0x00, FrameType.TEXT, 1, "x".toByteArray())
        try {
            Crypto.decryptFrame(keyB, 0x00, frame.copyOfRange(2, frame.size))
            fail("Expected AEADBadTagException")
        } catch (e: AEADBadTagException) { /* expected */ }
    }

    @Test fun deriveIsDeterministic() {
        assertArrayEquals(Crypto.deriveKeyFromCode("000001"), Crypto.deriveKeyFromCode("000001"))
    }
}
