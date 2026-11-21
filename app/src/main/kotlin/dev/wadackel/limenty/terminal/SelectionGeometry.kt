package dev.wadackel.limenty.terminal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where a selection sits in the view, in pixels, handles included.
 *
 * Its own type rather than `RectF`, which is a stub on the JVM the placement tests run on.
 */
data class SelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** What the selection toolbar is placed against: the selection, its own size, and the terminal's. */
data class ToolbarFit(
    val anchor: SelectionBounds,
    val width: Int,
    val height: Int,
    val containerWidth: Int,
    val containerHeight: Int,
    val gap: Float,
)

data class ToolbarPosition(val x: Int, val y: Int)

/**
 * Where a selection's handles sit on screen, and which one a touch has taken hold of.
 *
 * The user drags the handles, which makes them hit targets rather than markers.
 * The arithmetic lives here, apart from the view, because getting it wrong is invisible until
 * someone tries to adjust a selection on a device.
 */
object SelectionGeometry {
    /**
     * A handle is something a finger aims at, so it is sized in touch units rather than in cells.
     * Tying it to the cell made it 6dp across at the default font on a dense screen, and smaller
     * still whenever the user pinched the text down.
     */
    const val HANDLE_RADIUS_DP = 7f

    /** Roughly the 48dp target Android asks for, without swallowing a tap meant for the text. */
    const val HANDLE_REACH_DP = 20f

    /** The dot hangs just below its row, clear of the glyph it marks. */
    const val HANDLE_DROP_RADII = 0.6f

    /** How tall a row reads through the magnifier, whatever size the text itself is. */
    const val MAGNIFIED_ROW_DP = 32f

    /**
     * The platform default of 1.25 left a row at the default font about 22dp tall, still smaller
     * than the fingertip it is meant to see past. The floor keeps a large font from being shrunk.
     */
    const val MIN_MAGNIFIER_ZOOM = 1.25f

    const val MAGNIFIER_WIDTH_DP = 160f

    /** The row being adjusted with a sliver of its neighbours, so a wrap to the next row shows. */
    const val MAGNIFIER_ROWS = 1.6f

    fun handleRadius(metrics: TerminalMetrics): Float = HANDLE_RADIUS_DP * metrics.density

    fun handleReach(metrics: TerminalMetrics): Float = HANDLE_REACH_DP * metrics.density

    /**
     * Which end of a selection a touch grabbed, paired with the end that must stay put.
     *
     * [offsetX] and [offsetY] run from the touch to the centre of the grabbed cell. The drag carries
     * them along, because the handle hangs below its row and is wider than a cell: placing the end
     * under the finger moved it a row down, or a column over, the moment the handle was touched.
     */
    data class Grab(
        val movingLine: Int,
        val movingColumn: Int,
        val anchor: SelectionRange,
        val offsetX: Float,
        val offsetY: Float,
    )

    fun handleCentreX(column: Int, metrics: TerminalMetrics): Float = (column + 0.5f) * metrics.cellWidth

    fun handleCentreY(line: Int, scrollOffset: Int, metrics: TerminalMetrics): Float = (line + scrollOffset + 1) * metrics.cellHeight + handleRadius(metrics) * HANDLE_DROP_RADII

    /**
     * The handle within [reach] of the touch, or null.
     *
     * Ties go to the end nearest the touch so that a selection collapsed onto a single cell, where
     * both handles sit together, still picks one rather than always the first.
     */
    fun grabAt(
        x: Float,
        y: Float,
        selection: SelectionRange,
        scrollOffset: Int,
        metrics: TerminalMetrics,
        reach: Float,
    ): Grab? {
        val ends = listOf(
            Triple(selection.startLine, selection.startColumn, selection.endLine to selection.endColumn),
            Triple(selection.endLine, selection.endColumn, selection.startLine to selection.startColumn),
        )
        var best: Grab? = null
        var bestDistance = Float.MAX_VALUE
        for ((line, column, opposite) in ends) {
            val distance = hypot(
                x - handleCentreX(column, metrics),
                y - handleCentreY(line, scrollOffset, metrics),
            )
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance
                val (anchorLine, anchorColumn) = opposite
                best = Grab(
                    movingLine = line,
                    movingColumn = column,
                    // A degenerate range standing in for "this end holds still", carrying the
                    // scroll count so the anchor keeps addressing the same text as output arrives.
                    anchor = SelectionRange(
                        anchorLine,
                        anchorColumn,
                        anchorLine,
                        anchorColumn,
                        selection.scrolledAt,
                    ),
                    offsetX = handleCentreX(column, metrics) - x,
                    offsetY = (line + scrollOffset + 0.5f) * metrics.cellHeight - y,
                )
            }
        }
        return best
    }

    fun magnifierZoom(metrics: TerminalMetrics): Float = (MAGNIFIED_ROW_DP * metrics.density / metrics.cellHeight).coerceAtLeast(MIN_MAGNIFIER_ZOOM)

    fun magnifierWidth(metrics: TerminalMetrics): Float = MAGNIFIER_WIDTH_DP * metrics.density

    fun magnifierHeight(metrics: TerminalMetrics): Float = metrics.cellHeight * magnifierZoom(metrics) * MAGNIFIER_ROWS

    /**
     * From the centre of the row being magnified to the centre of the magnifier.
     *
     * The platform default places it a fixed distance above, which at a small font lands it on
     * the handle and the finger holding it. This lifts its bottom edge a whole row clear instead.
     */
    fun magnifierLift(metrics: TerminalMetrics): Float = -(metrics.cellHeight * 1.5f + magnifierHeight(metrics) / 2)

    /**
     * Where a dragged end sits along one axis: the finger plus the grab offset, with the offset
     * fading to nothing as the finger closes on either edge of [extent].
     *
     * A fixed offset would leave the first or last cell out of reach, since the finger cannot go
     * past the edge of the view. The fade can pull the end one cell towards the edge on the first
     * move after a grab close to it, which is the cost of keeping this a single expression.
     */
    fun carry(finger: Float, offset: Float, extent: Float): Float {
        if (offset == 0f) return finger
        val room = if (offset > 0f) finger else extent - finger
        return finger + offset * (room / abs(offset)).coerceIn(0f, 1f)
    }

    /**
     * The selection's extent in the view. A range over several rows takes the full width, since its
     * middle rows do; the bottom reaches the end handle's dot, which a toolbar below must clear.
     */
    fun selectionBounds(
        selection: SelectionRange,
        scrollOffset: Int,
        columns: Int,
        metrics: TerminalMetrics,
    ): SelectionBounds {
        val range = selection.normalized
        val singleRow = range.startLine == range.endLine
        return SelectionBounds(
            left = if (singleRow) range.startColumn * metrics.cellWidth else 0f,
            top = (range.startLine + scrollOffset) * metrics.cellHeight,
            right = (if (singleRow) range.endColumn + 1 else columns) * metrics.cellWidth,
            bottom = handleCentreY(range.endLine, scrollOffset, metrics) + handleRadius(metrics),
        )
    }

    /**
     * Above the selection, centred on it, as the platform's own text toolbar sits; below it when the
     * top row leaves no room. A selection scrolled out of view keeps its toolbar at the nearer edge
     * rather than hiding it, because the selection is still live and Copy still works.
     */
    fun toolbarPosition(fit: ToolbarFit): ToolbarPosition {
        val anchor = fit.anchor
        val above = floor(anchor.top - fit.gap - fit.height).toInt()
        val below = ceil(anchor.bottom + fit.gap).toInt()
        val y = if (above >= 0) above else below
        val x = ((anchor.left + anchor.right) / 2 - fit.width / 2f).roundToInt()
        return ToolbarPosition(
            x = x.coerceIn(0, (fit.containerWidth - fit.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (fit.containerHeight - fit.height).coerceAtLeast(0)),
        )
    }
}
