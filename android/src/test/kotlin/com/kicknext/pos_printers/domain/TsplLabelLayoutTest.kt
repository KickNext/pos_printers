package com.kicknext.pos_printers.domain

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TsplLabelLayoutTest {
    @Test
    fun `label layout keeps physical millimeters separate from bitmap dots`() {
        val layout = TsplLabelLayout(
            widthMm = 58.0,
            heightMm = 60.0,
            gapMm = 2.0,
            dpi = 203,
            bitmapWidthDots = 416,
        )

        assertEquals(58.0, layout.widthMm)
        assertEquals(60.0, layout.heightMm)
        assertEquals(2.0, layout.gapMm)
        assertEquals(416, layout.bitmapWidthDots)
        assertEquals((58.0 * 203.0 / 25.4).roundToInt(), layout.labelWidthDots)
        assertEquals((60.0 * 203.0 / 25.4).roundToInt(), layout.labelHeightDots)
    }

    @Test
    fun `invalid geometry fails before reaching the vendor sdk`() {
        assertFailsWith<IllegalArgumentException> {
            TsplLabelLayout(
                widthMm = 0.0,
                heightMm = 60.0,
                gapMm = 2.0,
                dpi = 203,
                bitmapWidthDots = 416,
            )
        }
    }
}
