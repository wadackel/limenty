package dev.wadackel.limenty.core.terminal

/**
 * The subset of DEC private modes that changes how *input* is encoded.
 *
 * These live with the terminal rather than the input layer because the remote application
 * sets them through escape sequences the terminal parses; the input layer only reads them.
 */
data class KeyEncodingModes(
    /** DECCKM. Application mode makes the cursor keys emit SS3 instead of CSI. */
    val applicationCursorKeys: Boolean = false,
    /**
     * When set, Alt prefixes the key with ESC instead of setting the eighth bit. Every terminal
     * this app targets defaults to the ESC prefix, and 8-bit meta breaks UTF-8 input outright.
     */
    val altSendsEscape: Boolean = true,
    /**
     * DECBKM. Off means Backspace sends DEL (0x7f), which is what macOS and Linux ttys expect
     * out of the box; on means it sends BS (0x08).
     */
    val backspaceSendsBackspace: Boolean = false,
    /**
     * XTMODKEYS resource 4. Zero until an application asks for it with `CSI > 4 ; n m`.
     *
     * At level 2 the keys whose legacy encoding throws the modifier away — Enter, Escape, Tab,
     * Space, Backspace — report it instead. The gate matters more than the encoding: a shell that
     * never asked receives `CSI 27 ; 2 ; 13 ~` as line-editor input, and zsh answers it with a
     * bell and a mangled line rather than running the command. Sending it unconditionally would
     * break every prompt to fix one editor.
     */
    val modifyOtherKeys: Int = 0,
) {
    companion object {
        val Default = KeyEncodingModes()
    }
}
