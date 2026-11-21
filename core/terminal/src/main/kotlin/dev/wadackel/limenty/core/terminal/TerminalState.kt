package dev.wadackel.limenty.core.terminal

/** Cursor position and the shape the remote asked for. */
data class Cursor(
    val row: Int = 0,
    val column: Int = 0,
    val visible: Boolean = true,
)

/**
 * Modes the remote sets that the *rest of the app* has to react to, as opposed to the ones the
 * screen handles internally. Kept in one observable object so the input layer, the renderer and
 * the gesture layer read a single source of truth.
 */
data class TerminalModes(
    val keys: KeyEncodingModes = KeyEncodingModes.Default,
    val bracketedPaste: Boolean = false,
    val mouse: MouseTracking = MouseTracking.None,
    val mouseEncoding: MouseEncoding = MouseEncoding.Default,
    val focusReporting: Boolean = false,
    val alternateScreen: Boolean = false,
    val autoWrap: Boolean = true,
    val insertMode: Boolean = false,
    val originMode: Boolean = false,
    val reverseVideo: Boolean = false,
)

enum class MouseTracking {
    None,

    /** DECSET 9. Press only. */
    X10,

    /** DECSET 1000. Press and release. */
    Normal,

    /** DECSET 1002. Adds motion while a button is held — what tmux uses for pane dragging. */
    ButtonEvent,

    /** DECSET 1003. Every motion event. */
    AnyEvent,
}

enum class MouseEncoding {
    /** The original scheme, which cannot address a column beyond 223. */
    Default,

    /** DECSET 1006. Unambiguous and unbounded; what any modern application negotiates. */
    Sgr,

    /** DECSET 1005. */
    Utf8,
}
