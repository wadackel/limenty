package dev.wadackel.limenty.core.terminal

import dev.wadackel.limenty.core.terminal.graphics.Placement

/**
 * The visible screen plus the lines that have scrolled off it.
 *
 * Scrollback and screen are one addressable space: line -[scrollbackSize] is the oldest retained
 * line and line 0 is the top of the screen. Selection, rendering and copy all work in that space,
 * so none of them has to special-case the boundary a scroll moves across.
 */
class TerminalBuffer(
    columns: Int,
    rows: Int,
    private val maxScrollback: Int = DEFAULT_SCROLLBACK,
) {
    var columns: Int = columns
        private set

    var rows: Int = rows
        private set

    private var screen = ArrayList<TerminalRow>(rows).apply {
        repeat(rows) { add(TerminalRow(columns)) }
    }

    private val scrollback = ArrayDeque<TerminalRow>()

    val scrollbackSize: Int get() = scrollback.size

    /**
     * Total lines ever pushed into scrollback, never reset.
     *
     * Line indices shift as the buffer scrolls and as old lines are dropped at the cap, so a
     * selection or a scroll position needs an identity that survives both.
     */
    var totalLinesScrolled: Long = 0L
        private set

    /** The number of lines addressable above the screen; negative line numbers index into them. */
    val totalLines: Int get() = scrollback.size + rows

    /**
     * Images placed over this buffer, in the order they were placed.
     *
     * Kept beside the rows rather than inside them because a row object is recycled as it
     * scrolls off (see [scrollUp]); the list is what lets an image follow the text instead.
     */
    private val placementList = ArrayList<Placement>()

    val placements: List<Placement> get() = placementList

    fun place(placement: Placement) {
        // A client that places without ever scrolling or deleting would otherwise grow this list
        // without bound, and every frame walks all of it.
        if (placementList.size >= MAX_PLACEMENTS) placementList.removeAt(0)
        placementList += placement
    }

    /** Removes matching placements and reports whether there were any. */
    fun removePlacements(predicate: (Placement) -> Boolean): Boolean = placementList.removeAll(predicate)

    fun hasPlacementOf(imageId: Int): Boolean = placementList.any { it.imageId == imageId }

    /**
     * Shifts placements the way a scroll moved the lines under them, and forgets those that
     * end up somewhere no line can be addressed any more.
     */
    private fun shiftPlacements(top: Int, bottom: Int, delta: Int, intoScrollback: Boolean) {
        val iterator = placementList.iterator()
        while (iterator.hasNext()) {
            val placement = iterator.next()
            val inRegion = placement.row in top..bottom
            val inScrollback = intoScrollback && placement.row < top
            if (!inRegion && !inScrollback) continue
            placement.row += delta
            val gone = when {
                placement.row > bottom -> true
                placement.row >= top -> false
                intoScrollback -> placement.row < -maxScrollback
                else -> true
            }
            if (gone) iterator.remove()
        }
    }

    fun screenRow(row: Int): TerminalRow = screen[row]

    /** Line 0 is the top of the screen; negative values reach into scrollback. */
    fun line(line: Int): TerminalRow? = when {
        line >= 0 && line < rows -> screen[line]
        line < 0 && -line <= scrollback.size -> scrollback[scrollback.size + line]
        else -> null
    }

    /** Returns the images whose placements went, so the caller can decide about their pixels. */
    fun clearScrollback(): Set<Int> {
        scrollback.clear()
        return removePlacementsReturningImages { it.row + it.rows <= 0 }
    }

    /**
     * Scrolls [count] lines out of the region, into the scrollback when [retain] says so. The
     * caller decides, because only it knows whether the region is the user's history or an
     * application's own viewport.
     */
    fun scrollUp(top: Int, bottom: Int, count: Int, pen: Pen, retain: Boolean) {
        val amount = count.coerceAtMost(bottom - top + 1)
        shiftPlacements(top, bottom, -amount, intoScrollback = retain && maxScrollback > 0)
        repeat(amount) {
            val removed = screen.removeAt(top)
            // `push` stores a trimmed copy, so the row object itself can be recycled as the blank
            // line appearing at the bottom rather than allocating a new one per scrolled line.
            if (retain) push(removed)
            removed.clear(pen)
            screen.add(bottom, removed)
        }
    }

    fun scrollDown(top: Int, bottom: Int, count: Int, pen: Pen) {
        val amount = count.coerceAtMost(bottom - top + 1)
        shiftPlacements(top, bottom, amount, intoScrollback = false)
        repeat(amount) {
            val removed = screen.removeAt(bottom)
            removed.clear(pen)
            screen.add(top, removed)
        }
    }

    private fun push(row: TerminalRow) {
        totalLinesScrolled++
        if (maxScrollback == 0) return
        scrollback.addLast(row.trimmedCopy())
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    /** Returns the images whose placements went, so the caller can decide about their pixels. */
    fun clearScreen(pen: Pen): Set<Int> {
        screen.forEach { it.clear(pen) }
        return removePlacementsReturningImages { it.reachesScreen() }
    }

    private fun removePlacementsReturningImages(predicate: (Placement) -> Boolean): Set<Int> {
        val images = HashSet<Int>()
        placementList.removeAll { placement -> predicate(placement).also { if (it) images += placement.imageId } }
        return images
    }

    /**
     * Resizes the screen.
     *
     * Growing the screen pulls lines back from scrollback rather than inserting blanks, so
     * unfolding the device reveals the output that was pushed off rather than empty space —
     * which is what makes a fold transition feel like the same session.
     */
    fun resize(newColumns: Int, newRows: Int, cursorRow: Int, pen: Pen): Int {
        var adjustedCursorRow = cursorRow

        if (newColumns != columns) {
            screen.forEach { it.resize(newColumns, pen) }
            columns = newColumns
            placementList.removeAll { it.column >= newColumns }
        }

        if (newRows < rows) {
            // Blank trailing lines can be dropped outright; anything with content moves into
            // scrollback instead. Discarding by cursor position lost the lower half of the screen
            // whenever the device was folded with the cursor near the top.
            var toRemove = rows - newRows
            while (toRemove > 0 && screen.size > 1 && screen.last().isBlank() && screen.size - 1 > adjustedCursorRow) {
                val dropped = screen.size - 1
                screen.removeAt(dropped)
                placementList.removeAll { it.row == dropped }
                toRemove--
            }
            while (toRemove > 0 && screen.isNotEmpty()) {
                // The line moves into scrollback, so a placement on it does the same, along with
                // everything already there.
                shiftPlacements(0, screen.size - 1, -1, intoScrollback = maxScrollback > 0)
                push(screen.removeAt(0))
                adjustedCursorRow--
                toRemove--
            }
        } else if (newRows > rows) {
            var toAdd = newRows - rows
            while (toAdd > 0 && scrollback.isNotEmpty()) {
                val restored = scrollback.removeLast()
                // Restored history is widened with the default pen: painting it with whatever
                // colour happens to be active would tint old output on every unfold.
                restored.resize(columns, Pen.Default)
                screen.add(0, restored)
                placementList.forEach { it.row += 1 }
                adjustedCursorRow++
                toAdd--
            }
            repeat(toAdd) { screen.add(TerminalRow(columns)) }
        }

        rows = newRows
        return adjustedCursorRow.coerceIn(0, rows - 1)
    }

    fun snapshotText(fromLine: Int = 0, toLine: Int = rows): String = (fromLine until toLine).mapNotNull { line(it)?.text()?.trimEnd() }.joinToString("\n")

    companion object {
        /**
         * A fixed, generous capacity rather than a user setting. Rows are trimmed on
         * the way in, so this costs a few megabytes at typical line lengths rather than the tens
         * it would at full width.
         */
        const val DEFAULT_SCROLLBACK = 5000

        /** Far more than a screen of images; a list this long is a client misbehaving, not a layout. */
        const val MAX_PLACEMENTS = 2048
    }
}
