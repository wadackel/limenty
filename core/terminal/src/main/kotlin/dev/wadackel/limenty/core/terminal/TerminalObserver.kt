package dev.wadackel.limenty.core.terminal

/**
 * Side effects the screen produces that belong to someone else.
 *
 * The emulator never touches the transport or the UI directly: replies to device-status queries
 * go out through [respond], so the terminal stays transport-agnostic and a test can
 * assert on what it would have answered.
 */
interface TerminalObserver {
    /** Bytes the terminal owes the remote, e.g. a cursor position report. */
    fun respond(bytes: ByteArray) {}

    /** The visible content changed and should be redrawn. */
    fun invalidate() {}

    fun titleChanged(title: String) {}

    fun bell() {}

    /** Text the remote asked to have copied (OSC 52), already decoded. */
    fun clipboardWrite(text: String) {}

    fun modesChanged(modes: TerminalModes) {}
}
