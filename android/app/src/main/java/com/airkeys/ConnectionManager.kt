package com.airkeys

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Owns the single RFCOMM socket. One writer thread (seq-ordered, immediate
 * flush for reliable frames), one reader thread, one 8 ms coalescer ticker,
 * one ping ticker. No polling of the UI thread, no batching of keys.
 *
 * Threading contract: all send*() calls are thread-safe and return
 * immediately; the writer thread does the socket write. The reader thread
 * delivers PONGs via onPong and connection loss via onDisconnected.
 */
class ConnectionManager(
    private val onDisconnected: () -> Unit,
    private val onPong: (Long) -> Unit,
    private val onPaired: () -> Unit,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a3e9b20-4f1c-4a2d-9c7e-1a2b3c4d5e6f")
        const val DIR_CLIENT: Byte = 0x00
        const val DIR_SERVER: Byte = 0x01
        private const val TAG = "AirKeysConn"
    }

    @Volatile var isConnected = false; private set
    @Volatile var sessionKey: ByteArray? = null; private set
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false

    private val seq = AtomicInteger(0)
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    private val coalescer = MouseMoveCoalescer(8L)
    private val serverSeq = SeqValidator()

    // Reliable send: build + encrypt + enqueue, writer flushes immediately.
    private fun enqueue(type: Byte, payload: ByteArray) {
        val key = sessionKey ?: return
        val s = seq.incrementAndGet()
        try { sendQueue.put(Crypto.encryptFrame(key, DIR_CLIENT, type, s, payload)) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    fun sendText(text: String) {
        // Split oversized text into ordered frames (max payload 8000).
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 8000) { enqueue(FrameType.TEXT, bytes); return }
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 8000, bytes.size)
            // Avoid splitting a UTF-8 sequence: back off to a char boundary.
            var safeEnd = end
            while (safeEnd > off && (bytes[safeEnd - 1].toInt() and 0xC0) == 0x80) safeEnd--
            if (safeEnd == off) safeEnd = end
            enqueue(FrameType.TEXT, bytes.copyOfRange(off, safeEnd)); off = safeEnd
        }
    }
    fun sendBackspace(count: Int) = enqueue(FrameType.BACKSPACE, FrameCodec.backspacePayload(count))
    fun sendKey(code: Int, down: Boolean) = enqueue(FrameType.KEY, FrameCodec.keyPayload(code, down))
    fun sendCombo(modifiers: Int, code: Int, down: Boolean) =
        enqueue(FrameType.COMBO, FrameCodec.comboPayload(modifiers, code, down))
    fun sendMouseButton(button: Int, down: Boolean) =
        enqueue(FrameType.MOUSE_BUTTON, FrameCodec.mouseButtonPayload(button, down))
    fun sendScroll(dx: Int, dy: Int) = enqueue(FrameType.MOUSE_SCROLL, FrameCodec.mouseScrollPayload(dx, dy))
    fun sendMouseMove(dx: Int, dy: Int) { coalescer.add(dx, dy) }

    fun flushMouseMoves(nowMs: Long) {
        val d = coalescer.flushIfDue(nowMs) ?: return
        enqueue(FrameType.MOUSE_MOVE, FrameCodec.mouseMovePayload(d.first, d.second))
    }
    fun flushMouseMovesNow(nowMs: Long) {
        val d = coalescer.flushNow(nowMs) ?: return
        enqueue(FrameType.MOUSE_MOVE, FrameCodec.mouseMovePayload(d.first, d.second))
    }

    private fun sendPing() = enqueue(FrameType.PING, FrameCodec.pingPayload(System.currentTimeMillis()))

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, key: ByteArray, onPairStep: (Int) -> Unit): Boolean {
        disconnectQuietly()
        return try {
            val sock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            sock.connect()
            socket = sock; sessionKey = key; running = true; isConnected = true
            seq.set(0); serverSeq.reset()
            // PAIR handshake first, before any input frame.
            val step = if (key.isNotEmpty()) 3 else 1
            // The caller decides step via stored-key presence; we always send
            // step 3 here when a stored key was supplied, step 1 otherwise is
            // handled by connectWithCode below. Keep this path as reconnect.
            enqueue(FrameType.PAIR, FrameCodec.pairPayload(3, Build.MODEL))
            onPairStep(3)
            startWriter(sock.outputStream)
            startReader(sock.inputStream)
            startPingLoop()
            startCoalesceLoop()
            onPaired()
            true
        } catch (e: IOException) {
            Log.w(TAG, "connect failed", e); disconnectQuietly(); false
        }
    }

    /** First pairing: derive key from the 6-digit code, then connect. */
    @SuppressLint("MissingPermission")
    fun connectWithCode(device: BluetoothDevice, code: String): Boolean {
        val key = Crypto.deriveKeyFromCode(code)
        disconnectQuietly()
        return try {
            val sock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            sock.connect()
            socket = sock; sessionKey = key; running = true; isConnected = true
            seq.set(0); serverSeq.reset()
            enqueue(FrameType.PAIR, FrameCodec.pairPayload(1, Build.MODEL))
            startWriter(sock.outputStream)
            startReader(sock.inputStream)
            startPingLoop(); startCoalesceLoop()
            // Server replies PAIR step 2; reader sets paired flag via onPaired.
            true
        } catch (e: IOException) {
            Log.w(TAG, "connectWithCode failed", e); disconnectQuietly(); false
        }
    }

    private fun startWriter(out: OutputStream) {
        thread(name = "airkeys-writer", isDaemon = true) {
            try {
                while (running) {
                    val frame = sendQueue.take()
                    out.write(frame); out.flush() // immediate: no batching for keys
                }
            } catch (e: Exception) { if (running) handleLoss() }
        }
    }

    private fun startReader(input: InputStream) {
        thread(name = "airkeys-reader", isDaemon = true) {
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
                        Crypto.decryptFrame(key, DIR_SERVER, body)
                    } catch (e: Exception) { Log.w(TAG, "bad frame, ignoring"); continue }
                    if (!serverSeq.accept(frame.seq)) continue // duplicate / replay
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
        thread(name = "airkeys-ping", isDaemon = true) {
            while (running) {
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                if (running) sendPing()
            }
        }
    }

    private fun startCoalesceLoop() {
        thread(name = "airkeys-coalesce", isDaemon = true) {
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

    fun disconnect() { running = false; isConnected = false; disconnectQuietly() }

    private fun disconnectQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; sendQueue.clear()
    }
}
