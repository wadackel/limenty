package dev.wadackel.limenty.core.terminal

/** Records parser output as readable strings so assertions read like the sequences they cover. */
class RecordingAnsiHandler : AnsiHandler {
    val events = mutableListOf<String>()

    val printed: String
        get() = buildString {
            events.filter { it.startsWith("print:") }.forEach { appendCodePoint(it.removePrefix("print:").toInt()) }
        }

    override fun print(codePoint: Int) {
        events += "print:$codePoint"
    }

    override fun execute(control: Int) {
        events += "exec:%02x".format(control)
    }

    override fun csi(
        params: IntArray,
        subParams: List<IntArray>,
        intermediates: String,
        private: Char?,
        final: Char,
    ) {
        val marker = private ?: ""
        val trailing = if (intermediates.isEmpty()) "" else "/$intermediates"
        events += "csi:$marker${params.joinToString(",")}$trailing:$final"
    }

    override fun esc(intermediates: String, final: Char) {
        events += "esc:$intermediates$final"
    }

    override fun osc(identifier: Int, body: String) {
        events += "osc:$identifier:$body"
    }

    override fun dcs(params: IntArray, intermediates: String, body: String) {
        events += "dcs:${params.joinToString(",")}:$body"
    }

    override fun apc(body: ByteArray, truncated: Boolean) {
        events += "apc:${String(body, Charsets.US_ASCII)}" + if (truncated) ":truncated" else ""
    }
}
