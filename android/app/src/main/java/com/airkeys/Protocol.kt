package com.airkeys

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire protocol v1. Mirrored exactly by windows/AirKeys/Protocol.cs. */
object FrameType {
    const val TEXT: Byte = 0x01
    const val BACKSPACE: Byte = 0x02
    const val KEY: Byte = 0x03
    const val COMBO: Byte = 0x04
    const val MOUSE_MOVE: Byte = 0x05
    const val MOUSE_BUTTON: Byte = 0x06
    const val MOUSE_SCROLL: Byte = 0x07
    const val PING: Byte = 0x08
    const val PONG: Byte = 0x09
    const val PAIR: Byte = 0x0A
}

object KeyCode {
    const val BACKSPACE = 8; const val TAB = 9; const val ENTER = 13
    const val ESC = 27; const val SPACE = 32; const val PAGE_UP = 33
    const val PAGE_DOWN = 34; const val END = 35; const val HOME = 36
    const val LEFT = 37; const val UP = 38; const val RIGHT = 39; const val DOWN = 40
    const val DELETE = 46
    fun f(n: Int): Int = 111 + n // F1=112
}

object Modifiers {
    const val CTRL = 0x01; const val ALT = 0x02; const val SHIFT = 0x04; const val WIN = 0x08
}

data class Frame(val type: Byte, val seq: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && seq == other.seq && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var r = type.toInt(); r = 31 * r + seq; r = 31 * r + payload.contentHashCode(); return r
    }
}

/** Replay protection: accept iff seq is newer than lastSeq in uint32 space. */
class SeqValidator {
    private var lastSeq: Int = 0
    private var seenAny = false
    fun accept(seq: Int): Boolean {
        if (!seenAny) { seenAny = true; lastSeq = seq; return true }
        if (seq == lastSeq) return false
        val diff = seq - lastSeq // wraps in Int arithmetic, exactly what we want
        if (diff <= 0 || diff >= (1 shl 31)) return false
        lastSeq = seq; return true
    }
    fun reset() { lastSeq = 0; seenAny = false }
}

object FrameCodec {
    const val MAX_FRAME = 8192
    const val TAG_LEN = 16

    /** Encode WITHOUT encryption for tests / for the crypto layer to wrap.
     *  Production path is Crypto.encryptFrame which produces the same layout
     *  with the tag computed by AES-GCM. This function builds the
     *  type||seq||payload body and is shared by both paths. */
    fun buildBody(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(1 + 4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(type); buf.putInt(seq); buf.put(payload); return buf.array()
    }

    fun textPayload(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
    fun backspacePayload(count: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(count.toShort()).array()
    fun keyPayload(code: Int, down: Boolean): ByteArray =
        byteArrayOf(code.toByte(), if (down) 1 else 0)
    fun comboPayload(modifiers: Int, code: Int, down: Boolean): ByteArray =
        byteArrayOf(modifiers.toByte(), code.toByte(), if (down) 1 else 0)
    fun mouseMovePayload(dx: Int, dy: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(dx.toShort()).putShort(dy.toShort()).array()
    fun mouseButtonPayload(button: Int, down: Boolean): ByteArray =
        byteArrayOf(button.toByte(), if (down) 1 else 0)
    fun mouseScrollPayload(dx: Int, dy: Int): ByteArray = mouseMovePayload(dx, dy)
    fun pingPayload(millis: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(millis).array()
    fun pairPayload(step: Int, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(step.toByte()) + n
    }

    fun parseMouseMove(payload: ByteArray): Pair<Int, Int> {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return b.short.toInt() to b.short.toInt()
    }
    fun parsePing(payload: ByteArray): Long =
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).long
}
