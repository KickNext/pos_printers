package com.kicknext.pos_printers.strict

import com.kicknext.pos_printers.connection.ConnectTimeoutException
import com.kicknext.pos_printers.connection.ConnectionException
import com.kicknext.pos_printers.connection.UsbDeviceNotFoundException
import com.kicknext.pos_printers.connection.UsbEndpointNotFoundException
import com.kicknext.pos_printers.gen.PrinterOutcome
import net.posprinter.POSConst

/**
 * Pure helpers of the opt-in strict printing mode. Nothing here touches the
 * printer, Android or Flutter, so everything can be unit tested on the JVM.
 */

/**
 * ESC/POS status codes of Xprinter SDK 3.5.8, as returned by
 * `POSPrinter.printerStatus` (read from `classes.jar` with `javap -constants`):
 *
 *  0 NORMAL, 1 PAPEREMPTY, 4 CUTTER_ERR, 5 OVERHEAT, 6 PRINTING, 7 OPEN_CASHBOX,
 *  8 PRESS_FEED (same value as CLOSE_CUTTER), 12 PAPER_NEAR_END, 16 COVEROPEN,
 *  64 PRINTER_ERR, -1 UNKNOWN, -3 CONNECT_ERROR, -4 READ_TIMEOUT.
 *
 * Note: before 3.5.x `PAPEREMPTY` was 32. The mapping below uses the SDK
 * constants, so it follows the jar that is actually linked.
 */
object EscPosStatus {
    const val SDK_CONNECT_ERROR = POSConst.CONNECT_ERROR // -3
    const val SDK_READ_TIMEOUT = POSConst.READ_TIMEOUT // -4

    /** Maps an SDK status code to the outcome reported to Dart. */
    fun toOutcome(code: Int): PrinterOutcome = when (code) {
        POSConst.STS_NORMAL -> PrinterOutcome.READY
        // Near end still prints; the cashier should only be warned.
        POSConst.STS_PAPER_NEAR_END -> PrinterOutcome.READY
        // Printer is busy printing or reports the drawer: it accepts data.
        POSConst.STS_PRINTING, POSConst.STS_OPEN_CASHBOX -> PrinterOutcome.READY
        POSConst.STS_PAPEREMPTY -> PrinterOutcome.NO_PAPER
        POSConst.STS_COVEROPEN -> PrinterOutcome.COVER_OPEN
        // Feed button held: the printer is paused and does not print.
        POSConst.STS_PRESS_FEED -> PrinterOutcome.OFFLINE
        POSConst.STS_PRINTER_ERR, POSConst.STS_CUTTER_ERR, POSConst.STS_OVERHEAT -> PrinterOutcome.ERROR
        SDK_CONNECT_ERROR -> PrinterOutcome.OFFLINE
        SDK_READ_TIMEOUT -> PrinterOutcome.TIMEOUT
        else -> PrinterOutcome.UNKNOWN // -1 and anything the SDK may add later
    }

    fun describe(code: Int): String = when (code) {
        POSConst.STS_NORMAL -> "Ready"
        POSConst.STS_PAPER_NEAR_END -> "Ready (paper near end)"
        POSConst.STS_PRINTING -> "Printing"
        POSConst.STS_OPEN_CASHBOX -> "Cash drawer open"
        POSConst.STS_PAPEREMPTY -> "Paper empty"
        POSConst.STS_COVEROPEN -> "Cover open"
        POSConst.STS_PRESS_FEED -> "Paused (feed button pressed)"
        POSConst.STS_PRINTER_ERR -> "Printer error"
        POSConst.STS_CUTTER_ERR -> "Cutter error"
        POSConst.STS_OVERHEAT -> "Print head overheated"
        SDK_CONNECT_ERROR -> "Connection lost during status read"
        SDK_READ_TIMEOUT -> "Printer did not answer the status request"
        -1 -> "Status unknown"
        else -> "Unknown status code $code"
    }

    /** True when sending a job to this outcome is pointless or dangerous. */
    fun blocksSending(outcome: PrinterOutcome): Boolean = when (outcome) {
        PrinterOutcome.NO_PAPER,
        PrinterOutcome.COVER_OPEN,
        PrinterOutcome.OFFLINE,
        PrinterOutcome.ERROR -> true
        else -> false
    }
}

/** Maps a connection failure to an outcome. */
fun classifyConnectFailure(error: Throwable?): PrinterOutcome = when (error) {
    null -> PrinterOutcome.OFFLINE
    is ConnectTimeoutException -> PrinterOutcome.TIMEOUT
    is ConnectionException,
    is UsbDeviceNotFoundException,
    is UsbEndpointNotFoundException -> PrinterOutcome.OFFLINE
    else -> PrinterOutcome.ERROR
}

/** A connect failure that retrying cannot fix (bad arguments, missing USB permission). */
fun isPermanentConnectFailure(error: Throwable?): Boolean =
    error is IllegalArgumentException || error is SecurityException

/**
 * Budget for connection attempts of one strict job. Only connecting is retried;
 * a job is never retried once a byte was written.
 */
class ConnectRetryPolicy(
    attempts: Int,
    val budgetMs: Long,
    val delayBetweenAttemptsMs: Long = DEFAULT_DELAY_MS,
) {
    val maxAttempts: Int = attempts.coerceIn(1, MAX_ATTEMPTS)

    /** Time the next attempt may take: the remaining budget shared by the remaining attempts. */
    fun attemptTimeoutMs(attemptsDone: Int, elapsedMs: Long): Long {
        val attemptsLeft = (maxAttempts - attemptsDone).coerceAtLeast(1)
        val remaining = (budgetMs - elapsedMs).coerceAtLeast(0)
        return if (remaining == 0L) 0L else (remaining / attemptsLeft).coerceAtLeast(MIN_ATTEMPT_MS).coerceAtMost(remaining)
    }

    /** Whether another attempt fits after [attemptsDone] attempts and [elapsedMs] spent. */
    fun shouldRetry(attemptsDone: Int, elapsedMs: Long): Boolean =
        attemptsDone < maxAttempts && elapsedMs + delayBetweenAttemptsMs + MIN_ATTEMPT_MS <= budgetMs

    companion object {
        const val MAX_ATTEMPTS = 10
        const val DEFAULT_DELAY_MS = 300L
        const val MIN_ATTEMPT_MS = 500L
    }
}

/** Hard time limit of a whole strict job. [clock] is injectable for tests. */
class Deadline(
    private val totalMs: Long,
    private val startedAtMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun elapsedMs(): Long = clock() - startedAtMs
    fun remainingMs(): Long = (totalMs - elapsedMs()).coerceAtLeast(0)
    fun expired(): Boolean = remainingMs() == 0L

    /** [stepMs] limited by what is left of the job. */
    fun cap(stepMs: Long): Long = minOf(stepMs, remainingMs())
}

/** IPv4 helpers for the strict TCP scan. */
object Subnet {
    /**
     * Host addresses (without network and broadcast) of the subnet of [ipAddress]/[prefixLength].
     * With [limitTo24] a subnet bigger than /24 is cut to the /24 that contains [ipAddress];
     * smaller subnets (/25../31) are kept as they are.
     * Returns an empty list for invalid input.
     */
    fun hosts(ipAddress: String, prefixLength: Int, limitTo24: Boolean): List<String> {
        val ip = parseIpv4(ipAddress) ?: return emptyList()
        if (prefixLength !in 1..31) return emptyList()
        val effective = if (limitTo24) maxOf(prefixLength, 24) else prefixLength
        val mask = (-1L shl (32 - effective)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val result = ArrayList<String>()
        var i = network + 1
        while (i < broadcast) {
            result.add("${(i shr 24) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 8) and 0xFF}.${i and 0xFF}")
            i++
        }
        return result
    }

    private fun parseIpv4(text: String): Long? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }
}
