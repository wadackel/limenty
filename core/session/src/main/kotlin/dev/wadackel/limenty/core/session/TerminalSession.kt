package dev.wadackel.limenty.core.session

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.TerminalEmulator
import dev.wadackel.limenty.core.terminal.TerminalModes
import dev.wadackel.limenty.core.terminal.TerminalObserver
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicLong

/**
 * One SSH connection joined to one terminal screen.
 *
 * The session outlives any screen showing it: navigating away or rotating the device
 * touches the UI, not this object, which is why the connection survives both.
 *
 * Threading: the emulator is mutated off the main thread so a burst of remote output cannot stall
 * the UI, and the renderer reads it under [lock]. Holding a lock rather than copying
 * the screen each frame keeps a 200-column buffer from being duplicated 60 times a second.
 */
class TerminalSession(
    val id: SessionId,
    val hostId: String,
    val hostName: String,
    private val transport: TerminalTransport,
    columns: Int,
    rows: Int,
    parentScope: CoroutineScope? = null,
) {
    private val log = TermLog.logger(LogTags.SESSION)

    /**
     * The session's own coroutines, as a child of whatever scope created it.
     *
     * A child rather than the parent itself, so [destroy] can end everything this session started
     * without touching the manager's other sessions. Running on the parent left a handshake that
     * outlived the session it belonged to: closing while one was queued removed the session from
     * the list and then connected its transport anyway.
     */
    private val scope = parentScope
        ?.let { parent -> CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])) }
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * What the user typed, bound for the remote in the order it was handed over.
     *
     * A coroutine per write did not keep that order. The scope runs on a thread pool, so two writes
     * handed over one after the other became two coroutines racing for the socket, and the one that
     * got there first was whichever thread woke up first. A single keystroke never showed it; a key
     * that sends `C-s` and then `w` did, by arriving reversed often enough to notice — a tmux
     * prefix swallowed and a stray letter left at the shell.
     *
     * Unbounded on purpose: only the user can add to it, and dropping a keystroke because a write
     * is slow would be a worse failure than holding a few bytes.
     */
    private val input = Channel<ByteArray>(Channel.UNLIMITED)

    private val droppedReplies = AtomicLong()

    /**
     * Raised by [destroy] before the channels close. Cancelling the writer can hand an element it
     * had already taken back through `onUndeliveredElement`, and counting that would warn on every
     * ordinary disconnect. The window is too narrow to pin with a deterministic test.
     */
    @Volatile
    private var closing = false

    private fun countDroppedReply() {
        if (closing) return
        // An eviction runs on the pump thread under the terminal lock: a counter and a rare log
        // line, nothing more.
        val count = droppedReplies.incrementAndGet()
        if (count == 1L || count % DROPPED_REPLY_LOG_INTERVAL == 0L) log.w("reply.dropped", "count" to count)
    }

    /**
     * What the terminal owes the remote — cursor reports, device attributes, graphics
     * acknowledgements — kept apart from [input] because the remote can provoke as many of these
     * as it likes. They only pile up while the remote is not reading, so a bound costs a working
     * remote nothing, and a stuck one was never going to receive them anyway. The oldest go first,
     * which keeps the reply to whatever was asked most recently.
     */
    private val replies = Channel<ByteArray>(
        capacity = REPLY_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { countDroppedReply() },
    )

    /**
     * Drains [input] ahead of [replies]: whatever the user typed leaves before a backlog of replies
     * to a remote that stopped reading. The cost is a reordering the single queue never had — a
     * reply queued before a key's first byte can go out between that byte and the key's next one,
     * when one key action arrives as several `send` calls and the writer empties [input] between
     * two of them. It needs a reply to be waiting at that instant, which ordinary interactive use
     * does not produce.
     */
    private val writerJob = scope.launch {
        while (true) {
            input.tryReceive().getOrNull()?.let { bytes ->
                transport.write(bytes)
                continue
            }
            val bytes = select<ByteArray?> {
                input.onReceiveCatching { it.getOrNull() }
                replies.onReceiveCatching { it.getOrNull() }
            } ?: break
            transport.write(bytes)
        }
    }

    /** Guards every read and write of [emulator]. */
    val lock = Any()

    private val observer = object : TerminalObserver {
        override fun respond(bytes: ByteArray) {
            replies.trySend(bytes)
        }

        override fun invalidate() {
            _revision.value = _revision.value + 1
        }

        override fun titleChanged(title: String) {
            _title.value = title
        }

        override fun modesChanged(modes: TerminalModes) {
            // Entering or leaving the alternate screen swaps the whole buffer, and with it the
            // line counter the viewport is anchored against. There is no correspondence between
            // the two histories, so the only honest position is the live screen.
            if (modes.alternateScreen != _modes.value.alternateScreen) scrollToBottom()
            _modes.value = modes
        }

        override fun bell() {
            _bellCount.value = _bellCount.value + 1
        }

        override fun clipboardWrite(text: String) {
            _clipboardWrites.tryEmit(text)
        }
    }

    val emulator = TerminalEmulator(columns, rows, observer)

    val state: StateFlow<ConnectionState> get() = transport.state

    private val _revision = MutableStateFlow(0L)

    /** Increments whenever the screen changes, so a renderer can recompose without diffing. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _clipboardWrites = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Text the remote asked to put on the clipboard. Nothing is replayed: a write that arrived
     * while no screen was collecting is dropped, not applied later over whatever the user copied
     * in the meantime.
     */
    val clipboardWrites: SharedFlow<String> = _clipboardWrites.asSharedFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /**
     * How long the connection has been up, or null if there is none.
     *
     * Taken from the monotonic clock: wall time would be wrong to subtract across a clock
     * adjustment, and this is only ever read as a duration.
     */
    fun connectedFor(): java.time.Duration? = connectedAtNanos?.let { java.time.Duration.ofNanos(System.nanoTime() - it) }

    @Volatile
    private var connectedAtNanos: Long? = null

    /**
     * The last [limit] lines with anything on them, for a preview elsewhere in the app.
     *
     * Walks up from the bottom rather than rendering the screen and discarding most of it — this
     * is called while someone is looking at a list, and a full screen is 122 columns by 29 rows.
     */
    fun visibleLines(limit: Int): List<String> = synchronized(lock) {
        val lines = ArrayDeque<String>()
        var row = emulator.rows - 1
        while (row >= 0 && lines.size < limit) {
            val text = emulator.buffer.line(row)?.text()?.trimEnd()
            if (text != null && (text.isNotEmpty() || lines.isNotEmpty())) lines.addFirst(text)
            row--
        }
        lines.toList()
    }

    private val _modes = MutableStateFlow(TerminalModes())
    val modes: StateFlow<TerminalModes> = _modes.asStateFlow()

    private val _bellCount = MutableStateFlow(0)
    val bellCount: StateFlow<Int> = _bellCount.asStateFlow()

    private val _scrollOffset = MutableStateFlow(0)

    /** Lines above the live screen the viewport is showing; 0 means following the output. */
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()

    private var pumpJob: Job? = null
    private var pendingResize: Job? = null

    /** Whether the view has measured a cell yet, which is what makes a post-connect window-change worth sending. */
    @Volatile
    private var cellMeasured = false

    /**
     * Runs the handshake on the session's own scope.
     *
     * Not on the caller's: one started from a composition dies when that composition does — the
     * activity being destroyed while the app is backgrounded — leaving a session that is neither
     * connected nor cleaned up. The connection outlives every screen, and no more than that.
     */
    fun launchConnect(config: ConnectionConfig, onFinished: suspend () -> Unit = {}): Job = scope.launch {
        try {
            connect(config)
        } catch (cancelled: CancellationException) {
            // runCatching would fold this into a Result, and the session it belongs to is already
            // closed by the time it arrives: the completion below would run for a connection
            // nobody is waiting on, and be logged as a handshake that merely failed.
            throw cancelled
        } catch (error: Exception) {
            log.w(error, "session.connectFailed", "session" to id.value)
        }
        onFinished()
    }

    suspend fun connect(config: ConnectionConfig) {
        connectedAtNanos = null
        startPump()
        transport.connect(config)

        // The view measures the real viewport while the handshake is still running, and a resize
        // sent before the shell exists is dropped by the transport. Without this the remote keeps
        // the size the connection was opened with until something else happens to resize.
        if (transport.state.value !is ConnectionState.Connected) return

        val (columns, rows, cell) = synchronized(lock) { Triple(emulator.columns, emulator.rows, emulator.cellPixelSize) }
        connectedAtNanos = System.nanoTime()
        // The pty was requested before the view had measured a cell, so it opened with no pixel
        // size; a measured cell is worth one window-change even when the grid did not change.
        if (columns != config.initialColumns || rows != config.initialRows || cellMeasured) {
            log.i("resize.afterConnect", "cols" to columns, "rows" to rows, "cellWidthPx" to cell.width, "cellHeightPx" to cell.height)
            transport.resize(columns, rows, columns * cell.width, rows * cell.height)
        }
    }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            transport.output.collect { chunk ->
                // The offset counts lines above the live screen, so every line the output pushes
                // into scrollback makes the same offset point one line further down. Holding the
                // viewport still means growing the offset by exactly what was pushed — otherwise
                // the build log the user is reading scrolls away underneath them.
                val scrolled = synchronized(lock) {
                    val before = emulator.buffer.totalLinesScrolled
                    emulator.write(chunk)
                    emulator.buffer.totalLinesScrolled - before
                }
                // Clamped on every write, not only when lines were pushed: `CSI 3J` drops the
                // scrollback outright, and an offset left pointing past the end renders as a
                // blank screen the user cannot escape without touching something.
                _scrollOffset.update { current ->
                    if (current == 0) 0 else (current + scrolled.toInt()).coerceAtMost(maxScrollOffset())
                }
            }
        }
    }

    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Typing returns the viewport to the live screen, which is what every terminal does and
        // what stops input from appearing to vanish while scrolled back.
        _scrollOffset.value = 0
        input.trySend(bytes)
    }

    /**
     * Applies a new viewport size.
     *
     * The screen is resized at once so rendering matches the pixels, but the remote is told only
     * after the size settles. A soft keyboard animating into view produces a window-change per
     * frame, and forwarding each one makes tmux and nvim redraw the whole screen a dozen times
     * for a single keyboard open.
     *
     * The cell's pixel size travels with the grid size rather than through a setter of its own,
     * so the one window-change carries both and the remote never sees a grid with stale pixels.
     */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        // Resizing moves the live screen's top edge without any output arriving, and the offset
        // is measured from the bottom, so it has to absorb that movement in both directions:
        // a shrink pushes rows into scrollback, a taller viewport pulls them back out. Left
        // uncorrected, opening the keyboard or changing the font size while scrolled back drags
        // the content away exactly as unanchored output used to.
        val (scrolled, cell) = synchronized(lock) {
            emulator.cellPixelSize = CellPixelSize(cellWidthPx, cellHeightPx)
            cellMeasured = true
            val scrolledBefore = emulator.buffer.totalLinesScrolled
            val historyBefore = emulator.buffer.scrollbackSize
            emulator.resize(columns, rows)
            val pushed = emulator.buffer.totalLinesScrolled - scrolledBefore
            val pulled = (historyBefore - emulator.buffer.scrollbackSize).coerceAtLeast(0)
            (pushed - pulled) to emulator.cellPixelSize
        }
        _scrollOffset.update { current ->
            if (current == 0) 0 else (current + scrolled.toInt()).coerceAtMost(maxScrollOffset())
        }

        pendingResize?.cancel()
        pendingResize = scope.launch {
            delay(RESIZE_SETTLE_MILLIS)
            transport.resize(columns, rows, columns * cell.width, rows * cell.height)
        }
    }

    fun scrollBy(lines: Int) {
        val max = maxScrollOffset()
        val moved = _scrollOffset.getAndUpdate { (it + lines).coerceIn(0, max) }
        if (moved != _scrollOffset.value) {
            _revision.value = _revision.value + 1
        }
    }

    fun scrollToBottom() {
        if (_scrollOffset.getAndUpdate { 0 } == 0) return
        _revision.value = _revision.value + 1
    }

    fun maxScrollOffset(): Int = synchronized(lock) { emulator.buffer.scrollbackSize }

    /** How far the buffer has scrolled overall, for addressing a row that outlives the screen. */
    fun linesScrolled(): Long = synchronized(lock) { emulator.buffer.totalLinesScrolled }

    suspend fun disconnect() {
        log.i("session.disconnect", "session" to id.value, "host" to hostName)
        transport.disconnect()
    }

    /** Releases the session's coroutines; the session cannot be reused afterwards. */
    fun destroy() {
        closing = true
        // Closed rather than cancelled: cancelling would push every queued reply through
        // `onUndeliveredElement` and count a normal teardown as a burst of drops.
        input.close()
        replies.close()
        // Takes the writer, the pump, a settling resize and a handshake still in flight with it.
        scope.cancel()
        (transport as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private companion object {
        /**
         * Long enough to swallow an IME or fold animation, short enough that the remote has the
         * right size before the user can type into it.
         */
        const val RESIZE_SETTLE_MILLIS = 120L

        /**
         * Replies are at most ~85 bytes (a graphics error with two ids), so this is under 90 KiB
         * at worst — and far more than a client that reads its input can have outstanding, since
         * replies only queue while the remote is not reading at all.
         */
        const val REPLY_QUEUE_CAPACITY = 1024

        /** A stuck remote can provoke a drop per query; one line, then one per ten thousand. */
        const val DROPPED_REPLY_LOG_INTERVAL = 10_000L
    }
}
