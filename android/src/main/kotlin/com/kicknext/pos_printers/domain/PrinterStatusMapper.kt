package com.kicknext.pos_printers.domain

import net.posprinter.POSConst

enum class PrinterStatusState {
    READY,
    COVER_OPEN,
    PRESS_FEED,
    PAPER_EMPTY,
    PRINTER_ERROR,
    HEAD_OPENED,
    PAPER_JAM,
    PAPER_JAM_WITH_HEAD_OPEN,
    RIBBON_EMPTY,
    RIBBON_EMPTY_WITH_HEAD_OPEN,
    RIBBON_AND_PAPER_JAM,
    RIBBON_AND_PAPER_JAM_WITH_HEAD_OPEN,
    RIBBON_AND_PAPER_EMPTY,
    RIBBON_AND_PAPER_EMPTY_WITH_HEAD_OPEN,
    PAUSED,
    PRINTING,
    OTHER_ERROR,
    TIMEOUT,
    DISCONNECTED,
    UNKNOWN,
}

data class PrinterStatus(
    val success: Boolean,
    val state: PrinterStatusState,
    val rawCode: Int,
    val errorMessage: String?,
)

object PrinterStatusMapper {
    fun fromEscPos(rawCode: Int): PrinterStatus {
        val state = when (rawCode) {
            POSConst.STS_NORMAL -> PrinterStatusState.READY
            POSConst.STS_COVEROPEN -> PrinterStatusState.COVER_OPEN
            POSConst.STS_PRESS_FEED -> PrinterStatusState.PRESS_FEED
            POSConst.STS_PAPEREMPTY -> PrinterStatusState.PAPER_EMPTY
            POSConst.STS_PRINTER_ERR -> PrinterStatusState.PRINTER_ERROR
            -3 -> PrinterStatusState.DISCONNECTED
            -4 -> PrinterStatusState.TIMEOUT
            else -> PrinterStatusState.UNKNOWN
        }
        return PrinterStatus(
            success = state == PrinterStatusState.READY,
            state = state,
            rawCode = rawCode,
            errorMessage = if (state == PrinterStatusState.READY) null else escPosMessage(state, rawCode),
        )
    }

    fun fromTspl(rawCode: Int): PrinterStatus {
        val state = when (rawCode) {
            0x00 -> PrinterStatusState.READY
            0x01 -> PrinterStatusState.HEAD_OPENED
            0x02 -> PrinterStatusState.PAPER_JAM
            0x03 -> PrinterStatusState.PAPER_JAM_WITH_HEAD_OPEN
            0x04 -> PrinterStatusState.PAPER_EMPTY
            0x05 -> PrinterStatusState.PAPER_EMPTY
            0x08 -> PrinterStatusState.RIBBON_EMPTY
            0x09 -> PrinterStatusState.RIBBON_EMPTY_WITH_HEAD_OPEN
            0x0A -> PrinterStatusState.RIBBON_AND_PAPER_JAM
            0x0B -> PrinterStatusState.RIBBON_AND_PAPER_JAM_WITH_HEAD_OPEN
            0x0C -> PrinterStatusState.RIBBON_AND_PAPER_EMPTY
            0x0D -> PrinterStatusState.RIBBON_AND_PAPER_EMPTY_WITH_HEAD_OPEN
            0x10 -> PrinterStatusState.PAUSED
            0x20 -> PrinterStatusState.PRINTING
            0x80 -> PrinterStatusState.OTHER_ERROR
            -1 -> PrinterStatusState.TIMEOUT
            else -> PrinterStatusState.UNKNOWN
        }
        return PrinterStatus(
            success = state == PrinterStatusState.READY,
            state = state,
            rawCode = rawCode,
            errorMessage = if (state == PrinterStatusState.READY) null else tsplMessage(rawCode),
        )
    }

    private fun escPosMessage(state: PrinterStatusState, rawCode: Int): String = when (state) {
        PrinterStatusState.COVER_OPEN -> "Cover open"
        PrinterStatusState.PRESS_FEED -> "Press the paper feed button"
        PrinterStatusState.PAPER_EMPTY -> "Paper empty"
        PrinterStatusState.PRINTER_ERROR -> "Printer error"
        PrinterStatusState.DISCONNECTED -> "Status check: Connection disconnected"
        PrinterStatusState.TIMEOUT -> "Status check: Receiving data timed out"
        else -> "Unknown status code: $rawCode"
    }

    private fun tsplMessage(rawCode: Int): String = when (rawCode) {
        0x01 -> "Head opened"
        0x02 -> "Paper Jam"
        0x03 -> "Paper Jam and head opened"
        0x04 -> "Out of paper"
        0x05 -> "Out of paper and head opened"
        0x08 -> "Out of ribbon"
        0x09 -> "Out of ribbon and head opened"
        0x0A -> "Out of ribbon and paper jam"
        0x0B -> "Out of ribbon, paper jam and head opened"
        0x0C -> "Out of ribbon and out of paper"
        0x0D -> "Out of ribbon, out of paper and head opened"
        0x10 -> "Pause"
        0x20 -> "Printing"
        0x80 -> "Other error"
        -1 -> "Receive timeout"
        else -> "TSPL status code: 0x${rawCode.toString(16).uppercase()}"
    }
}
