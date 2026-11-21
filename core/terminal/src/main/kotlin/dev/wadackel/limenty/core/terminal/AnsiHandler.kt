package dev.wadackel.limenty.core.terminal

/**
 * What [AnsiParser] extracts from the byte stream.
 *
 * The parser deliberately knows nothing about the meaning of a sequence: it recognises shapes and
 * hands over parameters, leaving interpretation to the screen. That split keeps the notoriously
 * fiddly state machine testable on its own, against sequences whose effect on a screen would be
 * far harder to assert than the parse itself.
 */
interface AnsiHandler {
    /** A printable character, already decoded from UTF-8. */
    fun print(codePoint: Int)

    /** A C0 or C1 control character such as CR, LF, BEL or HT. */
    fun execute(control: Int)

    /**
     * `ESC [ params final`, e.g. `ESC [ 1 ; 5 A`.
     *
     * [intermediates] holds the bytes in 0x20..0x2F, [private] the leading `?`, `<`, `=` or `>`.
     * Absent parameters arrive as -1 so a handler can tell `CSI ;5H` from `CSI 1;5H`.
     */
    fun csi(params: IntArray, subParams: List<IntArray>, intermediates: String, private: Char?, final: Char)

    /** `ESC intermediates final`, e.g. `ESC ( B` or `ESC =`. */
    fun esc(intermediates: String, final: Char)

    /** `ESC ] ... BEL` or `ESC ] ... ST`, split at the first `;` into a numeric selector and body. */
    fun osc(identifier: Int, body: String)

    /** `ESC P ... ST`. Device control strings; V1 only needs to consume them harmlessly. */
    fun dcs(params: IntArray, intermediates: String, body: String)

    /**
     * `ESC _ ... ST`. Application program commands, which is how kitty graphics travel.
     *
     * The body stays bytes: it is base64 by the time it reaches the parser, and decoding it into
     * a String would copy every image chunk once for nothing. A body over the parser's cap arrives
     * empty with [truncated] set, so a receiver mid-way through a chunked transfer can abandon it
     * instead of splicing the next chunk onto a hole.
     */
    fun apc(body: ByteArray, truncated: Boolean)
}
