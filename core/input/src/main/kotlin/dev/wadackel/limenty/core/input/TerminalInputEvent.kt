package dev.wadackel.limenty.core.input

/**
 * What the input layer decided to send to the remote.
 *
 * Keeping this above the byte level is what makes the zero-byte preedit guarantee testable:
 * a composition that produces no event provably produces no bytes, independently of how the
 * encoder happens to be configured.
 */
sealed interface TerminalInputEvent {
    /** Committed text — IME commit, paste, or a printable key. */
    data class Text(val text: String) : TerminalInputEvent

    /** A named key plus its modifiers, encoded against the terminal's current modes. */
    data class Key(val key: TerminalKey, val modifiers: Modifiers = Modifiers.None) : TerminalInputEvent

    /** A printable code point that carries modifiers, e.g. Ctrl+C or Alt+X. */
    data class Chord(val codePoint: Int, val modifiers: Modifiers) : TerminalInputEvent
}
