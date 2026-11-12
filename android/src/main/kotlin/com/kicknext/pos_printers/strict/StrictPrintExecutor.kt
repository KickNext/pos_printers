package com.kicknext.pos_printers.strict

import android.util.Log
import com.kicknext.pos_printers.connection.ConnectTimeoutException
import com.kicknext.pos_printers.connection.PrinterConnectionManager
import com.kicknext.pos_printers.gen.*
import com.kicknext.pos_printers.printer.PrinterOperations
import kotlinx.coroutines.*
import net.posprinter.IDeviceConnection
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Opt-in strict printing mode. The legacy paths in [PrinterConnectionManager] are untouched.
 *
 * Guarantees (see also the Pigeon docs):
 *  - the whole job is time-boxed (StrictPrintOptionsDTO.jobTimeoutMs);
 *  - only connecting is retried, with a small total budget; after the first written byte there is no retry;
 *  - the status is read before sending (a known fault refuses the job) and after sending;
 *  - bytes are written synchronously (sendSync), so a write error is reported, not swallowed;
 *  - jobs for one printer run one after another; other printers are never blocked.
 *
 * ESC/POS has no "printing finished" signal. PrinterOutcome.READY after a job means the bytes were
 * written and the printer reports no fault right afterwards.
 */
class StrictPrintExecutor(
    private val connectionManager: PrinterConnectionManager,
    private val operations: PrinterOperations,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "StrictPrintExecutor"
        private const val DRAWER_PULSES = 3
        private const val DRAWER_PULSE_GAP_MS = 100L
    }

    private val printerLocks = ConcurrentHashMap<String, Semaphore>()

    /** Reads the ESC/POS status: real SDK code plus mapped outcome. */
    suspend fun readStatus(printer: PrinterConnectionParamsDTO, options: StrictPrintOptionsDTO): PrinterStatusDTO {
        val deadline = Deadline(options.jobTimeoutMs, clock(), clock)
        var attempts = 0
        var result: PrinterStatusDTO? = null
        val busy = withPrinterLock(printer, deadline) {
            val connect = connect(printer, options, deadline)
            attempts = connect.attempts
            val connection = connect.connection
            if (connection == null) {
                result = PrinterStatusDTO(
                    outcome = classifyConnectFailure(connect.error),
                    statusCode = null,
                    message = "Cannot connect: ${connect.error?.message}",
                    connectAttempts = attempts.toLong(),
                    elapsedMs = deadline.elapsedMs(),
                )
            } else {
                try {
                    val code = readEscPosStatus(connection, deadline.cap(options.statusTimeoutMs))
                    result = PrinterStatusDTO(
                        outcome = if (code == null) PrinterOutcome.TIMEOUT else EscPosStatus.toOutcome(code),
                        statusCode = code?.toLong(),
                        message = if (code == null) "Status read timed out" else EscPosStatus.describe(code),
                        connectAttempts = attempts.toLong(),
                        elapsedMs = deadline.elapsedMs(),
                    )
                } finally {
                    closeQuietly(connection)
                }
            }
        }
        return result ?: PrinterStatusDTO(
            outcome = PrinterOutcome.TIMEOUT,
            statusCode = null,
            message = busy ?: "Printer is busy with another job",
            connectAttempts = attempts.toLong(),
            elapsedMs = deadline.elapsedMs(),
        )
    }

    /** Strict raw ESC/POS print. */
    suspend fun printRaw(
        printer: PrinterConnectionParamsDTO,
        data: ByteArray,
        width: Long,
        upsideDown: Boolean,
        options: StrictPrintOptionsDTO,
        startedAtMs: Long = clock(),
    ): PrintJobResultDTO = runPrintJob(printer, options, startedAtMs) { connection ->
        operations.printRawData(connection, data, width, upsideDown)
    }

    /** Strict print of an already rendered bitmap (HTML receipts). */
    suspend fun printBitmap(
        printer: PrinterConnectionParamsDTO,
        bitmap: android.graphics.Bitmap,
        width: Long,
        options: StrictPrintOptionsDTO,
        startedAtMs: Long,
    ): PrintJobResultDTO = runPrintJob(printer, options, startedAtMs) { connection ->
        val escPrinter = POSPrinter(connection)
        escPrinter.initializePrinter()
        escPrinter.printBitmap(bitmap, POSConst.ALIGNMENT_LEFT, width.toInt())
        escPrinter.cutHalfAndFeed(1)
    }

    /** Result for a failure before the job reached the printer (validation, render). */
    fun failedBeforeSend(
        outcome: PrinterOutcome,
        stage: PrintStage,
        message: String,
        startedAtMs: Long,
    ) = PrintJobResultDTO(
        outcome = outcome,
        sent = false,
        stage = stage,
        statusBefore = null,
        statusAfter = null,
        bytesSent = 0,
        connectAttempts = 0,
        elapsedMs = clock() - startedAtMs,
        message = message,
    )

    /** Strict cash drawer pulse: connection and write errors are reported. */
    suspend fun openCashBox(printer: PrinterConnectionParamsDTO, options: StrictPrintOptionsDTO): PrintJobResultDTO {
        val deadline = Deadline(options.jobTimeoutMs, clock(), clock)
        var attempts = 0
        var result: PrintJobResultDTO? = null
        val busy = withPrinterLock(printer, deadline) {
            val connect = connect(printer, options, deadline)
            attempts = connect.attempts
            val connection = connect.connection
            if (connection == null) {
                result = jobResult(
                    outcome = classifyConnectFailure(connect.error),
                    stage = PrintStage.CONNECT,
                    message = "Cannot connect: ${connect.error?.message}",
                    attempts = attempts,
                    deadline = deadline,
                )
            } else {
                var bytes = 0
                try {
                    var failure: PrintJobResultDTO? = null
                    for (pulse in 0 until DRAWER_PULSES) {
                        val capture = CapturingConnection(connection)
                        val escPrinter = POSPrinter(capture)
                        escPrinter.initializePrinter()
                        escPrinter.openCashBox(POSConst.PIN_TWO)
                        val flush = flush(connection, capture.chunks, deadline)
                        bytes += flush.bytes
                        if (flush.outcome != null) {
                            failure = jobResult(
                                outcome = flush.outcome,
                                stage = PrintStage.WRITE,
                                message = "Drawer pulse ${pulse + 1}/$DRAWER_PULSES failed: ${flush.message}",
                                attempts = attempts,
                                deadline = deadline,
                                bytes = bytes,
                            )
                            break
                        }
                        if (pulse < DRAWER_PULSES - 1) delay(deadline.cap(DRAWER_PULSE_GAP_MS))
                    }
                    result = failure ?: jobResult(
                        outcome = PrinterOutcome.READY,
                        stage = PrintStage.COMPLETED,
                        message = "Drawer pulse written ($DRAWER_PULSES x); drawer state is not verified",
                        attempts = attempts,
                        deadline = deadline,
                        bytes = bytes,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Strict cash drawer pulse failed", e)
                    result = jobResult(
                        outcome = PrinterOutcome.ERROR,
                        stage = PrintStage.WRITE,
                        message = "Drawer pulse failed: ${e.message}",
                        attempts = attempts,
                        deadline = deadline,
                        bytes = bytes,
                    )
                } finally {
                    closeQuietly(connection)
                }
            }
        }
        return result ?: jobResult(
            outcome = PrinterOutcome.TIMEOUT,
            stage = PrintStage.CONNECT,
            message = busy ?: "Printer is busy with another job",
            attempts = attempts,
            deadline = deadline,
        )
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun runPrintJob(
        printer: PrinterConnectionParamsDTO,
        options: StrictPrintOptionsDTO,
        startedAtMs: Long,
        write: suspend (IDeviceConnection) -> Unit,
    ): PrintJobResultDTO {
        val deadline = Deadline(options.jobTimeoutMs, startedAtMs, clock)
        var attempts = 0
        var result: PrintJobResultDTO? = null
        val busy = withPrinterLock(printer, deadline) {
            val connect = connect(printer, options, deadline)
            attempts = connect.attempts
            val connection = connect.connection
            if (connection == null) {
                result = jobResult(
                    outcome = classifyConnectFailure(connect.error),
                    stage = PrintStage.CONNECT,
                    message = "Cannot connect: ${connect.error?.message}",
                    attempts = attempts,
                    deadline = deadline,
                )
            } else {
                result = runConnected(connection, options, deadline, attempts, write)
            }
        }
        return result ?: jobResult(
            outcome = PrinterOutcome.TIMEOUT,
            stage = PrintStage.CONNECT,
            message = busy ?: "Printer is busy with another job",
            attempts = attempts,
            deadline = deadline,
        )
    }

    private suspend fun runConnected(
        connection: IDeviceConnection,
        options: StrictPrintOptionsDTO,
        deadline: Deadline,
        attempts: Int,
        write: suspend (IDeviceConnection) -> Unit,
    ): PrintJobResultDTO {
        var bytes = 0
        var statusBefore: Int? = null
        try {
            var preStatusTimedOut = false
            if (options.checkStatusBefore) {
                statusBefore = readEscPosStatus(connection, deadline.cap(options.statusTimeoutMs))
                preStatusTimedOut = statusBefore == null
                val outcome = if (statusBefore == null) PrinterOutcome.TIMEOUT else EscPosStatus.toOutcome(statusBefore)
                val unknown = outcome == PrinterOutcome.TIMEOUT || outcome == PrinterOutcome.UNKNOWN
                if (EscPosStatus.blocksSending(outcome) || (unknown && options.refuseOnUnknownStatus)) {
                    return jobResult(
                        outcome = outcome,
                        stage = PrintStage.PRE_STATUS,
                        message = "Not sent: " + (statusBefore?.let { EscPosStatus.describe(it) } ?: "status read timed out"),
                        attempts = attempts,
                        deadline = deadline,
                        statusBefore = statusBefore,
                    )
                }
            }

            // Collect the bytes first, then write them synchronously: a write error is visible.
            val capture = CapturingConnection(connection)
            write(capture)
            val flush = flush(connection, capture.chunks, deadline)
            bytes = flush.bytes
            if (flush.outcome != null) {
                return jobResult(
                    outcome = flush.outcome,
                    stage = PrintStage.WRITE,
                    message = "Write failed after $bytes bytes (not retried): ${flush.message}",
                    attempts = attempts,
                    deadline = deadline,
                    bytes = bytes,
                    statusBefore = statusBefore,
                )
            }

            // A status request while the SDK still waits for the previous answer would steal that answer.
            if (!options.checkStatusAfter || preStatusTimedOut) {
                return jobResult(
                    outcome = PrinterOutcome.UNKNOWN,
                    stage = PrintStage.COMPLETED,
                    message = "Written $bytes bytes; status after sending was not read",
                    attempts = attempts,
                    deadline = deadline,
                    bytes = bytes,
                    statusBefore = statusBefore,
                )
            }
            delay(deadline.cap(options.postStatusDelayMs))
            val statusAfter = readEscPosStatus(connection, deadline.cap(options.statusTimeoutMs))
            val outcome = if (statusAfter == null) PrinterOutcome.TIMEOUT else EscPosStatus.toOutcome(statusAfter)
            val said = statusAfter?.let { EscPosStatus.describe(it) } ?: "no answer to the status request"
            val advice = if (outcome == PrinterOutcome.READY) "" else ". The job may still be in the printer buffer: do not resend blindly"
            return jobResult(
                outcome = outcome,
                stage = if (outcome == PrinterOutcome.READY) PrintStage.COMPLETED else PrintStage.POST_STATUS,
                message = "Written $bytes bytes; printer says: $said$advice",
                attempts = attempts,
                deadline = deadline,
                bytes = bytes,
                statusBefore = statusBefore,
                statusAfter = statusAfter,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Strict print job failed", e)
            return jobResult(
                outcome = PrinterOutcome.ERROR,
                stage = PrintStage.WRITE,
                message = "Print failed: ${e.message}",
                attempts = attempts,
                deadline = deadline,
                bytes = bytes,
                statusBefore = statusBefore,
            )
        } finally {
            closeQuietly(connection)
        }
    }

    private class ConnectResult(val connection: IDeviceConnection?, val attempts: Int, val error: Exception?)

    /** Connects with a bounded number of attempts inside the connect budget and the job deadline. */
    private suspend fun connect(
        printer: PrinterConnectionParamsDTO,
        options: StrictPrintOptionsDTO,
        deadline: Deadline,
    ): ConnectResult {
        val policy = ConnectRetryPolicy(options.connectAttempts.toInt(), options.connectBudgetMs)
        val connectStarted = clock()
        var attempts = 0
        var lastError: Exception? = null
        while (true) {
            val timeout = minOf(policy.attemptTimeoutMs(attempts, clock() - connectStarted), deadline.remainingMs())
            if (timeout <= 0) {
                if (lastError == null) lastError = ConnectTimeoutException("Connect budget exhausted")
                break
            }
            attempts++
            var connection: IDeviceConnection? = null
            try {
                val (created, target) = connectionManager.createConnection(printer)
                connection = created
                withContext(Dispatchers.IO) { connectionManager.connectBounded(created, target, timeout) }
                return ConnectResult(created, attempts, null)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Strict connect attempt $attempts failed: ${e.message}")
                connection?.let { closeQuietly(it) }
                if (isPermanentConnectFailure(e)) break
            }
            if (!policy.shouldRetry(attempts, clock() - connectStarted)) break
            delay(deadline.cap(policy.delayBetweenAttemptsMs))
        }
        return ConnectResult(null, attempts, lastError)
    }

    private class FlushResult(val bytes: Int, val outcome: PrinterOutcome?, val message: String?)

    /**
     * Writes the collected chunks with sendSync. A watchdog closes the connection when the job deadline
     * passes, which unblocks a write stuck on a stalled printer.
     */
    private suspend fun flush(connection: IDeviceConnection, chunks: List<ByteArray>, deadline: Deadline): FlushResult {
        var written = 0
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        var failure: FlushResult? = null
        coroutineScope {
            val watchdog = launch(Dispatchers.Default) {
                delay(deadline.remainingMs())
                timedOut.set(true)
                closeQuietly(connection)
            }
            withContext(Dispatchers.IO) {
                for (chunk in chunks) {
                    val sent = try {
                        connection.sendSync(chunk)
                    } catch (e: Exception) {
                        failure = FlushResult(written, PrinterOutcome.ERROR, e.message)
                        break
                    }
                    if (sent < chunk.size) {
                        failure = FlushResult(written, PrinterOutcome.OFFLINE, "sendSync returned $sent for ${chunk.size} bytes")
                        break
                    }
                    written += sent
                }
            }
            watchdog.cancel()
        }
        if (timedOut.get()) return FlushResult(written, PrinterOutcome.TIMEOUT, "job time limit reached while writing")
        return failure ?: FlushResult(written, null, null)
    }

    /**
     * One status read with our own time limit. Returns null on timeout.
     * The SDK reports its own failures as negative codes (-1, -3, -4).
     */
    private suspend fun readEscPosStatus(connection: IDeviceConnection, timeoutMs: Long): Int? {
        if (timeoutMs <= 0) return null
        val answer = CompletableDeferred<Int>()
        try {
            POSPrinter(connection).printerStatus { code -> answer.complete(code) }
        } catch (e: Exception) {
            Log.w(TAG, "printerStatus threw: ${e.message}")
            return POSConst.STS_UNKNOWN
        }
        return withTimeoutOrNull(timeoutMs) { answer.await() }
    }

    /**
     * Runs [block] while holding the per-printer lock; waits for the lock at most until the deadline.
     * Returns null when the block ran, or a message when the lock could not be taken in time.
     */
    private suspend fun withPrinterLock(
        printer: PrinterConnectionParamsDTO,
        deadline: Deadline,
        block: suspend () -> Unit,
    ): String? {
        val lock = printerLocks.getOrPut(printer.id) { Semaphore(1) }
        val acquired = withContext(Dispatchers.IO) { lock.tryAcquire(deadline.remainingMs(), TimeUnit.MILLISECONDS) }
        if (!acquired) return "Printer is busy with another job (waited ${deadline.elapsedMs()} ms)"
        try {
            block()
        } finally {
            lock.release()
        }
        return null
    }

    private fun closeQuietly(connection: IDeviceConnection) {
        try {
            connection.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        }
    }

    private fun jobResult(
        outcome: PrinterOutcome,
        stage: PrintStage,
        message: String?,
        attempts: Int,
        deadline: Deadline,
        bytes: Int = 0,
        statusBefore: Int? = null,
        statusAfter: Int? = null,
    ) = PrintJobResultDTO(
        outcome = outcome,
        sent = bytes > 0,
        stage = stage,
        statusBefore = statusBefore?.toLong(),
        statusAfter = statusAfter?.toLong(),
        bytesSent = bytes.toLong(),
        connectAttempts = attempts.toLong(),
        elapsedMs = deadline.elapsedMs(),
        message = message,
    )
}

/**
 * Collects what the SDK wants to send instead of queueing it asynchronously, so the executor can write it
 * with sendSync and see write errors. Everything else is delegated to the real connection.
 */
internal class CapturingConnection(private val real: IDeviceConnection) : IDeviceConnection by real {
    val chunks = ArrayList<ByteArray>()

    override fun sendData(data: ByteArray) {
        chunks.add(data.copyOf())
    }

    override fun sendData(data: MutableList<ByteArray>) {
        data.forEach { chunks.add(it.copyOf()) }
    }
}
