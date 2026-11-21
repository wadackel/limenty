package dev.wadackel.limenty.core.input

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog

/**
 * Decides, for each hardware key, whether the in-flight IME composition owns it or the terminal does.
 *
 * Only keys the IME declined to consume reach this class, so the rules here are the fallback
 * arbitration rather than a re-implementation of IME behaviour.
 *
 * The composition owns a short, closed list of keys: Esc aborts it, Enter confirms it, and the
 * editing keys move or trim the preedit. Anything else means the app and the IME already disagree
 * about who is composing, so the preedit is dropped and the key is delivered to the terminal.
 * Swallowing such a key instead would lose a keystroke with nothing on screen to explain it.
 */
class InputArbiter(private val buffer: ImeEditBuffer) {
    private val log = TermLog.logger(LogTags.INPUT)

    fun onKey(
        key: TerminalKey?,
        codePoint: Int = 0,
        modifiers: Modifiers = Modifiers.None,
    ): KeyDecision {
        val decision = decide(key, codePoint, modifiers)
        log.d(
            "key.arbitrated",
            "key" to (key?.name ?: "char"),
            // Only whether a character was involved, not which one: this record reaches the crash
            // report, and the character may be a password typed at a remote prompt.
            "hasChar" to (codePoint > 0),
            "modifiers" to modifiers,
            "composing" to buffer.composing.active,
            "reason" to decision.reason,
            "events" to decision.events.size,
        )
        return decision
    }

    private fun decide(key: TerminalKey?, codePoint: Int, modifiers: Modifiers): KeyDecision {
        if (!buffer.composing.active) {
            return passThrough(key, codePoint, modifiers)
        }

        if (!modifiers.isEmpty) {
            // No conversion step is driven by a modifier chord, and terminal muscle memory expects
            // Ctrl-C to interrupt immediately rather than after confirming the preedit.
            return interrupt(key, codePoint, modifiers)
        }

        return when (key) {
            TerminalKey.Escape -> {
                buffer.discardComposing()
                KeyDecision(emptyList(), handled = true, reason = KeyDecision.Reason.CompositionCancelled)
            }
            TerminalKey.Enter -> {
                val events = buffer.commitComposing()
                KeyDecision(events, handled = true, reason = KeyDecision.Reason.CompositionCommitted)
            }
            TerminalKey.Backspace -> edit(buffer.deleteSurroundingText(1, 0))
            TerminalKey.Delete -> edit(buffer.deleteSurroundingText(0, 1))
            TerminalKey.ArrowLeft -> moveCaret(-1)
            TerminalKey.ArrowRight -> moveCaret(+1)
            TerminalKey.Home -> moveCaretTo(0)
            TerminalKey.End -> moveCaretTo(buffer.composing.text.length)
            else -> interrupt(key, codePoint, modifiers)
        }
    }

    /** Drops the preedit, then delivers the key. Used whenever the composition cannot own it. */
    private fun interrupt(key: TerminalKey?, codePoint: Int, modifiers: Modifiers): KeyDecision {
        buffer.discardComposing()
        val passed = passThrough(key, codePoint, modifiers)
        return passed.copy(reason = KeyDecision.Reason.CompositionInterrupted)
    }

    private fun edit(events: List<TerminalInputEvent>): KeyDecision = KeyDecision(events, handled = true, reason = KeyDecision.Reason.CompositionEdited)

    private fun moveCaret(delta: Int): KeyDecision = moveCaretTo(buffer.composing.cursor + delta)

    private fun moveCaretTo(position: Int): KeyDecision = edit(buffer.setSelection(position, position))

    private fun passThrough(key: TerminalKey?, codePoint: Int, modifiers: Modifiers): KeyDecision {
        val event = when {
            key != null -> TerminalInputEvent.Key(key, modifiers)
            codePoint <= 0 -> return KeyDecision.Unhandled
            modifiers.ctrl || modifiers.alt || modifiers.meta -> TerminalInputEvent.Chord(codePoint, modifiers)
            else -> TerminalInputEvent.Text(String(Character.toChars(codePoint)))
        }
        return KeyDecision(listOf(event), handled = true, reason = KeyDecision.Reason.PassThrough)
    }
}
