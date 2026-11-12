package com.kicknext.pos_printers.strict

import com.kicknext.pos_printers.connection.ConnectTimeoutException
import com.kicknext.pos_printers.connection.ConnectionException
import com.kicknext.pos_printers.connection.UsbDeviceNotFoundException
import com.kicknext.pos_printers.gen.PrinterOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class StrictLogicTest {

    // ---- status code -> outcome (values of Xprinter SDK 3.5.8) ----

    @Test
    fun statusCodesMapToOutcomes() {
        assertEquals(PrinterOutcome.READY, EscPosStatus.toOutcome(0))
        assertEquals(PrinterOutcome.READY, EscPosStatus.toOutcome(12)) // paper near end
        assertEquals(PrinterOutcome.NO_PAPER, EscPosStatus.toOutcome(1))
        assertEquals(PrinterOutcome.COVER_OPEN, EscPosStatus.toOutcome(16))
        assertEquals(PrinterOutcome.OFFLINE, EscPosStatus.toOutcome(8)) // feed button
        assertEquals(PrinterOutcome.ERROR, EscPosStatus.toOutcome(64))
        assertEquals(PrinterOutcome.ERROR, EscPosStatus.toOutcome(4)) // cutter
        assertEquals(PrinterOutcome.ERROR, EscPosStatus.toOutcome(5)) // overheat
        assertEquals(PrinterOutcome.OFFLINE, EscPosStatus.toOutcome(-3))
        assertEquals(PrinterOutcome.TIMEOUT, EscPosStatus.toOutcome(-4))
        assertEquals(PrinterOutcome.UNKNOWN, EscPosStatus.toOutcome(-1))
        assertEquals(PrinterOutcome.UNKNOWN, EscPosStatus.toOutcome(999))
    }

    @Test
    fun oldPaperEmptyValueIsNotPaperEmptyAnymore() {
        // 32 was STS_PAPEREMPTY before SDK 3.5.x; it must not be trusted now.
        assertEquals(PrinterOutcome.UNKNOWN, EscPosStatus.toOutcome(32))
    }

    @Test
    fun onlyKnownFaultsBlockSending() {
        assertTrue(EscPosStatus.blocksSending(PrinterOutcome.NO_PAPER))
        assertTrue(EscPosStatus.blocksSending(PrinterOutcome.COVER_OPEN))
        assertTrue(EscPosStatus.blocksSending(PrinterOutcome.OFFLINE))
        assertTrue(EscPosStatus.blocksSending(PrinterOutcome.ERROR))
        assertFalse(EscPosStatus.blocksSending(PrinterOutcome.READY))
        assertFalse(EscPosStatus.blocksSending(PrinterOutcome.UNKNOWN))
        assertFalse(EscPosStatus.blocksSending(PrinterOutcome.TIMEOUT))
    }

    @Test
    fun connectFailuresAreClassified() {
        assertEquals(PrinterOutcome.TIMEOUT, classifyConnectFailure(ConnectTimeoutException("t")))
        assertEquals(PrinterOutcome.OFFLINE, classifyConnectFailure(ConnectionException("refused")))
        assertEquals(PrinterOutcome.OFFLINE, classifyConnectFailure(UsbDeviceNotFoundException(1, 2, null)))
        assertEquals(PrinterOutcome.ERROR, classifyConnectFailure(IllegalStateException("x")))
        assertTrue(isPermanentConnectFailure(IllegalArgumentException("x")))
        assertFalse(isPermanentConnectFailure(ConnectionException("x")))
    }

    // ---- retry budget ----

    @Test
    fun attemptsAreClamped() {
        assertEquals(1, ConnectRetryPolicy(0, 10_000).maxAttempts)
        assertEquals(ConnectRetryPolicy.MAX_ATTEMPTS, ConnectRetryPolicy(1000, 10_000).maxAttempts)
    }

    @Test
    fun threeAttemptsShareTheBudget() {
        val policy = ConnectRetryPolicy(3, 10_000, 300)
        assertEquals(3333L, policy.attemptTimeoutMs(0, 0))
        assertEquals(3333L, policy.attemptTimeoutMs(1, 3_333)) // 6667 left / 2 attempts
        assertEquals(3333L, policy.attemptTimeoutMs(2, 6_667)) // all that is left for the last attempt
        assertEquals(0L, policy.attemptTimeoutMs(2, 10_000))
    }

    @Test
    fun retryStopsAtAttemptLimitOrBudget() {
        val policy = ConnectRetryPolicy(3, 10_000, 300)
        assertTrue(policy.shouldRetry(1, 100))
        assertTrue(policy.shouldRetry(2, 5_000))
        assertFalse(policy.shouldRetry(3, 100)) // attempt limit
        assertFalse(policy.shouldRetry(1, 9_500)) // not enough budget for another attempt
    }

    @Test
    fun worstCaseStaysInsideTheBudget() {
        // Dead printer: every attempt takes its whole timeout.
        val policy = ConnectRetryPolicy(3, 10_000, 300)
        var elapsed = 0L
        var attempts = 0
        while (true) {
            val timeout = policy.attemptTimeoutMs(attempts, elapsed)
            if (timeout <= 0) break
            attempts++
            elapsed += timeout
            if (!policy.shouldRetry(attempts, elapsed)) break
            elapsed += policy.delayBetweenAttemptsMs
        }
        assertTrue(elapsed <= 10_000, "elapsed=$elapsed")
        assertTrue(attempts <= 3)
        // The legacy path would take 30 attempts (77 s on the real printer).
    }

    @Test
    fun deadlineUsesTheClock() {
        var now = 1_000L
        val deadline = Deadline(5_000, 1_000) { now }
        assertEquals(5_000, deadline.remainingMs())
        assertEquals(2_000, deadline.cap(2_000))
        now = 5_000
        assertEquals(2_000, deadline.remainingMs())
        assertEquals(2_000, deadline.cap(9_000))
        assertFalse(deadline.expired())
        now = 7_000
        assertEquals(0, deadline.remainingMs())
        assertTrue(deadline.expired())
    }

    // ---- /24 limit ----

    @Test
    fun slash16IsCutToSlash24WhenLimited() {
        val hosts = Subnet.hosts("10.20.30.40", 16, limitTo24 = true)
        assertEquals(254, hosts.size)
        assertEquals("10.20.30.1", hosts.first())
        assertEquals("10.20.30.254", hosts.last())
    }

    @Test
    fun slash16IsFullWhenNotLimited() {
        assertEquals(65_534, Subnet.hosts("10.20.30.40", 16, limitTo24 = false).size)
    }

    @Test
    fun smallerThanSlash24IsKept() {
        val hosts = Subnet.hosts("192.168.1.130", 28, limitTo24 = true)
        assertEquals(14, hosts.size)
        assertEquals("192.168.1.129", hosts.first())
        assertEquals("192.168.1.142", hosts.last())
    }

    @Test
    fun slash24IsUnchanged() {
        assertEquals(254, Subnet.hosts("192.168.1.5", 24, limitTo24 = true).size)
    }

    @Test
    fun invalidInputGivesNoHosts() {
        assertTrue(Subnet.hosts("not-an-ip", 24, true).isEmpty())
        assertTrue(Subnet.hosts("1.2.3.256", 24, true).isEmpty())
        assertTrue(Subnet.hosts("1.2.3.4", 0, true).isEmpty())
        assertTrue(Subnet.hosts("1.2.3.4", 32, true).isEmpty())
    }
}
