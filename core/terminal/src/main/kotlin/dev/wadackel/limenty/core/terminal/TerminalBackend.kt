package dev.wadackel.limenty.core.terminal

/**
 * The byte-stream boundary between the terminal and whatever carries its traffic.
 *
 * Everything above this interface is transport-agnostic: the terminal knows about
 * bytes and a window size, never about SSH or the network.
 */
interface TerminalBackend {
    fun write(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    fun close()
}

/**
 * Collects everything written to it. Used by the debug harness and by tests that need to assert
 * on exactly which bytes an input path produced — notably the zero-byte preedit guarantee.
 */
class RecordingTerminalBackend : TerminalBackend {
    private val lock = Any()
    private val buffer = java.io.ByteArrayOutputStream()

    @Volatile
    var lastResize: Pair<Int, Int>? = null
        private set

    @Volatile
    var closed: Boolean = false
        private set

    override fun write(bytes: ByteArray) {
        synchronized(lock) { buffer.write(bytes) }
    }

    override fun resize(cols: Int, rows: Int) {
        lastResize = cols to rows
    }

    override fun close() {
        closed = true
    }

    fun bytes(): ByteArray = synchronized(lock) { buffer.toByteArray() }

    fun size(): Int = synchronized(lock) { buffer.size() }

    fun text(): String = String(bytes(), Charsets.UTF_8)

    fun clear() {
        synchronized(lock) { buffer.reset() }
    }
}
