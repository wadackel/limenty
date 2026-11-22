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

        val SourceCodePro = TerminalFont(
            id = "source-code-pro",
            displayName = "Source Code Pro",
            regularAsset = "fonts/source_code_pro_regular.ttf",
            boldAsset = "fonts/source_code_pro_bold.ttf",
        )

        val IbmPlexMono = TerminalFont(
            id = "ibm-plex-mono",
            displayName = "IBM Plex Mono",
            regularAsset = "fonts/ibm_plex_mono_regular.ttf",
            boldAsset = "fonts/ibm_plex_mono_bold.ttf",
        )

        /** Mono rather than Code: the two differ only by ligatures, which the cell renderer never shapes. */
        val CascadiaMono = TerminalFont(
            id = "cascadia-mono",
            displayName = "Cascadia Mono",
            regularAsset = "fonts/cascadia_mono_regular.ttf",
            boldAsset = "fonts/cascadia_mono_bold.ttf",
        )

        val Inconsolata = TerminalFont(
            id = "inconsolata",
            displayName = "Inconsolata",
            regularAsset = "fonts/inconsolata_regular.ttf",
            boldAsset = "fonts/inconsolata_bold.ttf",
        )

        /** Alphabetical, ignoring case, so the default is not singled out. */
        val All = listOf(JetBrainsMono, Hack, FiraCode, SourceCodePro, IbmPlexMono, CascadiaMono, Inconsolata)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

        val Default = JetBrainsMono

        fun byId(id: String): TerminalFont = All.firstOrNull { it.id == id } ?: Default
    }
}
