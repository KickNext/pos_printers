package com.kicknext.pos_printers.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CidrHostPlannerTest {
    @Test
    fun `host planner excludes network broadcast and current host`() {
        val hosts = CidrHostPlanner.hosts(
            ipAddress = "192.168.1.10",
            prefixLength = 24,
            exclude = setOf("192.168.1.20"),
            maxHosts = 300,
        ).toList()

        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertFalse("192.168.1.10" in hosts)
        assertFalse("192.168.1.20" in hosts)
        assertEquals(252, hosts.size)
    }

    @Test
    fun `host planner caps large networks instead of expanding everything`() {
        val hosts = CidrHostPlanner.hosts(
            ipAddress = "10.20.30.40",
            prefixLength = 16,
            exclude = emptySet(),
            maxHosts = 32,
        ).toList()

        assertEquals(32, hosts.size)
    }
}
