package dev.wadackel.limenty.core.terminal

/**
 * Incremental UTF-8 decoder.
 *
 * A terminal receives bytes in whatever chunks the transport delivers, so a multi-byte character
 * routinely straddles two writes; the decoder therefore holds partial state across calls rather
 * than decoding a buffer at a time.
 *
 * Malformed input yields U+FFFD instead of throwing, following the WHATWG substitution rules: a
 * truncated sequence produces exactly one replacement and the offending byte is then reprocessed
 * from scratch, so one bad byte can neither kill the session nor swallow the character after it.
 */
class Utf8Decoder {
    private var pending = 0
    private var remaining = 0
    private var minimum = 0

    fun reset() {
        pending = 0
        remaining = 0
        minimum = 0
    }

    fun decode(byte: Int, onCodePoint: (Int) -> Unit) {
        val value = byte and 0xFF

        if (remaining > 0) {
            if (value and 0xC0 == 0x80) {
                pending = (pending shl 6) or (value and 0x3F)
                remaining--
                if (remaining == 0) {
                    val codePoint = pending
                    val floor = minimum
                    reset()
                    onCodePoint(sanitize(codePoint, floor))
                }
                return
            }
            reset()
            onCodePoint(REPLACEMENT)
            // Fall through so the byte is interpreted as the start of something new.
        }

        when {
            value < 0x80 -> onCodePoint(value)
            value in 0xC2..0xDF -> start(value and 0x1F, 1, 0x80)
            value in 0xE0..0xEF -> start(value and 0x0F, 2, 0x800)
            value in 0xF0..0xF4 -> start(value and 0x07, 3, 0x10000)
            // 0xC0/0xC1 can only ever begin an overlong form, and 0x80..0xBF is a continuation
            // with nothing to continue.
            else -> onCodePoint(REPLACEMENT)
        }
    }

    private fun sanitize(codePoint: Int, floor: Int): Int = when {
        codePoint < floor -> REPLACEMENT
        codePoint in 0xD800..0xDFFF -> REPLACEMENT
        codePoint > 0x10FFFF -> REPLACEMENT
        else -> codePoint
    }

    private fun start(seed: Int, continuations: Int, floor: Int) {
        pending = seed
        remaining = continuations
        minimum = floor
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
