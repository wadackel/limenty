package dev.wadackel.limenty.core.input

/**
 * The outcome of arbitrating one hardware key press.
 *
 * [reason] exists so the decision shows up in logs verbatim: an input bug reported from a real
 * device is almost always a disagreement about who owned a key, and that is invisible from the
 * resulting bytes alone.
 */
data class KeyDecision(
    val events: List<TerminalInputEvent>,
    val handled: Boolean,
    val reason: Reason,
) {
    enum class Reason {
        /** No composition was in progress; the key went straight to the terminal. */
        PassThrough,

        /** Esc aborted an in-flight composition; nothing was sent. */
        CompositionCancelled,

        /** Enter confirmed an in-flight composition; only the confirmed text was sent. */
        CompositionCommitted,

        /** The key edited the preedit locally; nothing was sent. */
        CompositionEdited,

        /** The composition could not own the key, so it was dropped and the key delivered. */
        CompositionInterrupted,

        /** The key carries no terminal meaning; the framework keeps its default behaviour. */
        Ignored,
    }

    companion object {
        val Unhandled = KeyDecision(emptyList(), handled = false, reason = Reason.Ignored)
    }
}
