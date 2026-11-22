package dev.wadackel.limenty.core.terminal

/**
 * A terminal colour scheme: the 16 ANSI colours, the 256-colour cube derived from them, plus
 * foreground, background and cursor.
 *
 * Colours are plain ARGB ints so this stays in the pure-JVM module and can be unit-tested; the
 * Android layer converts to `Color` at draw time.
 *
 * A value rather than a mutable settings object: editing a custom theme makes a new palette, so
 * whoever holds the old one can tell by equality that the colours changed.
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

        val CatppuccinMocha = TerminalPalette(
            name = "Catppuccin Mocha",
            foreground = 0xFFCDD6F4.toInt(),
            background = 0xFF1E1E2E.toInt(),
            cursor = 0xFFF5E0DC.toInt(),
            selection = 0xFF353749.toInt(),
            ansi = intArrayOf(
                0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
                0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt(),
                0xFF585B70.toInt(), 0xFFF37799.toInt(), 0xFF89D88B.toInt(), 0xFFEBD391.toInt(),
                0xFF74A8FC.toInt(), 0xFFF2AEDE.toInt(), 0xFF6BD7CA.toInt(), 0xFFBAC2DE.toInt(),
            ),
        )

        val RosePine = TerminalPalette(
            name = "Rosé Pine",
            foreground = 0xFFE0DEF4.toInt(),
            background = 0xFF191724.toInt(),
            cursor = 0xFFE0DEF4.toInt(),
            selection = 0xFF403D52.toInt(),
            ansi = intArrayOf(
                0xFF26233A.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
                0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFE0DEF4.toInt(),
                0xFF6E6A86.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
                0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFE0DEF4.toInt(),
            ),
        )

        val KanagawaWave = TerminalPalette(
            name = "Kanagawa Wave",
            foreground = 0xFFDCD7BA.toInt(),
            background = 0xFF1F1F28.toInt(),
            cursor = 0xFFC8C093.toInt(),
            selection = 0xFF2D4F67.toInt(),
            ansi = intArrayOf(
                0xFF16161D.toInt(), 0xFFC34043.toInt(), 0xFF76946A.toInt(), 0xFFC0A36E.toInt(),
                0xFF7E9CD8.toInt(), 0xFF957FB8.toInt(), 0xFF6A9589.toInt(), 0xFFC8C093.toInt(),
                0xFF727169.toInt(), 0xFFE82424.toInt(), 0xFF98BB6C.toInt(), 0xFFE6C384.toInt(),
                0xFF7FB4CA.toInt(), 0xFF938AA9.toInt(), 0xFF7AA89F.toInt(), 0xFFDCD7BA.toInt(),
            ),
        )

        /**
         * Everforest publishes no terminal port, so the sixteen are the ones its Vim colorscheme
         * hands to Vim's own terminal (`g:terminal_ansi_colors`, dark, medium contrast), which is
         * the scheme's answer to the same question. That mapping repeats the normal colours as
         * the bright ones, and it is kept rather than brightened here.
         */
        val EverforestDark = TerminalPalette(
            name = "Everforest Dark",
            foreground = 0xFFD3C6AA.toInt(),
            background = 0xFF2D353B.toInt(),
            cursor = 0xFFD3C6AA.toInt(),
            selection = 0xFF543A48.toInt(),
            ansi = intArrayOf(
                0xFF475258.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
                0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
                0xFF475258.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
                0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
            ),
        )

        val OneDark = TerminalPalette(
            name = "One Dark",
            foreground = 0xFFABB2BF.toInt(),
            background = 0xFF282C34.toInt(),
            cursor = 0xFFABB2BF.toInt(),
            selection = 0xFF5C6370.toInt(),
            ansi = intArrayOf(
                0xFF2C323C.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFF5C6370.toInt(),
                0xFF3E4452.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
            ),
        )

        /**
         * From the integrated-terminal colours of the scheme's own VS Code theme. Its selection
         * there is translucent, which a palette cannot hold, so the editor's opaque selection
         * stands in for it.
         */
        val NightOwl = TerminalPalette(
            name = "Night Owl",
            foreground = 0xFFD6DEEB.toInt(),
            background = 0xFF011627.toInt(),
            cursor = 0xFF80A4C2.toInt(),
            selection = 0xFF1D3B53.toInt(),
            ansi = intArrayOf(
                0xFF011627.toInt(), 0xFFEF5350.toInt(), 0xFF22DA6E.toInt(), 0xFFC5E478.toInt(),
                0xFF82AAFF.toInt(), 0xFFC792EA.toInt(), 0xFF21C7A8.toInt(), 0xFFFFFFFF.toInt(),
                0xFF575656.toInt(), 0xFFEF5350.toInt(), 0xFF22DA6E.toInt(), 0xFFFFEB95.toInt(),
                0xFF82AAFF.toInt(), 0xFFC792EA.toInt(), 0xFF7FDBCA.toInt(), 0xFFFFFFFF.toInt(),
            ),
        )

        /** The sixteen Iceberg gives Vim's own terminal (`g:terminal_ansi_colors`, dark). */
        val Iceberg = TerminalPalette(
            name = "Iceberg",
            foreground = 0xFFC6C8D1.toInt(),
            background = 0xFF161821.toInt(),
            cursor = 0xFFC6C8D1.toInt(),
            selection = 0xFF272C42.toInt(),
            ansi = intArrayOf(
                0xFF1E2132.toInt(), 0xFFE27878.toInt(), 0xFFB4BE82.toInt(), 0xFFE2A478.toInt(),
                0xFF84A0C6.toInt(), 0xFFA093C7.toInt(), 0xFF89B8C2.toInt(), 0xFFC6C8D1.toInt(),
                0xFF6B7089.toInt(), 0xFFE98989.toInt(), 0xFFC0CA8E.toInt(), 0xFFE9B189.toInt(),
                0xFF91ACD1.toInt(), 0xFFADA0D3.toInt(), 0xFF95C4CE.toInt(), 0xFFD2D4DE.toInt(),
            ),
        )

        /** Alphabetical, ignoring case, so dogrun sits under D and the default is not singled out. */
        val All = listOf(
            TokyoNight, Dracula, Nord, GruvboxDark, SolarizedDark, Dogrun,
            CatppuccinMocha, RosePine, KanagawaWave, EverforestDark, OneDark, NightOwl, Iceberg,
        ).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val Default = TokyoNight
    }
}
