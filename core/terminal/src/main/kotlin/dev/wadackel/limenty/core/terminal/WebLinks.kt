package dev.wadackel.limenty.core.terminal

/**
 * Finds `http` and `https` links in what the terminal shows.
 *
 * A link is ASCII only. Japanese prose puts the next word straight after a link with no space
 * between them, and admitting other characters would carry `です` into the address that is opened;
 * the cost is that a link printed with its path already decoded is cut where the decoding starts.
 */
object WebLinks {
    /** Cells from the first character of a link to its last, both inclusive. */
    data class Span(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

    private val schemes = listOf("https://", "http://")

    /**
     * The link covering the cell at [line] and [column], following the rows the terminal wrapped.
     *
     * Called with the terminal lock held, which the output pump also takes, so the walk is bounded:
     * a minified file is one logical line thousands of rows long, and no link needs more than this.
     */
    fun at(buffer: TerminalBuffer, line: Int, column: Int): Span? {
        buffer.line(line) ?: return null

        var first = line
        while (line - first < MAX_ROWS && continues(buffer, first - 1)) first--
        var last = line
        while (last - line < MAX_ROWS && continues(buffer, last) && buffer.line(last + 1) != null) last++

        // Every character a link may hold is one cell wide, so a cell is a character here; any
        // other cell becomes a space, which a link never contains.
        val text = StringBuilder()
        val lines = ArrayList<Int>()
        val columns = ArrayList<Int>()
        var at = -1
        for (current in first..last) {
            val row = buffer.line(current) ?: break
            val images = buffer.placements.filter { current >= it.row && current < it.row + it.rows }
            // Scrollback keeps the width it was written at, and cells past today's screen are cut
            // off when it is drawn; only what is drawn may be opened.
            for (cell in 0 until minOf(row.columns, buffer.columns)) {
                if (current == line && cell == column) at = text.length
                val hidden = images.any { cell >= it.column && cell < it.column + it.columns }
                text.append(if (!hidden && isLinkCell(row, cell)) row.codePointAt(cell).toChar() else ' ')
                lines += current
                columns += cell
            }
        }
        if (at < 0 || text[at] == ' ') return null

        var runStart = at
        while (runStart > 0 && text[runStart - 1] != ' ') runStart--
        var runEnd = at
        while (runEnd + 1 < text.length && text[runEnd + 1] != ' ') runEnd++

        val start = (runStart..at).lastOrNull { index -> schemeAt(text, index) != null } ?: return null
        val end = trimEnd(text, start, runEnd)
        if (end < start + schemeAt(text, start)!!.length || at > end) return null
        return Span(lines[start], columns[start], lines[end], columns[end])
    }

    /**
     * The address [span] holds when it is exactly the link found there, otherwise null.
     *
     * Read from the cells rather than from a copy of the selection: a copy keeps text the screen
     * does not draw, and an address built from it would not be the one the user saw highlighted.
     */
    fun exact(buffer: TerminalBuffer, span: Span): String? {
        if (at(buffer, span.startLine, span.startColumn) != span) return null
        return buildString {
            for (line in span.startLine..span.endLine) {
                val row = buffer.line(line) ?: return null
                val from = if (line == span.startLine) span.startColumn else 0
                val to = if (line == span.endLine) span.endColumn else row.columns - 1
                for (cell in from..to) append(row.codePointAt(cell).toChar())
            }
        }
    }

    /**
     * Whether [line] runs on into the next row. A row wider than the screen does not, whatever it
     * recorded: its tail is drawn off the edge, so joining across it would join across text unseen.
     */
    private fun continues(buffer: TerminalBuffer, line: Int): Boolean {
        val row = buffer.line(line) ?: return false
        return row.wrapped && row.columns <= buffer.columns
    }

    private fun schemeAt(text: CharSequence, index: Int): String? = schemes.firstOrNull {
        text.regionMatches(index, it, 0, it.length, ignoreCase = true)
    }

    /**
     * Drops what closes the sentence rather than the link: a full stop after it, or the bracket
     * of a parenthetical it sits in. A closing bracket the link itself opened stays, which is how
     * a Wikipedia title like `Foo_(bar)` keeps its last character.
     */
    private fun trimEnd(text: CharSequence, start: Int, end: Int): Int {
        // Counted once and kept up to date as the end moves: recounting for each closing bracket
        // let a remote print thousands of `)` after a scheme and stall the press under the lock.
        val depth = IntArray(CLOSERS.length)
        for (index in start..end) {
            val opener = OPENERS.indexOf(text[index])
            val closer = CLOSERS.indexOf(text[index])
            if (opener >= 0) depth[opener]++
            if (closer >= 0) depth[closer]--
        }
        var last = end
        while (last >= start) {
            val closer = CLOSERS.indexOf(text[last])
            val drop = if (closer >= 0) depth[closer] < 0 else text[last] in SENTENCE_PUNCTUATION
            if (!drop) break
            if (closer >= 0) depth[closer]++
            last--
        }
        return last
    }

    /**
     * Whether a cell can be part of a link. A concealed cell (SGR 8) cannot, nor can one an image
     * is drawn over: the screen shows neither the text nor its highlight there, so text hidden in
     * it would be opened unseen.
     */
    private fun isLinkCell(row: TerminalRow, column: Int): Boolean {
        val excluded = CellFlags.WIDE_LEADING or CellFlags.WIDE_TRAILING or CellFlags.INVISIBLE
        if (row.flagsAt(column) and excluded != 0 || row.combiningAt(column) != null) return false
        val codePoint = row.codePointAt(column)
        return codePoint < 0x80 && isLinkChar(codePoint.toChar())
    }

    // A browser reads `\` as `/` in an http address, so admitting it would let
    // `https://evil.example\@good.example` look like it goes to good.example.
    private fun isLinkChar(char: Char): Boolean = char in '!'..'~' && char !in "<>\"`\\"

    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"
    private const val SENTENCE_PUNCTUATION = ".,;:!?'"

    /** How many wrapped rows a link is followed across, in each direction from the press. */
    private const val MAX_ROWS = 64
}
