package com.airkeys

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * TCP LAN transport: same frames, same AES-GCM, same seq/replay rules as
 * Bluetooth, over an ordinary TCP socket on the local network (phone
 * hotspot you already share, home Wi-Fi, or USB tethering — all are just
 * a LAN to this class). One writer thread (immediate flush for keys),
 * one reader thread, one 8 ms mouse coalescer, one 3 s ping loop.
 */
class TcpTransport(
    private val onDisconnected: () -> Unit,
    private val onPong: (Long) -> Unit,
    private val onPaired: () -> Unit,
) : InputTransport {
    companion object { private const val TAG = "AirKeysTcp" }

    @Volatile override var isConnected = false
    private var _framesSent = 0L
    override val framesSent: Long get() = _framesSent

    @Volatile var sessionKey: ByteArray? = null; private set
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false

    private val seq = AtomicInteger(0)
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    private val coalescer = MouseMoveCoalescer(8L)
    private val serverSeq = SeqValidator()

    private fun enqueue(type: Byte, payload: ByteArray) {
        val key = sessionKey ?: return
        val s = seq.incrementAndGet()
        try {
            sendQueue.put(Crypto.encryptFrame(key, ConnectionManager.DIR_CLIENT, type, s, payload))
            _framesSent++
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    override fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 8000) { enqueue(FrameType.TEXT, bytes); return }
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 8000, bytes.size)
            var safeEnd = end
            while (safeEnd > off && (bytes[safeEnd - 1].toInt() and 0xC0) == 0x80) safeEnd--
            if (safeEnd == off) safeEnd = end
            enqueue(FrameType.TEXT, bytes.copyOfRange(off, safeEnd)); off = safeEnd
        }
    }
    override fun sendBackspace(count: Int) = enqueue(FrameType.BACKSPACE, FrameCodec.backspacePayload(count))
    override fun sendKey(code: Int, down: Boolean) = enqueue(FrameType.KEY, FrameCodec.keyPayload(code, down))
    override fun sendCombo(modifiers: Int, code: Int, down: Boolean) =
        enqueue(FrameType.COMBO, FrameCodec.comboPayload(modifiers, code, down))
    override fun sendMouseButton(button: Int, down: Boolean) =
        enqueue(FrameType.MOUSE_BUTTON, FrameCodec.mouseButtonPayload(button, down))
    override fun sendScroll(dx: Int, dy: Int) = enqueue(FrameType.MOUSE_SCROLL, FrameCodec.mouseScrollPayload(dx, dy))
    override fun sendMouseMove(dx: Int, dy: Int) { coalescer.add(dx, dy) }
    private fun flushMouseMoves(nowMs: Long) {
        val d = coalescer.flushIfDue(nowMs) ?: return
        enqueue(FrameType.MOUSE_MOVE, FrameCodec.mouseMovePayload(d.first, d.second))
    }
    override fun flushMouseMovesNow(nowMs: Long) {
        val d = coalescer.flushNow(nowMs) ?: return
        enqueue(FrameType.MOUSE_MOVE, FrameCodec.mouseMovePayload(d.first, d.second))
    }
    private fun sendPing() = enqueue(FrameType.PING, FrameCodec.pingPayload(System.currentTimeMillis()))

    fun connect(host: String, key: ByteArray): Boolean {
        disconnectQuietly()
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, LanTransport.TCP_PORT), 3000)
            sock.tcpNoDelay = true
            finishConnect(sock, key, 3)
        } catch (e: Exception) { Log.w(TAG, "TCP connect failed", e); disconnectQuietly(); false }
    }

    fun connectWithCode(host: String, code: String): Boolean {
        val key = Crypto.deriveKeyFromCode(code)
        disconnectQuietly()
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, LanTransport.TCP_PORT), 3000)
            sock.tcpNoDelay = true
            finishConnect(sock, key, 1)
        } catch (e: Exception) { Log.w(TAG, "TCP connectWithCode failed", e); disconnectQuietly(); false }
    }

    private fun finishConnect(sock: Socket, key: ByteArray, pairStep: Int): Boolean {
        socket = sock; sessionKey = key; running = true; isConnected = true
        seq.set(0); serverSeq.reset()
        enqueue(FrameType.PAIR, FrameCodec.pairPayload(pairStep, android.os.Build.MODEL))
        startWriter(sock.getOutputStream())
        startReader(sock.getInputStream())
        startPingLoop(); startCoalesceLoop()
        return true
    }

    private fun startWriter(out: OutputStream) {
        thread(name = "airkeys-tcp-writer", isDaemon = true) {
            try {
                while (running) { val f = sendQueue.take(); out.write(f); out.flush() }
            } catch (e: Exception) { if (running) handleLoss() }
        }
    }

    private fun startReader(input: InputStream) {
        thread(name = "airkeys-tcp-reader", isDaemon = true) {
            val lenBuf = ByteArray(2)
            try {
                while (running) {
                    if (!readFully(input, lenBuf)) break
                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    if (len <= 0 || len > FrameCodec.MAX_FRAME) break
                    val body = ByteArray(len)
                    if (!readFully(input, body)) break
                    val key = sessionKey ?: break
                    val frame = try {
                        Crypto.decryptFrame(key, ConnectionManager.DIR_SERVER, body)
                    } catch (e: Exception) { Log.w(TAG, "bad frame, ignoring"); continue }
                    if (!serverSeq.accept(frame.seq)) continue
                    when (frame.type) {
                        FrameType.PONG -> onPong(FrameCodec.parsePing(frame.payload))
                        FrameType.PAIR -> onPaired()
                    }
                }
            } catch (e: Exception) { /* socket closed */ }
            if (running) handleLoss()
        }
    }

    private fun startPingLoop() {
        thread(name = "airkeys-tcp-ping", isDaemon = true) {
            while (running) {
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                if (running) sendPing()
            }
        }
    }

    private fun startCoalesceLoop() {
        thread(name = "airkeys-tcp-coalesce", isDaemon = true) {
            while (running) {
                try { Thread.sleep(8) } catch (e: InterruptedException) { break }
                if (running) flushMouseMoves(System.currentTimeMillis())
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun handleLoss() {
        if (!running) return
        running = false; isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        onDisconnected()
    }

    override fun disconnect() { running = false; isConnected = false; disconnectQuietly() }

    private fun disconnectQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; sendQueue.clear()
    }
}
