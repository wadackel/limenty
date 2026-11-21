package dev.wadackel.limenty.core.input

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.KeyEncodingModes

/**
 * The single place where input becomes bytes.
 *
 * Routing every path — IME commit, hardware key, auxiliary bar, paste — through one encoder is
 * what makes "nothing reached the remote" a property that can be asserted rather than reasoned
 * about per call site.
 */
class TerminalInputPipeline(
    private val modes: () -> KeyEncodingModes = { KeyEncodingModes.Default },
    private val sink: (ByteArray) -> Unit,
) {
    private val log = TermLog.logger(LogTags.INPUT)

    fun send(events: List<TerminalInputEvent>) {
        if (events.isEmpty()) return
        for (event in events) {
            val bytes = encode(event)
            if (bytes.isEmpty()) continue
            // Logged at debug rather than verbose: this is the record that says what left the
            // app, and it is the only way to tell a preedit leak or a missing keystroke from a
            // remote that ignored it. It carries no content, so keeping it costs nothing.
            log.d("input.send", "event" to describe(event), "bytes" to bytes.size)
            sink(bytes)
        }
    }

    fun send(event: TerminalInputEvent) = send(listOf(event))

    fun encode(event: TerminalInputEvent): ByteArray {
        val currentModes = modes()
        return when (event) {
            is TerminalInputEvent.Text -> TerminalKeyEncoder.encodeText(event.text)
            is TerminalInputEvent.Key -> TerminalKeyEncoder.encode(event.key, event.modifiers, currentModes)
            is TerminalInputEvent.Chord ->
                TerminalKeyEncoder.encodeCodePoint(event.codePoint, event.modifiers, currentModes)
        }
    }

    /** Never includes the text itself: committed input can be a password typed at a remote prompt. */
    private fun describe(event: TerminalInputEvent): String = when (event) {
        is TerminalInputEvent.Text -> "Text(len=${event.text.length})"
        is TerminalInputEvent.Key -> "Key(${event.key},${event.modifiers})"
        is TerminalInputEvent.Chord -> "Chord(U+%04X,%s)".format(event.codePoint, event.modifiers)
    }
}
