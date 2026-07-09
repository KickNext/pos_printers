package com.kicknext.pos_printers.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UdpNetworkConfigTest {
    @Test
    fun `udp config has an explicit target mac separate from connection params`() {
        val config = UdpNetworkConfig(
            targetMacAddress = "AA:BB:CC:DD:EE:FF",
            ipAddress = "192.168.1.50",
            mask = "255.255.255.0",
            gateway = "192.168.1.1",
            dhcp = false,
        )

        assertContentEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
            config.targetMacBytes,
        )
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 50), config.ipBytes)
        assertEquals(false, config.dhcp)
    }

    @Test
    fun `udp config rejects missing target mac`() {
        assertFailsWith<IllegalArgumentException> {
            UdpNetworkConfig(
                targetMacAddress = "",
                ipAddress = "192.168.1.50",
                mask = "255.255.255.0",
                gateway = "192.168.1.1",
                dhcp = false,
            )
        }
    }
}
