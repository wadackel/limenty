package dev.wadackel.limenty.core.terminal

/**
 * A selectable terminal font.
 *
 * Nerd Font glyphs come from one shared symbols face rather than from patched per-font builds:
 * the patched builds repeat the same 10,000 glyphs in every font, which would multiply the app
 * size by the number of choices offered.
 */
data class TerminalFont(
    val id: String,
    val displayName: String,
    val regularAsset: String,
    val boldAsset: String,
) {
    companion object {
        /** Shared fallback carrying Powerline, devicons and the rest of the Nerd Font ranges. */
        const val SYMBOLS_ASSET = "fonts/symbols_nerd_font_mono.ttf"

        val JetBrainsMono = TerminalFont(
            id = "jetbrains-mono",
            displayName = "JetBrains Mono",
            regularAsset = "fonts/jetbrains_mono_regular.ttf",
            boldAsset = "fonts/jetbrains_mono_bold.ttf",
        )

        val Hack = TerminalFont(
            id = "hack",
            displayName = "Hack",
            regularAsset = "fonts/hack_regular.ttf",
            boldAsset = "fonts/hack_bold.ttf",
        )

        val FiraCode = TerminalFont(
            id = "fira-code",
            displayName = "Fira Code",
            regularAsset = "fonts/fira_code_regular.ttf",
            boldAsset = "fonts/fira_code_bold.ttf",
        )

        val All = listOf(JetBrainsMono, Hack, FiraCode)

        val Default = JetBrainsMono

        fun byId(id: String): TerminalFont = All.firstOrNull { it.id == id } ?: Default
    }
}
