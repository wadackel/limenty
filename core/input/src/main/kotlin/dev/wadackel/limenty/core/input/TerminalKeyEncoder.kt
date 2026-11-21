package dev.wadackel.limenty.core.input

import dev.wadackel.limenty.core.terminal.KeyEncodingModes

/**
 * Turns key presses into the byte sequences a VT-style remote expects.
 *
 * The encoding follows xterm, because that is what `TERM=xterm-256color` promises and what
 * tmux, nvim and readline are configured against on the remote side.
 */
object TerminalKeyEncoder {
    private const val ESC = 0x1B.toByte()
    private const val DEL = 0x7F.toByte()
    private const val BS = 0x08.toByte()
    private const val CR = 0x0D.toByte()
    private const val LF = 0x0A.toByte()
    private const val TAB = 0x09.toByte()

    fun encodeText(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun encodeCodePoint(
        codePoint: Int,
        modifiers: Modifiers = Modifiers.None,
        modes: KeyEncodingModes = KeyEncodingModes.Default,
    ): ByteArray {
        // A physical keyboard has already applied Shift to the character it reports; a key-bar
        // step names the bare letter and holds Shift beside it, which showed `⇧a` and sent `a`.
        val character = if (modifiers.shift) Character.toUpperCase(codePoint) else codePoint
        val base = if (modifiers.ctrl) {
            controlByte(character)?.let { byteArrayOf(it) } ?: utf8(character)
        } else {
            utf8(character)
        }
        return applyAlt(base, modifiers, modes)
    }

    fun encode(
        key: TerminalKey,
        modifiers: Modifiers = Modifiers.None,
        modes: KeyEncodingModes = KeyEncodingModes.Default,
    ): ByteArray = when (key) {
        // Shift and Enter is a newline, not a second way to submit. Applications settled on
        // that convention — CR submits, LF inserts a line — long before any of them thought to
        // ask a terminal about modifiers, and it needs nothing from anyone in between: LF is
        // one byte and arrives as itself through tmux whatever tmux has been told. A shell
        // treats the two identically, so nothing that used to run stops running. Held with
        // anything else, Enter is still Enter.
        TerminalKey.Enter -> reported(CR.toInt(), modifiers, modes)
            ?: applyAlt(byteArrayOf(if (modifiers.shift) LF else CR), modifiers, modes)
        TerminalKey.Escape -> reported(ESC.toInt(), modifiers, modes)
            ?: applyAlt(byteArrayOf(ESC), modifiers, modes)
        TerminalKey.Space -> reported(' '.code, modifiers, modes)
            ?: encodeCodePoint(' '.code, modifiers, modes)
        TerminalKey.Tab ->
            if (modifiers.shift) {
                // Back-tab has its own sequence and keeps it whatever the reporting mode: it
                // is a key in its own right rather than Tab with something held down, and an
                // application that asked for modified keys still expects `CSI Z` for it.
                csi("Z")
            } else {
                reported(TAB.toInt(), modifiers, modes) ?: applyAlt(byteArrayOf(TAB), modifiers, modes)
            }
        TerminalKey.Backspace -> {
            val unmodified = if (modes.backspaceSendsBackspace) BS else DEL
            reported(unmodified.toInt() and 0xFF, modifiers, modes) ?: run {
                // xterm treats Ctrl as a toggle between the two erase characters rather than
                // as a modifier parameter, which is what readline's `stty erase` handling
                // expects. Once an application asks to be told about modifiers, it is told.
                val sendsBackspace = modes.backspaceSendsBackspace xor modifiers.ctrl
                applyAlt(byteArrayOf(if (sendsBackspace) BS else DEL), modifiers, modes)
            }
        }
        TerminalKey.ArrowUp -> cursorKey('A', modifiers, modes)
        TerminalKey.ArrowDown -> cursorKey('B', modifiers, modes)
        TerminalKey.ArrowRight -> cursorKey('C', modifiers, modes)
        TerminalKey.ArrowLeft -> cursorKey('D', modifiers, modes)
        TerminalKey.Home -> cursorKey('H', modifiers, modes)
        TerminalKey.End -> cursorKey('F', modifiers, modes)
        TerminalKey.Insert -> tildeKey(2, modifiers)
        TerminalKey.Delete -> tildeKey(3, modifiers)
        TerminalKey.PageUp -> tildeKey(5, modifiers)
        TerminalKey.PageDown -> tildeKey(6, modifiers)
        TerminalKey.F1 -> functionKeyPQRS('P', modifiers)
        TerminalKey.F2 -> functionKeyPQRS('Q', modifiers)
        TerminalKey.F3 -> functionKeyPQRS('R', modifiers)
        TerminalKey.F4 -> functionKeyPQRS('S', modifiers)
        TerminalKey.F5 -> tildeKey(15, modifiers)
        TerminalKey.F6 -> tildeKey(17, modifiers)
        TerminalKey.F7 -> tildeKey(18, modifiers)
        TerminalKey.F8 -> tildeKey(19, modifiers)
        TerminalKey.F9 -> tildeKey(20, modifiers)
        TerminalKey.F10 -> tildeKey(21, modifiers)
        TerminalKey.F11 -> tildeKey(23, modifiers)
        TerminalKey.F12 -> tildeKey(24, modifiers)
    }

    /**
     * The xterm modifyOtherKeys form, or null when this key should keep its legacy encoding.
     *
     * Null covers three cases and they all matter: the application never asked, so a bare prompt
     * is never handed a sequence its line editor answers with a bell; nothing is held down, so an
     * unmodified key stays the byte every shell already expects; or the level is 1, which asks
     * only about keys with no legacy form at all and so leaves these alone.
     */
    private fun reported(code: Int, modifiers: Modifiers, modes: KeyEncodingModes): ByteArray? {
        if (modes.modifyOtherKeys < 2) return null
        val parameter = modifiers.xtermParameter() ?: return null
        return csi("27;$parameter;$code~")
    }

    /**
     * Maps a character to its C0 control code. Returns null when the pairing has no control
     * meaning, in which case Ctrl is dropped and the character is sent as typed.
     */
    fun controlByte(codePoint: Int): Byte? {
        val ch = codePoint.toChar()
        return when {
            ch in 'a'..'z' -> (codePoint - 'a'.code + 1).toByte()
            ch in 'A'..'Z' -> (codePoint - 'A'.code + 1).toByte()
            ch == ' ' || ch == '@' || ch == '2' -> 0x00
            ch == '[' || ch == '3' -> 0x1B
            ch == '\\' || ch == '4' -> 0x1C
            ch == ']' || ch == '5' -> 0x1D
            ch == '^' || ch == '6' -> 0x1E
            ch == '_' || ch == '7' || ch == '/' -> 0x1F
            ch == '?' || ch == '8' -> 0x7F
            else -> null
        }?.toByte()
    }

    private fun cursorKey(final: Char, modifiers: Modifiers, modes: KeyEncodingModes): ByteArray {
        val parameter = modifiers.xtermParameter()
        return when {
            parameter != null -> csi("1;$parameter$final")
            modes.applicationCursorKeys -> ss3(final)
            else -> csi(final.toString())
        }
    }

    private fun functionKeyPQRS(final: Char, modifiers: Modifiers): ByteArray {
        val parameter = modifiers.xtermParameter()
        return if (parameter != null) csi("1;$parameter$final") else ss3(final)
    }

    private fun tildeKey(number: Int, modifiers: Modifiers): ByteArray {
        val parameter = modifiers.xtermParameter()
        return if (parameter != null) csi("$number;$parameter~") else csi("$number~")
    }

    private fun applyAlt(base: ByteArray, modifiers: Modifiers, modes: KeyEncodingModes): ByteArray = if (modifiers.alt && modes.altSendsEscape) byteArrayOf(ESC) + base else base

    private fun csi(body: String): ByteArray = byteArrayOf(ESC, '['.code.toByte()) + body.toByteArray(Charsets.US_ASCII)

    private fun ss3(final: Char): ByteArray = byteArrayOf(ESC, 'O'.code.toByte(), final.code.toByte())

    private fun utf8(codePoint: Int): ByteArray = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
}
