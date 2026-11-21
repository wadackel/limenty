package dev.wadackel.limenty.core.terminal

/**
 * A terminal colour scheme: the 16 ANSI colours, the 256-colour cube derived from them, plus
 * foreground, background and cursor.
 *
 * Colours are plain ARGB ints so this stays in the pure-JVM module and can be unit-tested; the
 * Android layer converts to `Color` at draw time.
 *
 * Themes are a preset choice with no per-component editing, so a palette is a value
 * rather than a mutable settings object.
 */
data class TerminalPalette(
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val selection: Int,
    /** The 16 ANSI colours: 0-7 normal, 8-15 bright. */
    val ansi: IntArray,
) {
    init {
        require(ansi.size == 16) { "expected 16 ANSI colours, got ${ansi.size}" }
    }

    /**
     * Resolves a palette index to ARGB.
     *
     * Indices 16-231 are the 6x6x6 cube and 232-255 the greyscale ramp; both are defined by the
     * xterm specification rather than by the theme, so every theme shares them.
     */
    fun resolve(index: Int): Int = when {
        index !in 0 until 256 -> foreground
        index < 16 -> ansi[index]
        index < 232 -> cubeColor(index - 16)
        else -> greyColor(index - 232)
    }

    private fun cubeColor(offset: Int): Int {
        val red = CUBE_LEVELS[(offset / 36) % 6]
        val green = CUBE_LEVELS[(offset / 6) % 6]
        val blue = CUBE_LEVELS[offset % 6]
        return argb(red, green, blue)
    }

    private fun greyColor(step: Int): Int {
        val level = 8 + step * 10
        return argb(level, level, level)
    }

    override fun equals(other: Any?): Boolean = other is TerminalPalette && name == other.name && ansi.contentEquals(other.ansi) &&
        foreground == other.foreground && background == other.background &&
        cursor == other.cursor && selection == other.selection

    override fun hashCode(): Int = 31 * name.hashCode() + ansi.contentHashCode()

    companion object {
        private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

        private fun argb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

        val Dracula = TerminalPalette(
            name = "Dracula",
            foreground = 0xFFF8F8F2.toInt(),
            background = 0xFF282A36.toInt(),
            cursor = 0xFFF8F8F2.toInt(),
            selection = 0xFF44475A.toInt(),
            ansi = intArrayOf(
                0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
                0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt(),
            ),
        )

        val TokyoNight = TerminalPalette(
            name = "Tokyo Night",
            foreground = 0xFFC0CAF5.toInt(),
            background = 0xFF1A1B26.toInt(),
            cursor = 0xFFC0CAF5.toInt(),
            selection = 0xFF33467C.toInt(),
            ansi = intArrayOf(
                0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
                0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
            ),
        )

        val Nord = TerminalPalette(
            name = "Nord",
            foreground = 0xFFD8DEE9.toInt(),
            background = 0xFF2E3440.toInt(),
            cursor = 0xFFD8DEE9.toInt(),
            selection = 0xFF434C5E.toInt(),
            ansi = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt(),
            ),
        )

        val GruvboxDark = TerminalPalette(
            name = "Gruvbox Dark",
            foreground = 0xFFEBDBB2.toInt(),
            background = 0xFF282828.toInt(),
            cursor = 0xFFEBDBB2.toInt(),
            selection = 0xFF504945.toInt(),
            ansi = intArrayOf(
                0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
                0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
                0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
                0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
            ),
        )

        val SolarizedDark = TerminalPalette(
            name = "Solarized Dark",
            foreground = 0xFF839496.toInt(),
            background = 0xFF002B36.toInt(),
            cursor = 0xFF93A1A1.toInt(),
            selection = 0xFF073642.toInt(),
            ansi = intArrayOf(
                0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
            ),
        )

        /**
         * Taken from the scheme's own terminal definition rather than from its Vim highlights.
         *
         * dogrun ships `wezterm/dogrun.toml`, generated from the same source as the colorscheme,
         * which already answers the question a terminal asks and a text editor does not: which of
         * the highlight colours are the sixteen, and in what order. Reading them back out of the
         * `.vim` file would be guessing at that mapping.
         */
        val Dogrun = TerminalPalette(
            name = "dogrun",
            foreground = 0xFF9EA3C0.toInt(),
            background = 0xFF222433.toInt(),
            cursor = 0xFF9EA3C0.toInt(),
            selection = 0xFF363E7F.toInt(),
            ansi = intArrayOf(
                0xFF111219.toInt(), 0xFFE58585.toInt(), 0xFF7CBE8C.toInt(), 0xFF8E8A6F.toInt(),
                0xFF4C89AC.toInt(), 0xFF6C75CB.toInt(), 0xFF73C1A9.toInt(), 0xFF9EA3C0.toInt(),
                0xFF545C8C.toInt(), 0xFFB871B8.toInt(), 0xFF7CBE8C.toInt(), 0xFFA8A384.toInt(),
                0xFF589EC6.toInt(), 0xFF929BE5.toInt(), 0xFF59B6B6.toInt(), 0xFF9EA3C0.toInt(),
            ),
        )

        val All = listOf(TokyoNight, Dracula, Nord, GruvboxDark, SolarizedDark, Dogrun)

        val Default = TokyoNight
    }
}
