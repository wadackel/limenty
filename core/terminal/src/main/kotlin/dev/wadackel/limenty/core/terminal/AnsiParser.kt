package dev.wadackel.limenty.core.terminal

/**
 * Byte-stream parser for ANSI/VT escape sequences.
 *
 * The state machine follows Paul Williams' VT500 diagram, which is the behaviour xterm implements
 * and therefore the behaviour every remote program was tested against. Following it literally —
 * including the recovery transitions for malformed input — is what stops a truncated sequence
 * from silently eating the output that follows it.
 *
 * UTF-8 decoding happens only in the ground state: escape sequences are ASCII by construction, so
 * decoding inside them would misread a parameter byte as a continuation byte.
 */
class AnsiParser(private val handler: AnsiHandler) {

    private enum class State {
        Ground,
        Escape,
        EscapeIntermediate,
        CsiEntry,
        CsiParam,
        CsiIntermediate,
        CsiIgnore,
        OscString,
        DcsEntry,
        DcsParam,
        DcsIntermediate,
        DcsPassthrough,
        DcsIgnore,
        SosPmString,
        ApcString,
    }

    private var state = State.Ground
    private val utf8 = Utf8Decoder()

    // Held rather than written as `handler::print` at the call site: that expression allocates a
    // function object on every byte, and the ground state is the most frequent path there is.
    private val printSink: (Int) -> Unit = handler::print

    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var currentParam = ABSENT
    private val subParams = mutableListOf<IntArray>()
    private val currentSubParams = mutableListOf<Int>()
    private val intermediates = StringBuilder()
    private var privateMarker: Char? = null
    private val stringBuffer = java.io.ByteArrayOutputStream()
    private var stringTruncated = false
    private var stringEscapePending = false

    fun reset() {
        state = State.Ground
        utf8.reset()
        clearSequence()
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (index in offset until offset + length) {
            feedByte(bytes[index].toInt() and 0xFF)
        }
    }

    private fun feedByte(byte: Int) {
        if (stringEscapePending) {
            stringEscapePending = false
            if (byte == '\\'.code) {
                finishString()
                return
            }
            // Not a terminator after all: the string is abandoned and the ESC starts something new.
            clearSequence()
            state = State.Ground
            feedByte(ESC)
            feedByte(byte)
            return
        }

        // CAN and SUB abort any sequence in flight from every state, and ESC restarts one.
        when (byte) {
            CAN, SUB -> {
                if (state != State.Ground) {
                    state = State.Ground
                    clearSequence()
                    return
                }
            }
            ESC -> {
                if (state.isStringState()) {
                    // Could be the 7-bit ST (`ESC \\`); resetting here would discard the string
                    // that the terminator is about to close, which is how most titles are sent.
                    stringEscapePending = true
                } else {
                    utf8.reset()
                    clearSequence()
                    state = State.Escape
                }
                return
            }
        }

        when (state) {
            State.Ground -> ground(byte)
            State.Escape -> escape(byte)
            State.EscapeIntermediate -> escapeIntermediate(byte)
            State.CsiEntry -> csiEntry(byte)
            State.CsiParam -> csiParam(byte)
            State.CsiIntermediate -> csiIntermediate(byte)
            State.CsiIgnore -> csiIgnore(byte)
            State.OscString -> oscString(byte)
            State.DcsEntry -> dcsEntry(byte)
            State.DcsParam -> dcsParam(byte)
            State.DcsIntermediate -> dcsIntermediate(byte)
            State.DcsPassthrough -> dcsPassthrough(byte)
            State.DcsIgnore -> dcsIgnore(byte)
            State.SosPmString -> sosPmString(byte)
            State.ApcString -> apcString(byte)
        }
    }

    // --- states ---

    private fun ground(byte: Int) {
        if (byte < 0x20 || byte == DEL) {
            // A control byte can arrive in the middle of a multi-byte character; the partial
            // sequence is abandoned rather than allowed to consume the control.
            utf8.reset()
            handler.execute(byte)
            return
        }
        utf8.decode(byte, printSink)
    }

    private fun escape(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte in 0x20..0x2F -> {
                addIntermediate(byte)
                state = State.EscapeIntermediate
            }
            byte == '['.code -> state = State.CsiEntry
            byte == ']'.code -> {
                stringBuffer.reset()
                stringTruncated = false
                state = State.OscString
            }
            byte == 'P'.code -> state = State.DcsEntry
            byte == '_'.code -> {
                stringBuffer.reset()
                stringTruncated = false
                state = State.ApcString
            }
            byte == 'X'.code || byte == '^'.code -> state = State.SosPmString
            byte == DEL -> Unit
            else -> {
                dispatchEsc(byte)
                state = State.Ground
            }
        }
    }

    private fun escapeIntermediate(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte in 0x20..0x2F -> addIntermediate(byte)
            byte == DEL -> Unit
            else -> {
                dispatchEsc(byte)
                state = State.Ground
            }
        }
    }

    private fun csiEntry(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte == DEL -> Unit
            byte in 0x3C..0x3F -> {
                privateMarker = byte.toChar()
                state = State.CsiParam
            }
            byte in 0x30..0x39 || byte == ';'.code || byte == ':'.code -> {
                state = State.CsiParam
                csiParam(byte)
            }
            byte in 0x20..0x2F -> {
                addIntermediate(byte)
                state = State.CsiIntermediate
            }
            byte in 0x40..0x7E -> {
                dispatchCsi(byte)
                state = State.Ground
            }
            else -> state = State.CsiIgnore
        }
    }

    private fun csiParam(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte == DEL -> Unit
            byte in 0x30..0x39 -> {
                val digit = byte - '0'.code
                val base = if (currentParam == ABSENT) 0 else currentParam
                // Clamped rather than allowed to overflow: a hostile or corrupted parameter must
                // not wrap into a negative row count.
                currentParam = if (base > (MAX_PARAM_VALUE - digit) / 10) MAX_PARAM_VALUE else base * 10 + digit
            }
            byte == ':'.code -> {
                addSubParam(currentParam)
                currentParam = ABSENT
            }
            byte == ';'.code -> commitParam()
            byte in 0x20..0x2F -> {
                addIntermediate(byte)
                state = State.CsiIntermediate
            }
            byte in 0x40..0x7E -> {
                dispatchCsi(byte)
                state = State.Ground
            }
            else -> state = State.CsiIgnore
        }
    }

    private fun csiIntermediate(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte == DEL -> Unit
            byte in 0x20..0x2F -> addIntermediate(byte)
            byte in 0x40..0x7E -> {
                dispatchCsi(byte)
                state = State.Ground
            }
            else -> state = State.CsiIgnore
        }
    }

    private fun csiIgnore(byte: Int) {
        when {
            byte < 0x20 -> handler.execute(byte)
            byte in 0x40..0x7E -> {
                clearSequence()
                state = State.Ground
            }
        }
    }

    private fun oscString(byte: Int) {
        when {
            byte == BEL -> {
                dispatchOsc()
                state = State.Ground
            }
            byte < 0x20 -> Unit
            else -> {
                // A remote can emit an unbounded OSC; the buffer is capped so a broken sequence
                // cannot grow without limit, and the truncated result is then discarded.
                if (stringBuffer.size() < MAX_OSC_LENGTH) {
                    stringBuffer.write(byte)
                } else {
                    stringTruncated = true
                }
            }
        }
    }

    private fun apcString(byte: Int) {
        when {
            byte == BEL -> {
                dispatchApc()
                state = State.Ground
            }
            byte < 0x20 -> Unit
            else -> {
                if (stringBuffer.size() < MAX_STRING_LENGTH) {
                    stringBuffer.write(byte)
                } else {
                    stringTruncated = true
                }
            }
        }
    }

    private fun dcsEntry(byte: Int) {
        when {
            byte < 0x20 || byte == DEL -> Unit
            byte in 0x3C..0x3F -> {
                privateMarker = byte.toChar()
                state = State.DcsParam
            }
            byte in 0x30..0x39 || byte == ';'.code || byte == ':'.code -> {
                state = State.DcsParam
                dcsParam(byte)
            }
            byte in 0x20..0x2F -> {
                addIntermediate(byte)
                state = State.DcsIntermediate
            }
            byte in 0x40..0x7E -> {
                stringBuffer.reset()
                state = State.DcsPassthrough
            }
            else -> state = State.DcsIgnore
        }
    }

    private fun dcsParam(byte: Int) {
        when {
            byte < 0x20 || byte == DEL -> Unit
            byte in 0x30..0x39 -> {
                val digit = byte - '0'.code
                val base = if (currentParam == ABSENT) 0 else currentParam
                currentParam = if (base > (MAX_PARAM_VALUE - digit) / 10) MAX_PARAM_VALUE else base * 10 + digit
            }
            byte == ';'.code -> commitParam()
            byte == ':'.code -> currentParam = ABSENT
            byte in 0x20..0x2F -> {
                addIntermediate(byte)
                state = State.DcsIntermediate
            }
            byte in 0x40..0x7E -> {
                commitPendingParam()
                stringBuffer.reset()
                state = State.DcsPassthrough
            }
            else -> state = State.DcsIgnore
        }
    }

    private fun dcsIntermediate(byte: Int) {
        when {
            byte < 0x20 || byte == DEL -> Unit
            byte in 0x20..0x2F -> addIntermediate(byte)
            byte in 0x40..0x7E -> {
                stringBuffer.reset()
                state = State.DcsPassthrough
            }
            else -> state = State.DcsIgnore
        }
    }

    private fun dcsPassthrough(byte: Int) {
        if (stringBuffer.size() < MAX_STRING_LENGTH) stringBuffer.write(byte)
    }

    private fun dcsIgnore(byte: Int) = Unit

    private fun sosPmString(byte: Int) {
        if (byte == BEL) {
            clearSequence()
            state = State.Ground
        }
    }

    /** Closes whichever string state is active when a terminator arrives. */
    private fun finishString() {
        when (state) {
            State.OscString -> dispatchOsc()
            State.ApcString -> dispatchApc()
            State.DcsPassthrough ->
                handler.dcs(
                    collectParams(),
                    intermediates.toString(),
                    String(stringBuffer.toByteArray(), Charsets.UTF_8),
                )
            else -> Unit
        }
        clearSequence()
        state = State.Ground
    }

    private fun State.isStringState(): Boolean = this == State.OscString ||
        this == State.DcsPassthrough ||
        this == State.DcsIgnore ||
        this == State.SosPmString ||
        this == State.ApcString

    // --- dispatch ---

    private fun dispatchEsc(final: Int) {
        // ST terminates a string; reaching it here means there was no string to terminate.
        if (final == '\\'.code && intermediates.isEmpty()) {
            clearSequence()
            return
        }
        handler.esc(intermediates.toString(), final.toChar())
        clearSequence()
    }

    private fun dispatchCsi(final: Int) {
        commitPendingParam()
        handler.csi(collectParams(), subParams.toList(), intermediates.toString(), privateMarker, final.toChar())
        clearSequence()
    }

    private fun dispatchApc() {
        // Unlike an OSC, a truncated APC is reported rather than dropped: a graphics receiver
        // holding half an image needs to hear that the rest is not coming.
        handler.apc(if (stringTruncated) EMPTY_BODY else stringBuffer.toByteArray(), stringTruncated)
        clearSequence()
    }

    private fun dispatchOsc() {
        if (!stringTruncated) {
            val raw = String(stringBuffer.toByteArray(), Charsets.UTF_8)
            val separator = raw.indexOf(';')
            val identifier = if (separator < 0) raw.toIntOrNull() ?: -1 else raw.substring(0, separator).toIntOrNull() ?: -1
            val body = if (separator < 0) "" else raw.substring(separator + 1)
            handler.osc(identifier, body)
        }
        clearSequence()
    }

    /**
     * Commits the parameter under construction at dispatch time.
     *
     * `CSI H` carries no parameters at all, which is not the same as one absent parameter:
     * a handler reading `params[0]` must see nothing there and apply its own default.
     */
    private fun commitPendingParam() {
        if (paramCount == 0 && currentParam == ABSENT && currentSubParams.isEmpty()) return
        commitParam()
    }

    private fun commitParam() {
        if (paramCount < MAX_PARAMS) {
            if (currentSubParams.isEmpty()) {
                params[paramCount] = currentParam
            } else {
                // `38:2:r:g:b` is one parameter with sub-parameters, so the parameter's own value
                // is the first token — reporting the last one would make SGR read the blue
                // channel as the attribute code.
                while (subParams.size < paramCount) subParams.add(IntArray(0))
                addSubParam(currentParam)
                subParams.add(currentSubParams.toIntArray())
                params[paramCount] = currentSubParams[0]
            }
            paramCount++
        }
        currentParam = ABSENT
        currentSubParams.clear()
    }

    /**
     * Adds a sub-parameter, or drops it once the cell is full.
     *
     * Dropped rather than escalated into ignoring the whole sequence: an over-long ordinary
     * parameter list is already dropped at [commitParam], and one parser answering the same
     * question two ways is worse than one that truncates a list nothing sends.
     */
    private fun addSubParam(value: Int) {
        if (currentSubParams.size < MAX_SUB_PARAMS) currentSubParams.add(value)
    }

    /** Same rule as [addSubParam]: past the ceiling the byte is dropped, not the sequence. */
    private fun addIntermediate(byte: Int) {
        if (intermediates.length < MAX_INTERMEDIATES) intermediates.append(byte.toChar())
    }

    private fun collectParams(): IntArray = params.copyOf(paramCount)

    private fun clearSequence() {
        paramCount = 0
        currentParam = ABSENT
        subParams.clear()
        currentSubParams.clear()
        intermediates.setLength(0)
        privateMarker = null
        stringBuffer.reset()
        stringTruncated = false
        stringEscapePending = false
    }

    companion object {
        /** Marks a parameter the remote omitted, which is not the same as writing zero. */
        const val ABSENT = -1

        private const val ESC = 0x1B
        private const val BEL = 0x07
        private const val CAN = 0x18
        private const val SUB = 0x1A
        private const val DEL = 0x7F

        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 65535

        /**
         * Sub-parameters within one parameter. The longest form anything sends is the full
         * `38:2:colourspace:r:g:b:tolerance:tolerance-colourspace`, eight counting the parameter's
         * own value; this is double that. Without a ceiling an unterminated `ESC [` followed by
         * colons retained one entry per byte, for as long as the remote kept sending them.
         */
        private const val MAX_SUB_PARAMS = 16

        /**
         * Intermediate bytes in one sequence. ECMA-48 permits a run of them, but no sequence in
         * use has more than two — `ESC $ ( A` is the only pair, and every CSI and DCS form with
         * an intermediate has exactly one. The private marker is held separately.
         */
        private const val MAX_INTERMEDIATES = 2

        /**
         * Also the ceiling for one graphics chunk: the kitty protocol caps a chunk's payload at
         * 4096 base64 characters, so a well-formed chunk always fits with its control data.
         */
        private const val MAX_STRING_LENGTH = 8192

        /**
         * OSC 52 carries a whole yank as base64, so the 8192 that suits a graphics chunk dropped
         * every copy past about 6 KB of text. Not more than this: the clipboard is set through a
         * binder transaction capped near 1 MB, and the decoded text crosses it as UTF-16, twice
         * its length in bytes.
         */
        private const val MAX_OSC_LENGTH = 512 * 1024

        private val EMPTY_BODY = ByteArray(0)
    }
}
