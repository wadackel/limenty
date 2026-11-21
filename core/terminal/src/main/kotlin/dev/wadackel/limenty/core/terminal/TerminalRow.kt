package dev.wadackel.limenty.core.terminal

/**
 * One line of the terminal.
 *
 * Cells are held in parallel primitive arrays rather than as objects: a scrollback of thousands
 * of lines would otherwise allocate millions of small objects, which is exactly the pressure that
 * must not reach the UI thread.
 */
class TerminalRow(columns: Int) {
    var codePoints: IntArray = IntArray(columns) { SPACE }
        private set
    var foreground: IntArray = IntArray(columns)
        private set
    var background: IntArray = IntArray(columns)
        private set
    var flags: IntArray = IntArray(columns)
        private set

    val columns: Int get() = codePoints.size

    /**
     * True when the line ran off the right edge and continues on the next one.
     *
     * Recorded at write time because it cannot be recovered afterwards, and selection and reflow
     * both need to know whether a line break was the user's or the terminal's.
     */
    var wrapped: Boolean = false

    /** Combining marks attached to a cell. Allocated only for the rare lines that carry any. */
    private var combining: MutableMap<Int, IntArray>? = null

    fun codePointAt(column: Int): Int = codePoints[column]

    fun foregroundAt(column: Int): TerminalColor = TerminalColor(foreground[column])

    fun backgroundAt(column: Int): TerminalColor = TerminalColor(background[column])

    fun flagsAt(column: Int): Int = flags[column]

    fun combiningAt(column: Int): IntArray? = combining?.get(column)

    fun setCell(column: Int, codePoint: Int, pen: Pen, extraFlags: Int = 0) {
        codePoints[column] = codePoint
        foreground[column] = pen.foreground.packed
        background[column] = pen.background.packed
        flags[column] = pen.flags or extraFlags
        combining?.remove(column)
    }

    fun appendCombining(column: Int, codePoint: Int) {
        val map = combining ?: HashMap<Int, IntArray>(2).also { combining = it }
        val existing = map[column] ?: run {
            map[column] = intArrayOf(codePoint)
            return
        }
        // Tested before the copy rather than after it. Appending duplicates the array every time,
        // so without a ceiling the work and the allocation grow with the square of the run: 16,000
        // marks on one cell — 32 KB of input a remote can send in two chunks — allocated 489 MiB
        // on the way through, and the pump holds the terminal lock the renderer also takes.
        if (existing.size >= MAX_COMBINING) return
        map[column] = existing + codePoint
    }

    fun clear(pen: Pen) = clearRange(0, columns, pen)

    fun clearRange(from: Int, to: Int, pen: Pen) {
        for (column in from until to) {
            codePoints[column] = SPACE
            foreground[column] = pen.foreground.packed
            // Only the background survives an erase: that is what makes `clear` on a themed shell
            // paint the whole line rather than leaving stripes of the previous colour.
            background[column] = pen.background.packed
            flags[column] = 0
        }
        combining?.keys?.removeAll { it in from until to }
        if (from == 0 && to == columns) wrapped = false
    }

    /** Shifts cells right within the row, as `CSI @` (insert characters) requires. */
    fun insert(column: Int, count: Int, pen: Pen) {
        val moved = columns - column - count
        if (moved > 0) {
            shift(column, column + count, moved)
        }
        clearRange(column, minOf(column + count, columns), pen)
    }

    /** Shifts cells left within the row, as `CSI P` (delete characters) requires. */
    fun delete(column: Int, count: Int, pen: Pen) {
        val moved = columns - column - count
        if (moved > 0) {
            shift(column + count, column, moved)
        }
        clearRange(maxOf(column, columns - count), columns, pen)
    }

    private fun shift(from: Int, to: Int, count: Int) {
        System.arraycopy(codePoints, from, codePoints, to, count)
        System.arraycopy(foreground, from, foreground, to, count)
        System.arraycopy(background, from, background, to, count)
        System.arraycopy(flags, from, flags, to, count)
        combining?.let { map ->
            if (map.isEmpty()) return@let
            val shifted = HashMap<Int, IntArray>(map.size)
            map.forEach { (column, marks) ->
                if (column in from until from + count) {
                    val target = column - from + to
                    if (target in 0 until columns) shifted[target] = marks
                } else {
                    // Cells outside the moved span keep their marks; rebuilding from the moved
                    // entries alone silently stripped accents elsewhere on the line.
                    shifted[column] = marks
                }
            }
            combining = shifted
        }
    }

    fun resize(newColumns: Int, pen: Pen) {
        val oldColumns = columns
        if (newColumns == oldColumns) return
        codePoints = codePoints.copyOf(newColumns)
        foreground = foreground.copyOf(newColumns)
        background = background.copyOf(newColumns)
        flags = flags.copyOf(newColumns)
        if (newColumns > oldColumns) {
            // copyOf zero-fills, but a blank cell is a space carrying the current pen.
            clearRange(oldColumns, newColumns, pen)
        }
        combining?.keys?.removeAll { it >= newColumns }
        // Nothing reflows, so a row cut or padded to a new width no longer runs on into the next
        // one, and keeping the mark would join a copy or a link across text the screen dropped.
        // A row restored from scrollback at the width it was stored at takes the early return and
        // keeps its mark.
        wrapped = false
    }

    /**
     * A copy trimmed to its last non-blank cell, or kept at full width when the row wrapped.
     *
     * Scrollback stores these rather than full-width rows: most output lines are far shorter than
     * the screen, and a phone cannot afford to keep thousands of mostly-empty rows at full width.
     */
    fun trimmedCopy(): TerminalRow {
        var end = columns
        // A wrapped row keeps its width: its edge can be blank (a wide character pushed to the next
        // row, a space that landed in the last cell), and trimming that would make it come back
        // narrower than the screen, which resize reads as a width change and unwraps.
        if (!wrapped) {
            while (end > 0 && isBlank(end - 1)) end--
        }
        val copy = TerminalRow(end)
        System.arraycopy(codePoints, 0, copy.codePoints, 0, end)
        System.arraycopy(foreground, 0, copy.foreground, 0, end)
        System.arraycopy(background, 0, copy.background, 0, end)
        System.arraycopy(flags, 0, copy.flags, 0, end)
        copy.wrapped = wrapped
        combining?.let { map ->
            map.forEach { (column, marks) -> if (column < end) copy.appendCombiningAll(column, marks) }
        }
        return copy
    }

    private fun appendCombiningAll(column: Int, marks: IntArray) {
        val map = combining ?: HashMap<Int, IntArray>(2).also { combining = it }
        map[column] = marks
    }

    /** True when the row holds nothing a user would miss — used to decide what a shrink may drop. */
    fun isBlank(): Boolean {
        for (column in 0 until columns) if (!isBlank(column)) return false
        return true
    }

    private fun isBlank(column: Int): Boolean = codePoints[column] == SPACE &&
        flags[column] == 0 &&
        background[column] == 0 &&
        foreground[column] == 0

    /** The row's text, with trailing blanks removed — used by selection, copy and tests. */
    fun text(from: Int = 0, to: Int = columns): String = buildString {
        for (column in from until minOf(to, columns)) {
            if (flags[column] and CellFlags.WIDE_TRAILING != 0) continue
            appendCodePoint(codePoints[column])
            combining?.get(column)?.forEach { appendCodePoint(it) }
        }
    }

    companion object {
        const val SPACE = ' '.code

        /**
         * How many combining marks one cell keeps.
         *
         * The longest run any real text produces is six — the five tag characters spelling a
         * subdivision flag's region, plus the cancel tag that ends it; a keycap is two, an NFD
         * accent one. Well over twice that leaves room for a sequence nobody has written down yet
         * while still bounding what one cell can be made to hold. Marks past the ceiling are
         * dropped: anything that deep is no longer a character being read.
         */
        const val MAX_COMBINING = 16
    }
}
