package dev.wadackel.limenty.core.terminal

/**
 * Text metrics for the terminal grid.
 *
 * Each of these is independently adjustable and applied live, so they are a value
 * that flows into the renderer rather than state the renderer owns.
 */
data class TerminalTypography(
    val fontId: String = DEFAULT_FONT_ID,
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    /** Multiplier on the font's natural line height. */
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    /** Extra advance per cell, in sp. Negative tightens. */
    val letterSpacingSp: Float = DEFAULT_LETTER_SPACING_SP,
) {
    fun withFontSize(sizeSp: Float): TerminalTypography = copy(fontSizeSp = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))

    companion object {
        const val DEFAULT_FONT_ID = "jetbrains-mono"
        const val DEFAULT_FONT_SIZE_SP = 13f
        const val DEFAULT_LINE_HEIGHT = 1.15f
        const val DEFAULT_LETTER_SPACING_SP = 0f

        /** Bounds for pinch zoom: small enough to be useful, large enough to read. */
        const val MIN_FONT_SIZE_SP = 6f
        const val MAX_FONT_SIZE_SP = 32f

        /**
         * Bounds the settings sliders offer.
         *
         * Wide enough to cover what a stored value can already be, so opening the screen never
         * pins a slider to an end and rounds the value away the moment it is touched.
         */
        const val MIN_LINE_HEIGHT = 0.9f
        const val MAX_LINE_HEIGHT = 2.0f
        const val MIN_LETTER_SPACING_SP = -1f
        const val MAX_LETTER_SPACING_SP = 4f
    }
}
