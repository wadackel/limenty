package dev.wadackel.limenty.terminal

import android.graphics.Paint
import dev.wadackel.limenty.core.terminal.TerminalTypography

/**
 * The pixel geometry of one terminal cell.
 *
 * Cell width is measured from a single ASCII advance rather than from the widest glyph: the grid
 * is monospaced by definition, and any other basis makes CJK and Nerd Font glyphs disagree with
 * the column arithmetic the remote is doing.
 */
data class TerminalMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
    val baseline: Float,
    val fontSizePx: Float,
    val letterSpacingPx: Float,
    /** Screen density, for the parts of the UI measured in touch units rather than text units. */
    val density: Float = 1f,
) {
    fun columnsFor(widthPx: Float): Int = ((widthPx / cellWidth).toInt()).coerceAtLeast(MIN_COLUMNS)

    fun rowsFor(heightPx: Float): Int = ((heightPx / cellHeight).toInt()).coerceAtLeast(MIN_ROWS)

    companion object {
        /**
         * A viewport can shrink below anything usable — a split-screen sliver, a DeX window being
         * dragged. Clamping keeps the PTY size legal instead of sending zero to the remote.
         */
        const val MIN_COLUMNS = 8
        const val MIN_ROWS = 2

        fun measure(paint: Paint, typography: TerminalTypography, density: Float): TerminalMetrics {
            val fontSizePx = typography.fontSizeSp * density
            val letterSpacingPx = typography.letterSpacingSp * density
            paint.textSize = fontSizePx
            // The renderer leaves its spacing on the shared paint; measuring through it would fold
            // that into the advance, and cellWidth adds letterSpacingPx again below.
            paint.letterSpacing = 0f
            paint.textScaleX = 1f
            val advance = paint.measureText("M")
            val metrics = paint.fontMetrics
            val naturalHeight = metrics.descent - metrics.ascent
            val cellHeight = naturalHeight * typography.lineHeight
            // The extra line height is split above and below so text sits optically centred.
            val baseline = -metrics.ascent + (cellHeight - naturalHeight) / 2f
            return TerminalMetrics(
                cellWidth = advance + letterSpacingPx,
                cellHeight = cellHeight,
                baseline = baseline,
                fontSizePx = fontSizePx,
                letterSpacingPx = letterSpacingPx,
                density = density,
            )
        }
    }
}
