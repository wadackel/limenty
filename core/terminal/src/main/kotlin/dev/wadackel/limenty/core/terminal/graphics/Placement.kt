package dev.wadackel.limenty.core.terminal.graphics

/**
 * One image drawn over the grid.
 *
 * [row] lives in the buffer's line space — 0 is the top of the screen, negative values reach
 * into scrollback — and the buffer moves it as lines scroll, so the image stays with the text
 * it was placed beside. Everything else is fixed at placement time: the cell size known then
 * has already been folded into [columns] and [rows], and the renderer only has to scale.
 */
class Placement(
    val imageId: Int,
    val placementId: Int,
    var row: Int,
    val column: Int,
    val columns: Int,
    val rows: Int,
    /** Source rectangle in image pixels; a zero width or height means the whole image. */
    val sourceX: Int = 0,
    val sourceY: Int = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
) {
    /** True when any of the placement's rows lies on the visible screen or below its top. */
    fun reachesScreen(): Boolean = row + rows > 0
}
