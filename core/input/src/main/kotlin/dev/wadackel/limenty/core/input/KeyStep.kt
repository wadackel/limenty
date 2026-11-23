package dev.wadackel.limenty.core.input

/**
 * One thing a key bar button or a gesture sends.
 *
 * Both send a list of these rather than a single action, because the shortcuts worth a button or a
 * gesture are often two keystrokes: a tmux prefix and the command key after it is one button's
 * worth of intent and two of typing.
 */
sealed interface KeyStep {
    /** A key press: a named key, or a code point, with whatever was held down. */
    data class Press(
        val key: TerminalKey? = null,
        val codePoint: Int = 0,
        val modifiers: Modifiers = Modifiers.None,
    ) : KeyStep

    /** Text, sent as typed. What saves `git status` from being eleven presses. */
    data class Literal(val text: String) : KeyStep

    /**
     * What this step puts on the wire, or null when it is bound to nothing.
     *
     * A code point with a modifier is a chord and without one is text — the same distinction the
     * encoder draws, made here so that what is stored stays the configuration rather than the
     * conclusion drawn from it.
     */
    fun toEvent(): TerminalInputEvent? = when (this) {
        is Literal -> TerminalInputEvent.Text(text).takeIf { text.isNotEmpty() }
        is Press -> when {
            key != null -> TerminalInputEvent.Key(key, modifiers)
            codePoint > 0 && !modifiers.isEmpty -> TerminalInputEvent.Chord(codePoint, modifiers)
            codePoint > 0 -> TerminalInputEvent.Text(String(Character.toChars(codePoint)))
            else -> null
        }
    }
}
