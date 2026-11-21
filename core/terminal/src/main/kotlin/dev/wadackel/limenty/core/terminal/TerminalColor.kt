package dev.wadackel.limenty.core.terminal

/**
 * A cell colour: the terminal default, one of the 256 palette entries, or a direct RGB triple.
 *
 * Packed into an Int because every cell carries two of them; a class per colour would dominate
 * the memory of a scrollback buffer that holds thousands of lines.
 *
 * Palette indices are kept as indices rather than resolved to RGB at parse time so a theme change
 * can repaint existing scrollback instead of only affecting new output.
 */
@JvmInline
value class TerminalColor(val packed: Int) {
    val isDefault: Boolean get() = packed == 0
    val isIndexed: Boolean get() = packed and INDEXED != 0
    val isRgb: Boolean get() = packed and RGB != 0

    val index: Int get() = packed and 0xFF
    val red: Int get() = (packed shr 16) and 0xFF
    val green: Int get() = (packed shr 8) and 0xFF
    val blue: Int get() = packed and 0xFF

    override fun toString(): String = when {
        isDefault -> "default"
        isIndexed -> "idx($index)"
        else -> "rgb($red,$green,$blue)"
    }

    companion object {
        private const val INDEXED = 1 shl 24
        private const val RGB = 1 shl 25

        val Default = TerminalColor(0)

        fun indexed(index: Int): TerminalColor = TerminalColor(INDEXED or (index and 0xFF))

        fun rgb(red: Int, green: Int, blue: Int): TerminalColor = TerminalColor(
            RGB or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF),
        )
    }
}

/** Character rendition flags, as set by SGR. */
object CellFlags {
    const val NONE = 0
    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val REVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKETHROUGH = 1 shl 7
    const val DOUBLE_UNDERLINE = 1 shl 8

    /** The cell holds the left half of a double-width character. */
    const val WIDE_LEADING = 1 shl 9

    /**
     * The cell is the right half of a double-width character and holds no code point of its own.
     * Tracking it explicitly is what lets the cursor and erase operations treat the pair as one
     * unit instead of splitting a CJK character down the middle.
     */
    const val WIDE_TRAILING = 1 shl 10

    fun describe(flags: Int): String = buildList {
        if (flags and BOLD != 0) add("bold")
        if (flags and DIM != 0) add("dim")
        if (flags and ITALIC != 0) add("italic")
        if (flags and UNDERLINE != 0) add("underline")
        if (flags and DOUBLE_UNDERLINE != 0) add("2underline")
        if (flags and BLINK != 0) add("blink")
        if (flags and REVERSE != 0) add("reverse")
        if (flags and INVISIBLE != 0) add("invisible")
        if (flags and STRIKETHROUGH != 0) add("strike")
    }.joinToString("+").ifEmpty { "none" }
}

/**
 * The current graphic rendition — what SGR accumulates and what each printed cell inherits.
 */
data class Pen(
    val foreground: TerminalColor = TerminalColor.Default,
    val background: TerminalColor = TerminalColor.Default,
    val flags: Int = CellFlags.NONE,
) {
    fun with(flag: Int): Pen = copy(flags = flags or flag)

    fun without(flag: Int): Pen = copy(flags = flags and flag.inv())

    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        val Default = Pen()
    }
}
