package com.airkeys

/**
 * Transport-independent input sender. Bluetooth RFCOMM and TCP LAN both
 * implement this, so the ViewModel and keyboard/touchpad code never care
 * which transport is active. Frames, crypto, seq and replay protection are
 * identical on both — only the socket underneath changes.
 */
interface InputTransport {
    val isConnected: Boolean
    val framesSent: Long
    fun sendText(text: String)
    fun sendBackspace(count: Int)
    fun sendKey(code: Int, down: Boolean)
    fun sendCombo(modifiers: Int, code: Int, down: Boolean)
    fun sendMouseButton(button: Int, down: Boolean)
    fun sendScroll(dx: Int, dy: Int)
    fun sendMouseMove(dx: Int, dy: Int)
    fun flushMouseMovesNow(nowMs: Long)
    fun disconnect()
}

object LanTransport {
    const val TCP_PORT = 47820
    const val UDP_PORT = 47821
    const val DISCOVERY_PREFIX = "AirKeys"

    /** Parse a UDP discovery packet: "AirKeys|47820|PCName" -> (port, pcName). */
    fun parseDiscoveryPacket(text: String): Pair<Int, String>? {
        val parts = text.trim().split("|")
        if (parts.size < 3 || parts[0] != DISCOVERY_PREFIX) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return port to parts[2]
    }

    fun buildDiscoveryPacket(port: Int, pcName: String): String =
        "$DISCOVERY_PREFIX|$port|$pcName"

    /** All hosts in the same /24 as [baseIp], excluding the base itself. */
    fun subnetHosts(baseIp: String): List<String> {
        val prefix = baseIp.substringBeforeLast(".", "")
        if (prefix.isEmpty() || prefix == baseIp) return emptyList()
        return (2..254).map { "$prefix.$it" }
    }

    /** Subnet prefixes to try, in order. These cover the common cases:
     *  phone hotspot (192.168.43.x), Windows hotspot (192.168.137.x),
     *  USB tethering (192.168.42.x), and ordinary home routers. */
    fun commonSubnetPrefixes(): List<String> = listOf(
        "192.168.43.", "192.168.137.", "192.168.42.",
        "192.168.0.", "192.168.1.", "192.168.178.",
    )
}
