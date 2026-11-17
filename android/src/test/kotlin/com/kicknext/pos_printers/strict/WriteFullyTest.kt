package com.kicknext.pos_printers.strict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WriteFullyTest {

    private class Calls {
        val offsets = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        val timeouts = ArrayList<Int>()
    }

    private fun recorded(calls: Calls, answer: (Int, Int) -> Int): (Int, Int, Int) -> Int = { offset, length, timeout ->
        calls.offsets.add(offset)
        calls.lengths.add(length)
        calls.timeouts.add(timeout)
        answer(offset, length)
    }

    @Test
    fun aFullTransferIsDone() {
        val calls = Calls()
        val result = writeFully(100, Deadline(5_000, 0) { 0 }, 5_000, 16_384, recorded(calls) { _, length -> length })
        assertEquals(ChunkWrite.Done(100), result)
        assertEquals(listOf(0), calls.offsets)
    }

    @Test
    fun aShortTransferIsContinuedFromWhereItStopped() {
        val calls = Calls()
        val result = writeFully(100, Deadline(5_000, 0) { 0 }, 5_000, 16_384, recorded(calls) { _, length -> minOf(length, 30) })
        assertEquals(ChunkWrite.Done(100), result)
        assertEquals(listOf(0, 30, 60, 90), calls.offsets)
        assertEquals(listOf(100, 70, 40, 10), calls.lengths)
    }

    @Test
    fun oneTransferIsNeverBiggerThanTheStep() {
        val calls = Calls()
        val result = writeFully(40_000, Deadline(5_000, 0) { 0 }, 5_000, 16_384, recorded(calls) { _, length -> length })
        assertEquals(ChunkWrite.Done(40_000), result)
        assertEquals(listOf(16_384, 16_384, 7_232), calls.lengths)
    }

    @Test
    fun aTransferThatMovesNothingStallsWithTheCountSoFar() {
        var call = 0
        val result = writeFully(100, Deadline(5_000, 0) { 0 }, 5_000, 16_384) { _, _, _ -> if (call++ == 0) 40 else 0 }
        assertEquals(ChunkWrite.Stalled(40), result)
    }

    @Test
    fun aNegativeTransferFailsWithItsCode() {
        val result = writeFully(100, Deadline(5_000, 0) { 0 }, 5_000, 16_384) { _, _, _ -> -1 }
        assertEquals(ChunkWrite.Failed(0, -1), result)
    }

    @Test
    fun anExpiredDeadlineWritesNothing() {
        var called = false
        val result = writeFully(100, Deadline(5_000, 0) { 6_000 }, 5_000, 16_384) { _, length, _ ->
            called = true
            length
        }
        assertEquals(ChunkWrite.Expired(0), result)
        assertTrue(!called)
    }

    @Test
    fun theDeadlineIsCheckedBetweenTransfers() {
        var now = 0L
        val deadline = Deadline(1_000, 0) { now }
        val result = writeFully(100, deadline, 5_000, 16_384) { _, _, _ ->
            now = 1_000 // the first transfer used the rest of the job
            30
        }
        assertEquals(ChunkWrite.Expired(30), result)
    }

    @Test
    fun aTransferThatFailsAtTheDeadlineIsATimeout() {
        var now = 0L
        val result = writeFully(100, Deadline(1_000, 0) { now }, 5_000, 16_384) { _, _, _ ->
            now = 1_500
            -1
        }
        assertEquals(ChunkWrite.Expired(0), result)
    }

    @Test
    fun eachTransferIsLimitedByWhatIsLeftOfTheJob() {
        var now = 0L
        val calls = Calls()
        writeFully(100, Deadline(3_000, 0) { now }, 5_000, 16_384, recorded(calls) { _, _ ->
            now += 2_000
            30
        })
        assertEquals(listOf(3_000, 1_000), calls.timeouts)
        assertTrue(calls.timeouts.all { it > 0 }, "0 means no limit to bulkTransfer")
    }

    @Test
    fun theSdkUsbConnectionHasTheFieldsStrictModeWritesThrough() {
        // net.posprinter.a.f is the USB connection of Xprinter SDK 3.5.8 (android/libs/classes.jar).
        // If an SDK update drops these fields, strict USB writes fall back to sendSync: this test says so first.
        val usb = Class.forName("net.posprinter.a.f", false, javaClass.classLoader)
        val fields = UsbBulkOut.usbFields(usb)
        assertNotNull(fields)
        assertEquals(2, fields.endpoints.size)
    }

    @Test
    fun aNetworkConnectionHasNoUsbFields() {
        val network = Class.forName("net.posprinter.a.b", false, javaClass.classLoader) // Ethernet (connect type 3)
        assertNull(UsbBulkOut.usbFields(network))
    }
}
