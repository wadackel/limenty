package dev.wadackel.limenty.core.terminal.graphics

/**
 * One cell's size in device pixels, as the view that draws the grid measured it.
 *
 * The emulator needs it to turn a single-axis placement into whole cells and to answer a
 * client asking for the window size in pixels; both must agree with what the view draws, so the
 * value is pushed in from the view rather than guessed here.
 */
data class CellPixelSize(val width: Int, val height: Int) {
    companion object {
        /** Stands in until the view has measured; every real terminal font is in this vicinity. */
        val Unmeasured = CellPixelSize(8, 16)
    }
}
