package com.kicknext.pos_printers.domain

import kotlin.math.roundToInt

data class TsplLabelLayout(
    val widthMm: Double,
    val heightMm: Double,
    val gapMm: Double,
    val dpi: Int,
    val bitmapWidthDots: Int,
) {
    init {
        require(widthMm > 0.0) { "Label width must be positive" }
        require(heightMm > 0.0) { "Label height must be positive" }
        require(gapMm >= 0.0) { "Label gap cannot be negative" }
        require(dpi > 0) { "DPI must be positive" }
        require(bitmapWidthDots > 0) { "Bitmap width must be positive" }
    }

    val dotsPerMm: Double = dpi / 25.4
    val labelWidthDots: Int = (widthMm * dotsPerMm).roundToInt()
    val labelHeightDots: Int = (heightMm * dotsPerMm).roundToInt()

    companion object {
        fun fromMedia(
            widthMm: Double,
            heightMm: Double,
            gapMm: Double,
            dpi: Int,
            bitmapWidthDots: Int,
        ): TsplLabelLayout {
            return TsplLabelLayout(
                widthMm = widthMm,
                heightMm = heightMm,
                gapMm = gapMm,
                dpi = dpi,
                bitmapWidthDots = bitmapWidthDots,
            )
        }

        fun legacyFromBitmap(widthDots: Int, bitmapHeightDots: Int): TsplLabelLayout {
            val dpi = 203
            val dotsPerMm = dpi / 25.4
            return TsplLabelLayout(
                widthMm = widthDots / dotsPerMm,
                heightMm = bitmapHeightDots / dotsPerMm,
                gapMm = 2.0,
                dpi = dpi,
                bitmapWidthDots = widthDots,
            )
        }
    }
}
