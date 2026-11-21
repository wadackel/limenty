package dev.wadackel.limenty.terminal

/**
 * A text selection in buffer coordinates, where line 0 is the top of the screen and negative
 * lines reach into scrollback.
 *
 * Those coordinates move as output arrives, so the range records the buffer's scroll count when
 * it was made and is translated to the current one before use ([shiftedTo]). Without that the
 * highlight stays where it was drawn while the text slides out from under it — a selection made
 * on a busy screen would be copied as whatever happened to land there.
 */
data class SelectionRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    /** [dev.wadackel.limenty.core.terminal.TerminalBuffer.totalLinesScrolled] when this was made. */
    val scrolledAt: Long = 0L,
) {
    /** The same text, addressed as the buffer numbers it now. */
    fun shiftedTo(scrolled: Long): SelectionRange {
        val delta = (scrolledAt - scrolled).toInt()
        if (delta == 0) return this
        return copy(
            startLine = startLine + delta,
            endLine = endLine + delta,
            scrolledAt = scrolled,
        )
    }

    /** The same range with its start before its end, whichever way the handles were dragged. */
    val normalized: SelectionRange
        get() = if (startLine < endLine || (startLine == endLine && startColumn <= endColumn)) {
            this
        } else {
            SelectionRange(endLine, endColumn, startLine, startColumn, scrolledAt)
        }

    fun contains(line: Int, column: Int): Boolean {
        val range = normalized
        return when {
            line < range.startLine || line > range.endLine -> false
            range.startLine == range.endLine -> column in range.startColumn..range.endColumn
            line == range.startLine -> column >= range.startColumn
            line == range.endLine -> column <= range.endColumn
            else -> true
        }
    }

    val isEmpty: Boolean get() = startLine == endLine && startColumn == endColumn
}
