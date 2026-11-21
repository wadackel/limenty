package dev.wadackel.limenty.input

import android.view.KeyEvent

/**
 * Describes a keystroke for the log without recording what was typed.
 *
 * A key may be a character of a password being typed at a remote prompt, and every sink here
 * outlives the moment: the in-app ring buffer is what a report is built from, and logcat is
 * retained by the OS and collected into a bugreport. So a text-carrying key is always reduced to
 * its shape, never its value.
 *
 * Redaction applies only to keys that carry text. Escape, the arrows, the function row and the
 * modifiers are exactly what a report needs to explain "my key did not arrive", and none of them
 * can spell a password — so they are logged by name.
 */
object KeystrokeDescription {
    fun codePoint(codePoint: Int): String = if (codePoint <= 0) "-" else shape(codePoint)

    fun keyCode(keyCode: Int, hasCharacter: Boolean): String = if (hasCharacter) "text" else keyCode.toString()

    /**
     * The scripts a composing run is made of, for diagnosing which compositions an IME abandons.
     *
     * Coarser than the text and finer than a length: enough to tell an unresolved romaji letter
     * from a kana or a punctuation mark, which is the difference between an IME tidying up and a
     * character the user meant to type. It cannot spell anything back.
     */
    fun script(text: String): String {
        if (text.isEmpty()) return "-"
        return text.codePoints().toArray().map(::scriptOf).distinct().sorted().joinToString("+")
    }

    private fun scriptOf(codePoint: Int): String = when (codePoint) {
        in 0x3040..0x309F -> "hiragana"
        in 0x30A0..0x30FF -> "katakana"
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> "kanji"
        in 0x3000..0x303F, in 0xFF01..0xFF20, in 0xFF3B..0xFF40, in 0xFF5B..0xFF65 -> "punct"
        in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> "romaji"
        else -> if (Character.isLetterOrDigit(codePoint)) "latin" else "other"
    }

    /** True when the key produces text, and so could be part of something secret. */
    fun carriesText(keyCode: Int, codePoint: Int): Boolean = codePoint > 0 ||
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
        keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    /** Enough to tell a letter from punctuation when reconstructing a sequence, and no more. */
    private fun shape(codePoint: Int): String = when {
        Character.isLetterOrDigit(codePoint) -> "alnum"
        Character.isWhitespace(codePoint) -> "space"
        else -> "other"
    }
}
