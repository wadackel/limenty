package dev.wadackel.limenty.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws the Powerline separators U+E0B0–E0B7 as shapes that fill their cell.
 *
 * A separator only reads as one when it meets the coloured segments beside it edge to edge, and no
 * font glyph does: the text face's arrows stop short of a cell taller than the font's own line, and
 * the rounded ones exist only in the symbols face, whose one-em advance the renderer shrinks until
 * a half circle is under half as tall as its segment. The paths are WezTerm's, the terminal these
 * status lines are usually designed against.
 */
object PowerlineGlyphs {
    /** Draws [codePoint] into [cell] and returns true, or returns false when it is not a separator. */
    fun draw(canvas: Canvas, codePoint: Int, cell: RectF, path: Path, paint: Paint): Boolean {
        val left = cell.left
        val top = cell.top
        val right = cell.right
        val bottom = cell.bottom
        val middle = cell.centerY()
        val width = cell.width()
        val height = cell.height()
        // The flat side meets the next segment, whose background is filled without smoothing and
        // so stops at a whole pixel. Left on a fractional edge, the smoothed side would leave the
        // pixel between them half-covered and the terminal background would show through it.
        val flatLeft = floor(left)
        val flatRight = ceil(right)
        path.reset()
        val filled = when (codePoint) {
            0xE0B0 -> {
                path.moveTo(flatLeft, top)
                path.lineTo(flatLeft, bottom)
                path.lineTo(right, middle)
                path.close()
                true
            }
            0xE0B1 -> {
                path.moveTo(left, top)
                path.lineTo(right, middle)
                path.lineTo(left, bottom)
                false
            }
            0xE0B2 -> {
                path.moveTo(flatRight, top)
                path.lineTo(flatRight, bottom)
                path.lineTo(left, middle)
                path.close()
                true
            }
            0xE0B3 -> {
                path.moveTo(right, top)
                path.lineTo(left, middle)
                path.lineTo(right, bottom)
                false
            }
            0xE0B4 -> {
                path.moveTo(flatLeft, top)
                path.quadTo(right, top, right, middle)
                path.quadTo(right, bottom, flatLeft, bottom)
                path.close()
                true
            }
            0xE0B5 -> {
                path.moveTo(left - width / 4f, top - height / 3f)
                path.quadTo(left + width * 7f / 4f, middle, left - width / 4f, top + height * 4f / 3f)
                false
            }
            0xE0B6 -> {
                path.moveTo(flatRight, top)
                path.quadTo(left, top, left, middle)
                path.quadTo(left, bottom, flatRight, bottom)
                path.close()
                true
            }
            0xE0B7 -> {
                path.moveTo(left + width * 5f / 4f, top - height / 3f)
                path.quadTo(left - width * 3f / 4f, middle, left + width * 5f / 4f, top + height * 4f / 3f)
                false
            }
            else -> return false
        }
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        if (filled) {
            canvas.drawPath(path, paint)
        } else {
            // The outline curves overshoot the cell so that only their flatter middle shows; left
            // unclipped they would draw into the rows above and below.
            canvas.save()
            canvas.clipRect(cell)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        return true
    }
}
