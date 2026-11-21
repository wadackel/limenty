package dev.wadackel.limenty.core.terminal

/**
 * Encodes pointer activity as the escape sequences a remote application expects.
 *
 * Only a physical pointer reaches here. Touch is local interaction — scrolling, selection, the
 * keyboard — and forwarding it would make every scroll gesture land inside tmux instead of the
 * scrollback.
 */
object MouseEncoder {
    enum class Button(val code: Int) {
        Left(0),
        Middle(1),
        Right(2),

        /**
         * Motion with nothing held. Reported as button 3, which is what xterm sends for a bare
         * hover; sending it as Left instead starts a drag selection in tmux and nvim the moment
         * the pointer moves.
         */
        None(3),
        WheelUp(64),
        WheelDown(65),
        WheelLeft(66),
        WheelRight(67),
        ;

        val isWheel: Boolean get() = code >= 64
    }

    enum class Action { Press, Release, Motion }

    /**
     * Returns the bytes for one event, or null when the current mode does not report it.
     *
     * [column] and [row] are zero-based cell coordinates; the wire protocol is one-based.
     */
    fun encode(
        modes: TerminalModes,
        button: Button,
        action: Action,
        column: Int,
        row: Int,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ): ByteArray? {
        if (modes.mouse == MouseTracking.None) return null
        if (!reports(modes.mouse, action, button)) return null

        var code = button.code
        if (action == Action.Motion && !button.isWheel) code += MOTION_FLAG
        if (shift) code += SHIFT_FLAG
        if (alt) code += ALT_FLAG
        if (ctrl) code += CTRL_FLAG

        val oneBasedColumn = column + 1
        val oneBasedRow = row + 1

        return when (modes.mouseEncoding) {
            MouseEncoding.Sgr -> {
                val final = if (action == Action.Release && !button.isWheel) 'm' else 'M'
                "\u001B[<$code;$oneBasedColumn;$oneBasedRow$final".toByteArray(Charsets.US_ASCII)
            }
            MouseEncoding.Utf8, MouseEncoding.Default -> {
                // The original scheme adds 32 to every field and so cannot address past column
                // 223; applications that care negotiate SGR, and this exists only for the ones
                // that never learned to.
                if (oneBasedColumn > LEGACY_MAX || oneBasedRow > LEGACY_MAX) return null
                val released = if (action == Action.Release && !button.isWheel) RELEASE_CODE else code
                byteArrayOf(
                    ESC,
                    '['.code.toByte(),
                    'M'.code.toByte(),
                    (released + OFFSET).toByte(),
                    (oneBasedColumn + OFFSET).toByte(),
                    (oneBasedRow + OFFSET).toByte(),
                )
            }
        }
    }

    private fun reports(tracking: MouseTracking, action: Action, button: Button): Boolean = when (tracking) {
        MouseTracking.None -> false
        // X10 reports presses only, and never motion.
        MouseTracking.X10 -> action == Action.Press
        MouseTracking.Normal -> action != Action.Motion
        // 1002 reports motion only while a button is held, so a bare hover is not an event;
        // only 1003 asks for movement with nothing pressed.
        MouseTracking.ButtonEvent -> action != Action.Motion || button != Button.None
        MouseTracking.AnyEvent -> true
    }

    private const val ESC = 0x1B.toByte()
    private const val MOTION_FLAG = 32
    private const val SHIFT_FLAG = 4
    private const val ALT_FLAG = 8
    private const val CTRL_FLAG = 16
    private const val OFFSET = 32
    private const val RELEASE_CODE = 3
    private const val LEGACY_MAX = 223
}
