package dev.wadackel.limenty.debugharness

import dev.wadackel.limenty.core.input.ComposingState
import dev.wadackel.limenty.core.terminal.RecordingTerminalBackend

/**
 * Live evidence that a composition sends nothing to the remote.
 *
 * [bytesWrittenWhileComposing] is the number that decides it: it must stay at zero through
 * an entire Japanese conversion, and the counter is kept here rather than derived afterwards so a
 * single leaked byte cannot be missed by scrolling past it.
 */
class InputTraceState {
    val backend = RecordingTerminalBackend()

    var bytesWrittenWhileComposing: Int = 0
        private set

    var lastCommittedByteCount: Int = 0
        private set

    var composing: ComposingState = ComposingState.Inactive

    fun onBytes(bytes: ByteArray, composingActive: Boolean) {
        backend.write(bytes)
        if (composingActive) {
            bytesWrittenWhileComposing += bytes.size
        } else {
            lastCommittedByteCount = bytes.size
        }
    }

    fun clear() {
        backend.clear()
        bytesWrittenWhileComposing = 0
        lastCommittedByteCount = 0
    }

    fun hexDump(limit: Int = 512): String {
        val all = backend.bytes()
        val slice = if (all.size <= limit) all else all.copyOfRange(all.size - limit, all.size)
        return slice.joinToString(" ") { "%02x".format(it) }
    }

    fun decodedText(): String = backend.text()
}
