package dev.wadackel.limenty.core.input

/**
 * Encodes clipboard text for the remote.
 *
 * When the remote has enabled bracketed paste the text is wrapped in markers so an editor can
 * tell it from typing — without them, pasting into nvim triggers auto-indent on every line and
 * mangles the content.
 *
 * A paste never becomes Enter key presses: newlines inside pasted text are content, unlike the
 * newline a soft keyboard commits when the user presses Return.
 */
object PasteEncoder {
    const val START = "\u001B[200~"
    const val END = "\u001B[201~"

    fun encode(text: String, bracketed: Boolean): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val body = if (bracketed) START + sanitize(text) + END else sanitize(text)
        return body.toByteArray(Charsets.UTF_8)
    }

    /**
     * Removes any embedded end marker.
     *
     * Pasted text containing the terminator would otherwise close the bracket early and let the
     * remainder be interpreted as typed input — the paste-injection problem bracketed paste exists
     * to prevent. A single `replace` is not enough: removing the marker from `ESC[201` `ESC[201~`
     * `~` joins the pieces around it into a fresh one. Replacing until none is left handles that,
     * but costs a pass per level of nesting, and the remote can put a deeply nested one on the
     * clipboard through OSC 52; checking the tail as each character lands is one pass whatever
     * the nesting.
     */
    private fun sanitize(text: String): String {
        val result = StringBuilder(text.length)
        for (char in text) {
            result.append(char)
            if (result.endsWith(END)) result.setLength(result.length - END.length)
        }
        return result.toString()
    }
}
