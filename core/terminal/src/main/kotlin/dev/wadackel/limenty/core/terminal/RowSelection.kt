package dev.wadackel.limenty.core.terminal

/**
 * Resolves cell ranges on a row to the text they hold.
 *
 * A selection is made in cells, because a cell is what a finger lands on, but a copy is a string,
 * and the two do not index the same way: a double-width character is two cells and one code unit,
 * a combining mark is no cells and one more code unit. Applying a column number to the row's
 * string therefore addresses a different span than the one on screen — `日本語 abc` with cells 0
 * through 5 highlighted copied `日本語 ab`. Resolving the range against the cells before anything
 * is stringified is what keeps the highlight and the clipboard describing the same characters.
 */
object RowSelection {
    /**
     * The text held by cells [from] through [to], both inclusive.
     *
     * A range that starts or ends on half of a double-width character takes the other half with
     * it: the two halves are one character, and returning either alone loses it entirely — the
     * trailing half carries no code point of its own.
     */
    fun textIn(row: TerminalRow, from: Int, to: Int, trimTrailingBlanks: Boolean = false): String {
        if (row.columns == 0 || from >= row.columns || to < 0) return ""
        val start = leadingHalf(row, from.coerceAtLeast(0))
        val last = trailingHalf(row, to.coerceAtMost(row.columns - 1))
        if (start > last) return ""
        var end = last + 1
        if (trimTrailingBlanks) {
            while (end > start && isBlank(row, end - 1)) end--
        }
        return row.text(start, end)
    }

    /** The run of non-blank cells around [column], or null when that cell is blank itself. */
    fun wordAt(row: TerminalRow, column: Int): IntRange? {
        if (column < 0 || column >= row.columns) return null
        val at = leadingHalf(row, column)
        if (isBlank(row, at)) return null
        var start = at
        while (start > 0 && !isBlank(row, start - 1)) start--
        var end = trailingHalf(row, at)
        while (end + 1 < row.columns && !isBlank(row, end + 1)) end = trailingHalf(row, end + 1)
        return start..end
    }

    private fun leadingHalf(row: TerminalRow, column: Int): Int {
        val trailing = row.flagsAt(column) and CellFlags.WIDE_TRAILING != 0
        return if (column > 0 && trailing) column - 1 else column
    }

    private fun trailingHalf(row: TerminalRow, column: Int): Int {
        val leading = row.flagsAt(column) and CellFlags.WIDE_LEADING != 0
        return if (column + 1 < row.columns && leading) column + 1 else column
    }

    /**
     * Whether the cell holds nothing the user selected.
     *
     * Asked of the leading half, because the right half of a wide character stores a space of its
     * own and answering from that would call half of every wide character blank. It also has to be
     * asked of the leading half rather than answered as "never blank": U+3000 is a wide space, and
     * treating its right half as content put a full-width space at the front of every word
     * selected after one. A space carrying combining marks is a character in its own right.
     */
    private fun isBlank(row: TerminalRow, column: Int): Boolean {
        val cell = leadingHalf(row, column)
        if (row.combiningAt(cell) != null) return false
        return Character.isWhitespace(row.codePointAt(cell))
    }
}
