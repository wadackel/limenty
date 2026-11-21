package dev.wadackel.limenty.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * How the terminal advertises itself to the IME.
 *
 * This is a knob rather than a constant because the right answer is empirical: `TYPE_NULL` gives
 * the cleanest hardware-key behaviour but disables Japanese conversion outright, while a text
 * input type enables conversion at the cost of autocorrect interference. The default is
 * picked by measuring real Gboard behaviour through the debug harness.
 */
data class EditorConfiguration(
    val name: String,
    val inputType: Int,
    val imeOptions: Int = COMMON_IME_OPTIONS,
) {
    /**
     * The IME options, with learning suppressed unless the user has asked otherwise.
     *
     * Gboard reads [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] as "this field is private" and,
     * beyond not learning from it, keeps anything copied there out of its clipboard history. That
     * is the right default for a terminal — commands and whatever is typed at a password prompt
     * both pass through here — but it costs the clipboard sharing some users want more.
     */
    fun imeOptionsWith(personalizedLearning: Boolean): Int = if (personalizedLearning) {
        imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
    } else {
        imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    companion object {
        const val COMMON_IME_OPTIONS: Int =
            EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        /** Conversion enabled, autocorrect and suggestions suppressed. */
        val Composing = EditorConfiguration(
            name = "composing",
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )

        /** Conversion enabled with suggestions left on, for comparison against [Composing]. */
        val ComposingWithSuggestions = EditorConfiguration(
            name = "composing+suggestions",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )

        /** No IME editing at all; every key arrives as a raw event. */
        val RawKeys = EditorConfiguration(name = "raw-keys", inputType = InputType.TYPE_NULL)

        val All = listOf(Composing, ComposingWithSuggestions, RawKeys)

        val Default = Composing
    }
}
