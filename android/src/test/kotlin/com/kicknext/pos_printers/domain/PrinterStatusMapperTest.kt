package com.kicknext.pos_printers.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.posprinter.POSConst

class PrinterStatusMapperTest {
    @Test
    fun `esc pos normal status is ready and successful`() {
        val status = PrinterStatusMapper.fromEscPos(POSConst.STS_NORMAL)

        assertTrue(status.success)
        assertEquals(PrinterStatusState.READY, status.state)
        assertEquals(POSConst.STS_NORMAL, status.rawCode)
        assertEquals(null, status.errorMessage)
    }

    @Test
    fun `esc pos paper empty is a device fault not a successful status`() {
        val status = PrinterStatusMapper.fromEscPos(POSConst.STS_PAPEREMPTY)

        assertFalse(status.success)
        assertEquals(PrinterStatusState.PAPER_EMPTY, status.state)
        assertEquals(POSConst.STS_PAPEREMPTY, status.rawCode)
        assertEquals("Paper empty", status.errorMessage)
    }

    @Test
    fun `tspl composite status maps to concrete fault`() {
        val status = PrinterStatusMapper.fromTspl(0x0D)

        assertFalse(status.success)
        assertEquals(PrinterStatusState.RIBBON_AND_PAPER_EMPTY_WITH_HEAD_OPEN, status.state)
        assertEquals(0x0D, status.rawCode)
        assertEquals("Out of ribbon, out of paper and head opened", status.errorMessage)
    }
}
