package dev.wadackel.limenty.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import dev.wadackel.limenty.core.terminal.CellFlags
import dev.wadackel.limenty.core.terminal.CharacterWidth
import dev.wadackel.limenty.core.terminal.Cursor
import dev.wadackel.limenty.core.terminal.TerminalBuffer
import dev.wadackel.limenty.core.terminal.TerminalColor
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalRow
import dev.wadackel.limenty.core.terminal.graphics.GraphicsStore

/**
 * Draws the terminal grid onto a canvas.
 *
 * Runs of cells sharing a background and a style are drawn together rather than cell by cell: a
 * full screen is ~2000 cells and a per-cell `drawText` call at 60fps is the difference between a
 * responsive terminal and one that stutters under `cat`.
 */
class TerminalRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
        // A ligature draws `->` as one arrow across two cells, so the screen stops showing the
        // characters the remote wrote, and a cursor or selection on either cell lands on half a glyph.
        fontFeatureSettings = "'calt' 0, 'liga' 0"
    }
    private val fillPaint = Paint()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageSource = Rect()
    private val imageTarget = RectF()

    /** Separate from [textPaint] so drawing an outline separator never leaves the text stroked. */
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = Path()
    private val shapeCell = RectF()

    private val builder = StringBuilder(256)

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            textPaint.typeface = value
        }

    /**
     * The bold face of the same family.
     *
     * Synthetic bold widens glyphs by smearing them, which pushes a bold run past its cells; a
     * real bold face keeps the advance the grid was measured with.
     */
    var boldTypeface: Typeface? = null

    fun measurePaint(): Paint = Paint(textPaint)

    /**
     * Phase of the blink cycle. Blinking is driven by the caller rather than by a timer here so
     * every blinking cell changes together, and so a screen with none of them costs no frames.
     */
    var blinkOn: Boolean = true

    fun draw(
        canvas: Canvas,
        buffer: TerminalBuffer,
        metrics: TerminalMetrics,
        palette: TerminalPalette,
        cursor: Cursor,
        /** 0 shows the live screen; positive values look that many lines back. */
        scrollOffset: Int,
        selection: SelectionRange?,
        showCursor: Boolean,
        graphics: GraphicsStore,
        bitmaps: GraphicsBitmapCache,
    ) {
        textPaint.textSize = metrics.fontSizePx
        // Letter spacing is expressed in ems by Paint but in pixels by the metrics. Leaving it at
        // zero here made a batched ASCII run advance by the bare glyph width while the background
        // rectangles, the cursor and every cell-by-cell glyph used the spaced cell width.
        textPaint.letterSpacing =
            if (metrics.fontSizePx > 0f) metrics.letterSpacingPx / metrics.fontSizePx else 0f
        canvas.drawColor(palette.background)

        val visibleRows = buffer.rows
        for (screenRow in 0 until visibleRows) {
            val line = screenRow - scrollOffset
            val row = buffer.line(line) ?: continue
            drawRow(canvas, row, screenRow, metrics, palette, selection, line)
        }

        drawGraphics(canvas, buffer, graphics, bitmaps, metrics, scrollOffset)

        if (showCursor && cursor.visible && scrollOffset == 0) {
            drawCursor(canvas, buffer, metrics, palette, cursor)
        }

        selection?.let { drawSelectionHandles(canvas, it, metrics, palette, scrollOffset) }
    }

    /**
     * Draws placed images over the text, in the order they were placed.
     *
     * Images come after every row and before the cursor: the protocol's default puts them in
     * front of the text, and a cursor hidden under a preview would be the one thing the user
     * cannot find. A placement's row is in the buffer's line space, so the scroll offset moves it
     * exactly as it moves the rows.
     */
    private fun drawGraphics(
        canvas: Canvas,
        buffer: TerminalBuffer,
        graphics: GraphicsStore,
        bitmaps: GraphicsBitmapCache,
        metrics: TerminalMetrics,
        scrollOffset: Int,
    ) {
        bitmaps.prune(graphics)
        for (placement in buffer.placements) {
            val screenRow = placement.row + scrollOffset
            if (screenRow.toLong() + placement.rows <= 0 || screenRow >= buffer.rows) continue
            val image = graphics.image(placement.imageId) ?: continue
            val bitmap = bitmaps.bitmapFor(image) ?: continue

            val sourceWidth = if (placement.sourceWidth > 0) placement.sourceWidth else bitmap.width - placement.sourceX
            val sourceHeight = if (placement.sourceHeight > 0) placement.sourceHeight else bitmap.height - placement.sourceY
            imageSource.set(
                placement.sourceX.coerceIn(0, bitmap.width),
                placement.sourceY.coerceIn(0, bitmap.height),
                (placement.sourceX + sourceWidth).coerceIn(0, bitmap.width),
                (placement.sourceY + sourceHeight).coerceIn(0, bitmap.height),
            )
            if (imageSource.isEmpty) continue

            val left = placement.column * metrics.cellWidth
            val top = screenRow * metrics.cellHeight
            imageTarget.set(
                left,
                top,
                left + placement.columns * metrics.cellWidth,
                top + placement.rows * metrics.cellHeight,
            )
            canvas.drawBitmap(bitmap, imageSource, imageTarget, imagePaint)
        }
    }

    /**
     * Draws the IME preedit at the cursor cell.
     *
     * The composing text is in the terminal itself rather than in a panel, because the
     * text is about to be typed *there* — a candidate strip somewhere else leaves the user
     * choosing kanji while looking away from the line they are editing.
     *
     * It is drawn over the screen rather than into the buffer: the remote has not been told about
     * any of this, and writing it into cells would make it survive a redraw the remote triggers.
     */
    fun drawPreedit(
        canvas: Canvas,
        text: String,
        caret: Int,
        cursor: Cursor,
        metrics: TerminalMetrics,
        palette: TerminalPalette,
    ) {
        if (text.isEmpty()) return

        val columns = maxOf(1, (canvas.width / metrics.cellWidth).toInt())
        var column = cursor.column
        var row = cursor.row
        var index = 0
        var caretX: Float? = null

        textPaint.textSize = metrics.fontSizePx
        textPaint.typeface = typeface
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f

        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = CharacterWidth.of(codePoint).coerceAtLeast(1)

            if (column + width > columns) {
                column = 0
                row++
            }

            val x = column * metrics.cellWidth
            val y = row * metrics.cellHeight
            val cellWidth = metrics.cellWidth * width

            if (index == caret) caretX = x

            fillPaint.color = palette.selection
            canvas.drawRect(x, y, x + cellWidth, y + metrics.cellHeight, fillPaint)

            builder.setLength(0)
            builder.appendCodePoint(codePoint)
            textPaint.color = palette.foreground
            textPaint.letterSpacing = 0f
            textPaint.textScaleX = 1f
            val natural = textPaint.measureText(builder, 0, builder.length)
            if (natural > 0.1f) textPaint.textScaleX = cellWidth / natural
            canvas.drawText(builder, 0, builder.length, x, y + metrics.baseline, textPaint)
            textPaint.textScaleX = 1f

            // An underline is how every terminal marks text the IME still owns.
            val thickness = (metrics.fontSizePx / 12f).coerceAtLeast(1.5f)
            fillPaint.color = palette.foreground
            canvas.drawRect(
                x,
                y + metrics.cellHeight - thickness,
                x + cellWidth,
                y + metrics.cellHeight,
                fillPaint,
            )

            column += width
            index += Character.charCount(codePoint)
        }

        // The caret inside the preedit: the IME moves it during conversion, and without it the
        // user cannot tell which clause they are about to change.
        val markerX = caretX ?: (column * metrics.cellWidth)
        fillPaint.color = palette.cursor
        canvas.drawRect(
            markerX,
            row * metrics.cellHeight,
            markerX + maxOf(2f, metrics.cellWidth * 0.12f),
            row * metrics.cellHeight + metrics.cellHeight,
            fillPaint,
        )
    }

    /**
     * Marks the ends of a selection.
     *
     * Without them the selection reads as arbitrary highlighting; the handles are what tell the
     * user the range has ends they can drag.
     */
    private fun drawSelectionHandles(
        canvas: Canvas,
        selection: SelectionRange,
        metrics: TerminalMetrics,
        palette: TerminalPalette,
        scrollOffset: Int,
    ) {
        fillPaint.color = palette.cursor
        val radius = SelectionGeometry.handleRadius(metrics)
        listOf(
            selection.startLine to selection.startColumn,
            selection.endLine to selection.endColumn,
        ).forEach { (line, column) ->
            canvas.drawCircle(
                SelectionGeometry.handleCentreX(column, metrics),
                SelectionGeometry.handleCentreY(line, scrollOffset, metrics),
                radius,
                fillPaint,
            )
        }
    }

    private fun drawRow(
        canvas: Canvas,
        row: TerminalRow,
        screenRow: Int,
        metrics: TerminalMetrics,
        palette: TerminalPalette,
        selection: SelectionRange?,
        line: Int,
    ) {
        val top = screenRow * metrics.cellHeight
        val baseline = top + metrics.baseline
        var column = 0

        while (column < row.columns) {
            val flags = row.flagsAt(column)
            if (flags and CellFlags.WIDE_TRAILING != 0) {
                column++
                continue
            }

            val selected = selection?.contains(line, column) == true
            val background = backgroundOf(row, column, palette, flags, selected)
            val foreground = foregroundOf(row, column, palette, flags, selected)

            // Extend the run while the drawing state is identical, so one drawText covers it.
            var end = column + 1
            while (end < row.columns &&
                row.flagsAt(end) and CellFlags.WIDE_TRAILING == 0 &&
                row.flagsAt(end) == flags &&
                backgroundOf(row, end, palette, row.flagsAt(end), selection?.contains(line, end) == true) == background &&
                foregroundOf(row, end, palette, row.flagsAt(end), selection?.contains(line, end) == true) == foreground
            ) {
                end++
            }

            val runStartX = column * metrics.cellWidth
            val runEndColumn = widthAwareEnd(row, column, end)
            val runWidth = (runEndColumn - column) * metrics.cellWidth

            if (background != palette.background) {
                fillPaint.color = background
                canvas.drawRect(runStartX, top, runStartX + runWidth, top + metrics.cellHeight, fillPaint)
            }

            // SGR 5 is rendered by dropping the glyph on the off phase rather than by animating
            // opacity: a terminal blink is a hard on/off, and anything smoother reads as a bug.
            val hidden = flags and CellFlags.INVISIBLE != 0 ||
                (flags and CellFlags.BLINK != 0 && !blinkOn)
            if (!hidden) {
                applyStyle(flags, foreground, metrics)
                if (isPlainAscii(row, column, end)) {
                    builder.setLength(0)
                    for (cell in column until end) builder.appendCodePoint(row.codePointAt(cell))
                    textPaint.textScaleX = 1f
                    canvas.drawText(builder, 0, builder.length, runStartX, baseline, textPaint)
                } else {
                    drawCellByCell(canvas, row, column, end, top, baseline, metrics)
                }
                drawDecorations(canvas, flags, runStartX, runWidth, top, metrics)
            }

            column = end
        }
    }

    /**
     * True when every cell in the run is plain ASCII with no marks.
     *
     * Only then can the run be handed to one `drawText`: the primary font's advance matches the
     * cell width by construction, whereas a glyph resolved from a fallback font advances by its
     * own metrics and drags everything after it out of column.
     */
    private fun isPlainAscii(row: TerminalRow, from: Int, to: Int): Boolean {
        for (cell in from until to) {
            val codePoint = row.codePointAt(cell)
            if (codePoint < 0x20 || codePoint > 0x7E) return false
            if (row.combiningAt(cell) != null) return false
        }
        return true
    }

    /**
     * Draws one cell at a time, scaling each glyph to the cells it occupies.
     *
     * Box drawing is the visible case: its glyphs come from a fallback face and are narrower than
     * the cell, so without scaling the horizontal strokes stop short and a drawn frame appears
     * broken. Scaling also keeps CJK and Nerd Font glyphs inside their own columns.
     */
    private fun drawCellByCell(
        canvas: Canvas,
        row: TerminalRow,
        from: Int,
        to: Int,
        top: Float,
        baseline: Float,
        metrics: TerminalMetrics,
    ) {
        shapePaint.color = textPaint.color
        shapePaint.strokeWidth = lineThickness(metrics)
        var cell = from
        while (cell < to) {
            if (row.flagsAt(cell) and CellFlags.WIDE_TRAILING != 0) {
                cell++
                continue
            }
            val cellLeft = cell * metrics.cellWidth
            shapeCell.set(cellLeft, top, cellLeft + metrics.cellWidth, top + metrics.cellHeight)
            if (PowerlineGlyphs.draw(canvas, row.codePointAt(cell), shapeCell, shapePath, shapePaint)) {
                cell++
                continue
            }
            builder.setLength(0)
            builder.appendCodePoint(row.codePointAt(cell))
            row.combiningAt(cell)?.forEach { builder.appendCodePoint(it) }

            val cells = if (row.flagsAt(cell) and CellFlags.WIDE_LEADING != 0) 2 else 1
            val target = metrics.cellWidth * cells

            textPaint.textScaleX = 1f
            textPaint.letterSpacing = 0f
            val natural = textPaint.measureText(builder, 0, builder.length)
            // Scale both axes or neither. Forcing the advance to the cell with textScaleX alone
            // stretched CJK by a quarter and squeezed Nerd Font icons by two fifths, at unchanged
            // height, which reads as flattened glyphs: the bundled symbols face advances a full em
            // per icon while a monospace cell is about six tenths of one.
            val fit = if (natural > target && natural > 0.1f) target / natural else 1f
            if (fit < 1f) textPaint.textSize = metrics.fontSizePx * fit
            // Centred in its cells, so a glyph narrower than the span is not pinned to one edge.
            val left = cell * metrics.cellWidth + (target - natural * fit) / 2f
            // Shrunk about the cell's middle rather than about the baseline. Shrinking toward the
            // baseline drops a Nerd Font icon by a fifth of an em, below the text beside it.
            val centre = top + metrics.cellHeight / 2f
            val glyphBaseline = centre + (baseline - centre) * fit

            canvas.drawText(builder, 0, builder.length, left, glyphBaseline, textPaint)
            if (fit < 1f) textPaint.textSize = metrics.fontSizePx
            cell++
        }
        textPaint.textScaleX = 1f
        textPaint.letterSpacing =
            if (metrics.fontSizePx > 0f) metrics.letterSpacingPx / metrics.fontSizePx else 0f
    }

    /** Includes the trailing half of a wide character that sits at the end of a run. */
    private fun widthAwareEnd(row: TerminalRow, start: Int, end: Int): Int {
        var result = end
        while (result < row.columns && row.flagsAt(result) and CellFlags.WIDE_TRAILING != 0) result++
        return result
    }

    private fun applyStyle(flags: Int, foreground: Int, metrics: TerminalMetrics) {
        textPaint.color = if (flags and CellFlags.DIM != 0) dim(foreground) else foreground
        val wantsBold = flags and CellFlags.BOLD != 0
        val bold = boldTypeface
        if (wantsBold && bold != null) {
            textPaint.typeface = bold
            textPaint.isFakeBoldText = false
        } else {
            textPaint.typeface = typeface
            textPaint.isFakeBoldText = wantsBold
        }
        textPaint.textSkewX = if (flags and CellFlags.ITALIC != 0) ITALIC_SKEW else 0f
        textPaint.textSize = metrics.fontSizePx
    }

    private fun drawDecorations(
        canvas: Canvas,
        flags: Int,
        x: Float,
        width: Float,
        top: Float,
        metrics: TerminalMetrics,
    ) {
        val thickness = lineThickness(metrics)
        fillPaint.color = textPaint.color
        if (flags and CellFlags.UNDERLINE != 0) {
            val y = top + metrics.baseline + thickness * 2
            canvas.drawRect(x, y, x + width, y + thickness, fillPaint)
        }
        if (flags and CellFlags.DOUBLE_UNDERLINE != 0) {
            val y = top + metrics.baseline + thickness * 2
            canvas.drawRect(x, y, x + width, y + thickness, fillPaint)
            canvas.drawRect(x, y + thickness * 2, x + width, y + thickness * 3, fillPaint)
        }
        if (flags and CellFlags.STRIKETHROUGH != 0) {
            val y = top + metrics.cellHeight * 0.55f
            canvas.drawRect(x, y, x + width, y + thickness, fillPaint)
        }
    }

    private fun lineThickness(metrics: TerminalMetrics): Float = (metrics.fontSizePx / 14f).coerceAtLeast(1f)

    /**
     * Draws the block cursor and repaints the character underneath it in the background colour.
     *
     * A plain opaque block hides whatever it covers, which in nvim's normal mode is every
     * character the user is looking at.
     */
    private fun drawCursor(
        canvas: Canvas,
        buffer: TerminalBuffer,
        metrics: TerminalMetrics,
        palette: TerminalPalette,
        cursor: Cursor,
    ) {
        val x = cursor.column * metrics.cellWidth
        val y = cursor.row * metrics.cellHeight
        fillPaint.color = palette.cursor
        canvas.drawRect(x, y, x + metrics.cellWidth, y + metrics.cellHeight, fillPaint)

        val row = buffer.line(cursor.row) ?: return
        if (cursor.column >= row.columns) return
        val codePoint = row.codePointAt(cursor.column)
        if (codePoint == TerminalRow.SPACE && row.combiningAt(cursor.column) == null) return

        builder.setLength(0)
        builder.appendCodePoint(codePoint)
        row.combiningAt(cursor.column)?.forEach { builder.appendCodePoint(it) }

        textPaint.color = palette.background
        textPaint.typeface = typeface
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.textScaleX = 1f
        textPaint.letterSpacing = 0f
        val cells = if (row.flagsAt(cursor.column) and CellFlags.WIDE_LEADING != 0) 2 else 1
        val natural = textPaint.measureText(builder, 0, builder.length)
        if (natural > 0.1f) textPaint.textScaleX = metrics.cellWidth * cells / natural
        canvas.drawText(builder, 0, builder.length, x, y + metrics.baseline, textPaint)
        textPaint.textScaleX = 1f
    }

    private fun backgroundOf(
        row: TerminalRow,
        column: Int,
        palette: TerminalPalette,
        flags: Int,
        selected: Boolean,
    ): Int {
        if (selected) return palette.selection
        val colour = if (flags and CellFlags.REVERSE != 0) row.foregroundAt(column) else row.backgroundAt(column)
        val fallback = if (flags and CellFlags.REVERSE != 0) palette.foreground else palette.background
        return resolve(colour, palette, fallback)
    }

    private fun foregroundOf(
        row: TerminalRow,
        column: Int,
        palette: TerminalPalette,
        flags: Int,
        selected: Boolean,
    ): Int {
        val colour = if (flags and CellFlags.REVERSE != 0) row.backgroundAt(column) else row.foregroundAt(column)
        val fallback = if (flags and CellFlags.REVERSE != 0) palette.background else palette.foreground
        val resolved = resolve(colour, palette, fallback)
        return if (selected && resolved == palette.selection) palette.foreground else resolved
    }

    private fun resolve(colour: TerminalColor, palette: TerminalPalette, fallback: Int): Int = when {
        colour.isDefault -> fallback
        colour.isIndexed -> palette.resolve(colour.index)
        else -> (0xFF shl 24) or (colour.red shl 16) or (colour.green shl 8) or colour.blue
    }

    /** SGR 2 renders at reduced intensity; blending toward black is what every terminal does. */
    private fun dim(colour: Int): Int {
        val red = ((colour shr 16 and 0xFF) * DIM_FACTOR).toInt()
        val green = ((colour shr 8 and 0xFF) * DIM_FACTOR).toInt()
        val blue = ((colour and 0xFF) * DIM_FACTOR).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        const val ITALIC_SKEW = -0.25f
        const val DIM_FACTOR = 0.6f
    }
}
