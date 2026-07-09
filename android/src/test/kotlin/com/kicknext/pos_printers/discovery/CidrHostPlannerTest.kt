package com.kicknext.pos_printers.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `host planner caps local segment instead of expanding everything`() {
        val hosts = CidrHostPlanner.hosts(
            ipAddress = "10.20.30.40",
            prefixLength = 16,
            exclude = emptySet(),
            maxHosts = 32,
        ).toList()

        assertEquals(32, hosts.size)
    }

    @Test
    fun `host planner scans only the local segment inside a large network`() {
        val hosts = CidrHostPlanner.hosts(
            ipAddress = "192.168.100.40",
            prefixLength = 16,
            exclude = setOf("192.168.100.1"),
            maxHosts = 300,
        ).toList()

        assertEquals(252, hosts.size)
        assertEquals("192.168.100.2", hosts.first())
        assertTrue("192.168.100.32" in hosts)
        assertTrue("192.168.100.254" in hosts)
        assertFalse("192.168.0.1" in hosts)
        assertFalse("192.168.101.1" in hosts)
        assertFalse("192.168.100.40" in hosts)
    }
}
