package com.airkeys

import org.junit.Assert.*
import org.junit.Test

class LanAndKeyboardTest {
    @Test fun parseDiscoveryPacket() {
        val parsed = LanTransport.parseDiscoveryPacket("AirKeys|47820|MyLaptop")
        assertEquals(47820 to "MyLaptop", parsed)
        assertNull(LanTransport.parseDiscoveryPacket("AirKeys|99999|X"))
        assertNull(LanTransport.parseDiscoveryPacket("Other|47820|X"))
        assertNull(LanTransport.parseDiscoveryPacket("garbage"))
        assertEquals("AirKeys|47820|MyLaptop", LanTransport.buildDiscoveryPacket(47820, "MyLaptop"))
    }

    @Test fun subnetHosts() {
        val hosts = LanTransport.subnetHosts("192.168.43.1")
        assertEquals(253, hosts.size)
        assertEquals("192.168.43.2", hosts.first())
        assertEquals("192.168.43.254", hosts.last())
        assertFalse(hosts.contains("192.168.43.1"))
        assertTrue(LanTransport.subnetHosts("not-an-ip").isEmpty())
    }

    @Test fun commonSubnetsCoverHotspotUsbAndHome() {
        val prefixes = LanTransport.commonSubnetPrefixes()
        assertTrue(prefixes.contains("192.168.43.")) // phone hotspot
        assertTrue(prefixes.contains("192.168.137.")) // Windows hotspot
        assertTrue(prefixes.contains("192.168.42.")) // USB tethering
    }

    @Test fun shiftAppliesToLatinOnly() {
        assertEquals("Q", KeyboardLayouts.applyShift("q", true))
        assertEquals("q", KeyboardLayouts.applyShift("q", false))
        assertEquals("ا", KeyboardLayouts.applyShift("ا", true))
        assertEquals("!", KeyboardLayouts.applyShift("!", true))
    }

    @Test fun layoutsAreNonEmpty() {
        assertTrue(KeyboardLayouts.rowsFor(SmartLayout.ENGLISH).flatten().contains("q"))
        assertTrue(KeyboardLayouts.rowsFor(SmartLayout.URDU).flatten().contains("ا"))
        assertTrue(KeyboardLayouts.rowsFor(SmartLayout.SYMBOLS).flatten().contains("!"))
        assertTrue(KeyboardLayouts.EMOJI_ROW.isNotEmpty())
    }
}
