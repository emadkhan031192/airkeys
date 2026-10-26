package com.airkeys

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the PC on the local network, two ways:
 * 1. UDP discovery: the PC broadcasts "AirKeys|47820|<name>" every second
 *    on UDP 47821. Listening for a few seconds finds it with no scanning.
 * 2. Subnet scan (fallback): try TCP port 47820 on every host in a /24,
 *    in parallel, with a short timeout. First success wins.
 * Both are LAN-only. Nothing here ever touches the internet.
 */
object LanDiscovery {
    /** Listen for a PC broadcast. Returns the sender's host, or null on timeout. */
    fun listenOnce(timeoutMs: Long): String? {
        return try {
            DatagramSocket(LanTransport.UDP_PORT).use { sock ->
                sock.soTimeout = timeoutMs.toInt()
                sock.broadcast = true
                val buf = ByteArray(256)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val parsed = LanTransport.parseDiscoveryPacket(text) ?: return null
                if (parsed.first != LanTransport.TCP_PORT) return null
                pkt.address.hostAddress
            }
        } catch (e: Exception) { null }
    }

    /** Parallel TCP probe of one /24 prefix (e.g. "192.168.43."). */
    fun scanPrefix(prefix: String, timeoutMs: Int = 200): String? {
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        try {
            for (i in 2..254) {
                val host = "$prefix$i"
                pool.submit {
                    if (found.get() != null) return@submit
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, LanTransport.TCP_PORT), timeoutMs)
                            found.compareAndSet(null, host)
                        }
                    } catch (_: Exception) { /* not a PC, or not listening */ }
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return found.get()
    }

    /** Try UDP first, then the common subnets in order. Returns host or null. */
    fun discover(): String? {
        listenOnce(3000)?.let { return it }
        for (prefix in LanTransport.commonSubnetPrefixes()) {
            scanPrefix(prefix)?.let { return it }
        }
        return null
    }
}
