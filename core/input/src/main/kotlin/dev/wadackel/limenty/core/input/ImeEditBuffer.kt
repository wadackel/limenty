package dev.wadackel.limenty.core.input

/**
 * The editable document an Android IME believes it is editing.
 *
 * The buffer deliberately holds *only* the composing region. Committed text is handed to the
 * terminal immediately and forgotten, so the IME can never talk the app into re-sending or
 * deleting something the remote has already consumed — the terminal, not this buffer, owns
 * everything the user has confirmed.
 *
 * Every mutation returns the events to forward to the remote. While a composition is in
 * progress that list is empty, which is the zero-byte preedit guarantee.
 */
class ImeEditBuffer {
    var composing: ComposingState = ComposingState.Inactive
        private set

    private var revision = 0L

    fun reset() {
        if (composing == ComposingState.Inactive) return
        composing = ComposingState.Inactive.copy(revision = ++revision)
    }

    /**
     * InputConnection.setComposingText. Never produces output.
     *
     * An empty result ends the composition rather than leaving an active-but-empty one: a
     * zero-length preedit still claims ownership of Enter, Backspace and Esc, and would silently
     * eat them — Gboard sends `setComposingText("")` when the last preedit character is erased.
     */
    fun setComposingText(text: String, newCursorPosition: Int): List<TerminalInputEvent> {
        composing = if (text.isEmpty()) {
            ComposingState.Inactive.copy(revision = ++revision)
        } else {
            ComposingState(
                text = text,
                cursor = cursorWithin(text, newCursorPosition),
                active = true,
                revision = ++revision,
            )
        }
        return emptyList()
    }

    /** InputConnection.setComposingRegion. There is no surrounding text, so only an empty region is representable. */
    fun setComposingRegion(start: Int, end: Int): List<TerminalInputEvent> {
        val from = minOf(start, end).coerceIn(0, composing.text.length)
        val to = maxOf(start, end).coerceIn(0, composing.text.length)
        val text = composing.text.substring(from, to)
        composing = if (text.isEmpty()) {
            ComposingState.Inactive.copy(revision = ++revision)
        } else {
            ComposingState(text = text, cursor = text.length, active = true, revision = ++revision)
        }
        return emptyList()
    }

    /**
     * InputConnection.commitText. This is the only path that turns IME activity into bytes.
     */
    fun commitText(text: String, @Suppress("UNUSED_PARAMETER") newCursorPosition: Int): List<TerminalInputEvent> {
        composing = ComposingState.Inactive.copy(revision = ++revision)
        return committedTextEvents(text)
    }

    /**
     * Sends the preedit, for a key the user pressed to confirm it.
     *
     * This is the only path that turns an unconfirmed composition into bytes, so it belongs to
     * the arbiter — the IME asking to end a composition is not the user accepting it.
     */
    fun commitComposing(): List<TerminalInputEvent> {
        val pending = composing.text
        reset()
        return committedTextEvents(pending)
    }

    /** Throws the preedit away, for a user who aborted it with Esc or a key it cannot own. */
    fun discardComposing(): List<TerminalInputEvent> {
        reset()
        return emptyList()
    }

    /**
     * InputConnection.finishComposingText, and anywhere else a composition stops being one.
     *
     * The framework contract is that the composing text stays in the document, so text the user
     * has worked on is committed — dropping it eats characters when the IME ends a conversion its
     * own way, as Gboard does on a modifier chord.
     *
     * Gboard parks the unresolved consonant of a romaji sequence as its own composition and ends
     * it milliseconds later. Withholding that consonant loses it from the syllable the next key
     * completes — 「の」 arrived as 「お」, 「入力」 as 「有力」 — so it is kept like any other.
     */
    fun endComposing(): List<TerminalInputEvent> {
        val pending = composing.text
        reset()
        return committedTextEvents(pending)
    }

    /**
     * InputConnection.deleteSurroundingText.
     *
     * Inside a composition this edits the preedit locally. Outside one there is nothing to edit,
     * so the request is translated into the erase keys a terminal understands — that is what the
     * IME actually meant by "delete the character behind the cursor".
     */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): List<TerminalInputEvent> {
        if (beforeLength < 0 || afterLength < 0) return emptyList()

        if (composing.active && composing.text.isNotEmpty()) {
            val cursor = composing.cursor
            val from = (cursor - beforeLength).coerceAtLeast(0)
            val to = (cursor + afterLength).coerceAtMost(composing.text.length)
            val text = composing.text.removeRange(from, to)
            composing = if (text.isEmpty()) {
                ComposingState.Inactive.copy(revision = ++revision)
            } else {
                composing.copy(text = text, cursor = from, revision = ++revision)
            }
            return emptyList()
        }

        return buildList {
            repeat(beforeLength) { add(TerminalInputEvent.Key(TerminalKey.Backspace)) }
            repeat(afterLength) { add(TerminalInputEvent.Key(TerminalKey.Delete)) }
        }
    }

    /**
     * InputConnection.deleteSurroundingTextInCodePoints.
     *
     * The lengths are code points, while the preedit is indexed in UTF-16 units; applying them
     * directly would delete half of a surrogate pair and commit malformed text.
     */
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): List<TerminalInputEvent> {
        if (beforeLength < 0 || afterLength < 0) return emptyList()
        if (!composing.active || composing.text.isEmpty()) {
            // Outside a composition each code point is one erase keystroke regardless of width.
            return deleteSurroundingText(beforeLength, afterLength)
        }

        val text = composing.text
        val cursor = composing.cursor.coerceIn(0, text.length)
        val from = text.offsetByCodePoints(cursor, -minOf(beforeLength, text.codePointCount(0, cursor)))
        val availableAfter = text.codePointCount(cursor, text.length)
        val to = text.offsetByCodePoints(cursor, minOf(afterLength, availableAfter))
        return deleteSurroundingText(cursor - from, to - cursor)
    }

    /** InputConnection.setSelection, restricted to the composing region we expose. */
    fun setSelection(start: Int, end: Int): List<TerminalInputEvent> {
        if (!composing.active) return emptyList()
        val cursor = end.coerceIn(0, composing.text.length)
        composing = composing.copy(cursor = cursor, revision = ++revision)
        return emptyList()
    }

    /** The text the IME may inspect before the cursor: the preedit only. */
    fun textBeforeCursor(length: Int): String {
        if (length <= 0) return ""
        val cursor = composing.cursor.coerceIn(0, composing.text.length)
        return composing.text.substring((cursor - length).coerceAtLeast(0), cursor)
    }

    fun textAfterCursor(length: Int): String {
        if (length <= 0) return ""
        val cursor = composing.cursor.coerceIn(0, composing.text.length)
        return composing.text.substring(cursor, (cursor + length).coerceAtMost(composing.text.length))
    }

    /**
     * Splits committed text on line breaks and turns each break into an Enter key press.
     *
     * A soft keyboard configured for multi-line text delivers Return as `commitText("\n")`, and
     * forwarding that verbatim would put LF on the wire where every shell and readline expects CR.
     * Line breaks inside committed text always mean "the user pressed Return"; paste takes a
     * different path and keeps its newlines intact.
     */
    private fun committedTextEvents(text: String): List<TerminalInputEvent> {
        if (text.isEmpty()) return emptyList()
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return listOf(TerminalInputEvent.Text(text))
        }

        val events = mutableListOf<TerminalInputEvent>()
        val pending = StringBuilder()
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '\r', '\n' -> {
                    if (pending.isNotEmpty()) {
                        events += TerminalInputEvent.Text(pending.toString())
                        pending.clear()
                    }
                    events += TerminalInputEvent.Key(TerminalKey.Enter)
                    index += if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') 2 else 1
                }
                else -> {
                    pending.append(ch)
                    index++
                }
            }
        }
        if (pending.isNotEmpty()) events += TerminalInputEvent.Text(pending.toString())
        return events
    }

    /**
     * InputConnection positions are relative to the inserted text: 1 places the cursor directly
     * after it, 0 directly before it, and larger values reach into surrounding text that a
     * terminal does not have — hence the clamp.
     */
    private fun cursorWithin(text: String, newCursorPosition: Int): Int {
        val raw = if (newCursorPosition > 0) text.length + newCursorPosition - 1 else newCursorPosition
        return raw.coerceIn(0, text.length)
    }
}
