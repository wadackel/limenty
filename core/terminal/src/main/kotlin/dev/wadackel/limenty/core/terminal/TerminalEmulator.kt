package dev.wadackel.limenty.core.terminal

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import dev.wadackel.limenty.core.terminal.graphics.GraphicsCommand
import dev.wadackel.limenty.core.terminal.graphics.GraphicsStore
import dev.wadackel.limenty.core.terminal.graphics.Placement
import dev.wadackel.limenty.core.terminal.graphics.TerminalImage
import dev.wadackel.limenty.core.terminal.graphics.Transmission
import java.util.Base64
import kotlin.math.ceil

/**
 * The terminal screen: applies parsed sequences to a buffer and a cursor.
 *
 * The emulator owns no threads, no transport and no rendering. It is fed bytes and mutated in
 * place, which is what lets the whole of VT behaviour be tested on the JVM against assertions
 * about screen contents rather than about pixels.
 */
class TerminalEmulator(
    columns: Int,
    rows: Int,
    private val observer: TerminalObserver = object : TerminalObserver {},
    maxScrollback: Int = TerminalBuffer.DEFAULT_SCROLLBACK,
) : AnsiHandler {

    private val log = TermLog.logger(LogTags.TERMINAL)
    private val graphicsLog = TermLog.logger(LogTags.GRAPHICS)

    var buffer = TerminalBuffer(columns, rows, maxScrollback)
        private set

    private val parser = AnsiParser(this)

    private var alternateBuffer: TerminalBuffer? = null

    /**
     * Images clients have sent, shared by both screens: a placement belongs to the screen it was
     * made on, but the pixels behind it are worth keeping across an alternate-screen round trip.
     */
    val graphics = GraphicsStore(
        isPlaced = ::isPlaced,
        onRemoved = { id ->
            buffer.removePlacements { it.imageId == id }
            alternateBuffer?.removePlacements { it.imageId == id }
        },
    )

    /** Set by whoever draws the grid; see [CellPixelSize]. */
    var cellPixelSize: CellPixelSize = CellPixelSize.Unmeasured

    /** Cursor row of whichever buffer is currently swapped out, so a resize can respect it. */
    private var inactiveCursorRow = 0

    // The cursor is held as primitives and materialised on read: `print()` moves it for every
    // character, and allocating a Cursor per character put tens of millions of objects in front
    // of the GC during a large paste or a `cat`.
    private var cursorRow = 0
    private var cursorColumn = 0
    private var cursorVisible = true

    val cursor: Cursor get() = Cursor(cursorRow, cursorColumn, cursorVisible)

    var pen: Pen = Pen.Default
        private set

    var modes = TerminalModes()
        private set

    var title: String = ""
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    /**
     * Set once the cursor has been parked past the last column, deferring the line wrap until the
     * next character actually arrives. Wrapping eagerly would move the cursor to the next line
     * after a character that exactly fills the row, which shifts every subsequent redraw by a line.
     */
    private var wrapPending = false

    // xterm keeps one DECSC slot per screen. With a single slot, a TUI that saves its own cursor
    // on the alternate screen overwrote the one `?1049h` saved, and the shell prompt came back
    // wherever the TUI had last been.
    private var savedMain: SavedCursor? = null
    private var savedAlternate: SavedCursor? = null

    // G0 and G1 are either ASCII or DEC line drawing; SO and SI choose which one prints.
    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var shiftedOut = false

    /**
     * What REP repeats. Reading it back from the cell left of the cursor found the placeholder
     * half of a wide character, or the end of the previous row after a wrap.
     */
    private var lastPrinted = -1

    val columns: Int get() = buffer.columns
    val rows: Int get() = buffer.rows

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        parser.feed(bytes, offset, length)
        observer.invalidate()
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        val previous = "${columns}x$rows"
        val newRow = buffer.resize(newColumns, newRows, cursorRow, pen)
        // A saved position moves with the lines around it, or restoring it after a fold puts the
        // cursor on a line that has since scrolled somewhere else.
        shiftSaved(alternate = modes.alternateScreen, by = newRow - cursorRow)
        // The inactive buffer is resized against its own cursor row, not zero: passing zero told
        // it every line below the top was expendable, so folding inside tmux ate the shell screen
        // waiting underneath.
        alternateBuffer?.let { inactive ->
            val inactiveRow = inactive.resize(newColumns, newRows, inactiveCursorRow, Pen.Default)
            shiftSaved(alternate = !modes.alternateScreen, by = inactiveRow - inactiveCursorRow)
            inactiveCursorRow = inactiveRow
        }
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = newRow.coerceIn(0, newRows - 1)
        cursorColumn = cursorColumn.coerceIn(0, newColumns - 1)
        wrapPending = false
        log.i("resize", "from" to previous, "to" to "${newColumns}x$newRows", "cursorRow" to cursorRow)
        observer.invalidate()
    }

    // --- AnsiHandler ---

    override fun print(codePoint: Int) {
        if (codePoint in LINE_DRAWING_FIRST..LINE_DRAWING_LAST && (if (shiftedOut) g1LineDrawing else g0LineDrawing)) {
            printCell(LINE_DRAWING[codePoint - LINE_DRAWING_FIRST].code)
        } else {
            printCell(codePoint)
        }
    }

    private fun printCell(codePoint: Int) {
        val width = CharacterWidth.of(codePoint)
        if (width == 0) {
            attachCombining(codePoint)
            return
        }
        lastPrinted = codePoint

        if (wrapPending) {
            buffer.screenRow(cursorRow).wrapped = true
            lineFeed(retainOnScroll = true)
            cursorColumn = 0
            wrapPending = false
        }

        // A double-width character cannot straddle the right edge, so it moves to the next line
        // whole rather than being split across the boundary.
        if (width == 2 && cursorColumn == columns - 1) {
            buffer.screenRow(cursorRow).clearRange(cursorColumn, columns, pen)
            buffer.screenRow(cursorRow).wrapped = true
            lineFeed(retainOnScroll = true)
            cursorColumn = 0
        }

        val row = buffer.screenRow(cursorRow)
        if (modes.insertMode) row.insert(cursorColumn, width, pen)

        clearWideNeighbour(row, cursorColumn)
        row.setCell(cursorColumn, codePoint, pen, if (width == 2) CellFlags.WIDE_LEADING else 0)
        if (width == 2 && cursorColumn + 1 < columns) {
            clearWideNeighbour(row, cursorColumn + 1)
            row.setCell(cursorColumn + 1, TerminalRow.SPACE, pen, CellFlags.WIDE_TRAILING)
        }

        val next = cursorColumn + width
        if (next >= columns) {
            if (modes.autoWrap) {
                cursorColumn = columns - 1
                wrapPending = true
            } else {
                cursorColumn = columns - 1
            }
        } else {
            cursorColumn = next
        }
    }

    /**
     * Overwriting one half of a double-width character would leave the other half orphaned and
     * render as a stray glyph, so the whole pair is cleared first.
     */
    private fun clearWideNeighbour(row: TerminalRow, column: Int) {
        val flags = row.flagsAt(column)
        if (flags and CellFlags.WIDE_LEADING != 0 && column + 1 < columns) {
            row.setCell(column + 1, TerminalRow.SPACE, pen)
        }
        if (flags and CellFlags.WIDE_TRAILING != 0 && column > 0) {
            row.setCell(column - 1, TerminalRow.SPACE, pen)
        }
    }

    /**
     * Attaches a zero-width mark to the character just written.
     *
     * The cursor sits two cells past a double-width character, so the naive "one cell back" lands
     * on its trailing placeholder — where the mark would be invisible to rendering and dropped by
     * copy. macOS hands out decomposed filenames, so this is the difference between seeing
     * `が` and `か` in an `ls` listing.
     */
    private fun attachCombining(codePoint: Int) {
        val row = buffer.screenRow(cursorRow)
        var column = if (wrapPending) cursorColumn else cursorColumn - 1
        if (column >= 0 && column < columns && row.flagsAt(column) and CellFlags.WIDE_TRAILING != 0) {
            column--
        }
        if (column < 0) return
        row.appendCombining(column, codePoint)
    }

    override fun execute(control: Int) {
        when (control) {
            0x07 -> observer.bell()
            0x08 -> backspace()
            0x09 -> tabForward()
            0x0A, 0x0B, 0x0C -> {
                lineFeed(retainOnScroll = true)
                wrapPending = false
            }
            0x0D -> {
                cursorColumn = 0
                wrapPending = false
            }
            0x0E -> shiftedOut = true
            0x0F -> shiftedOut = false
        }
    }

    override fun esc(intermediates: String, final: Char) {
        when {
            intermediates.isEmpty() && final == 'D' -> lineFeed(retainOnScroll = true)
            intermediates.isEmpty() && final == 'E' -> {
                lineFeed(retainOnScroll = true)
                cursorColumn = 0
                wrapPending = false
            }
            intermediates.isEmpty() && final == 'M' -> reverseLineFeed()
            intermediates.isEmpty() && final == '7' -> saveCursor()
            intermediates.isEmpty() && final == '8' -> restoreCursor()
            intermediates.isEmpty() && final == 'c' -> reset()
            intermediates.isEmpty() && final == '=' -> Unit
            intermediates.isEmpty() && final == '>' -> Unit
            // ncurses draws every box with `smacs` (`ESC ( 0`) under TERM=xterm-256color, so line
            // drawing is the one designation that matters; any other set means plain text again.
            intermediates == "(" -> g0LineDrawing = final == '0'
            intermediates == ")" -> g1LineDrawing = final == '0'
            intermediates.startsWith("(") || intermediates.startsWith(")") -> Unit
            intermediates == "#" && final == '8' -> fillScreen('E'.code)
            else -> log.v("esc.unhandled", "intermediates" to intermediates, "final" to final)
        }
    }

    override fun csi(
        params: IntArray,
        subParams: List<IntArray>,
        intermediates: String,
        private: Char?,
        final: Char,
    ) {
        fun param(index: Int, default: Int): Int {
            val value = params.getOrElse(index) { AnsiParser.ABSENT }
            return if (value == AnsiParser.ABSENT || value == 0) default else value
        }

        fun rawParam(index: Int): Int = params.getOrElse(index) { AnsiParser.ABSENT }

        if (private == '?') {
            when (final) {
                'h' -> params.forEach { setPrivateMode(it, true) }
                'l' -> params.forEach { setPrivateMode(it, false) }
                // XTQMODKEYS. An application asks what a modifier option is set to before it
                // trusts a set of it; nvim does exactly this.
                'm' -> if (param(0, -1) == MODIFY_OTHER_KEYS) {
                    observer.respond(
                        "\u001B[>$MODIFY_OTHER_KEYS;${modes.keys.modifyOtherKeys}m".toByteArray(Charsets.US_ASCII),
                    )
                }
                else -> log.v("csi.unhandled", "private" to private, "final" to final)
            }
            return
        }

        if (private == '>') {
            // XTMODKEYS. `CSI > 4 ; n m` chooses how modified keys are reported; `CSI > 4 m` with
            // no value resets it, which is how an application hands the keyboard back on the way
            // out. Reading it as an SGR instead would stain the pen, which is what the rest of
            // this private space is still discarded for.
            if (final == 'm' && param(0, -1) == MODIFY_OTHER_KEYS) {
                val level = if (params.size > 1) param(1, 0) else 0
                updateModes { copy(keys = keys.copy(modifyOtherKeys = level.coerceIn(0, 2))) }
                log.i("modifyOtherKeys", "level" to modes.keys.modifyOtherKeys)
                return
            }
        }

        if (private != null) {
            // `>`, `=` and `<` introduce a private parameter space with its own meanings. Falling
            // through would read them as SGR and stain the pen, and would answer a secondary
            // device attributes query with the primary reply.
            log.v("csi.privateSpace", "marker" to private, "final" to final, "params" to params.joinToString(","))
            return
        }

        when (final) {
            'A' -> moveCursorUp(param(0, 1))
            'B', 'e' -> moveCursorDown(param(0, 1))
            'C', 'a' -> moveCursorRight(param(0, 1))
            'D' -> moveCursorLeft(param(0, 1))
            'E' -> {
                moveCursorDown(param(0, 1))
                cursorColumn = 0
            }
            'F' -> {
                moveCursorUp(param(0, 1))
                cursorColumn = 0
            }
            'G', '`' -> setColumn(param(0, 1) - 1)
            'H', 'f' -> setPosition(param(0, 1) - 1, param(1, 1) - 1)
            'd' -> setRow(param(0, 1) - 1)
            'I' -> repeat(param(0, 1)) { tabForward() }
            'Z' -> repeat(param(0, 1)) { tabBackward() }
            'J' -> eraseInDisplay(if (rawParam(0) == AnsiParser.ABSENT) 0 else rawParam(0))
            'K' -> eraseInLine(if (rawParam(0) == AnsiParser.ABSENT) 0 else rawParam(0))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'P' -> {
                buffer.screenRow(cursorRow).delete(cursorColumn, param(0, 1), pen)
                wrapPending = false
            }
            '@' -> {
                buffer.screenRow(cursorRow).insert(cursorColumn, param(0, 1), pen)
                wrapPending = false
            }
            'X' -> eraseCharacters(param(0, 1))
            'S' -> buffer.scrollUp(scrollTop, scrollBottom, param(0, 1), pen, retain = scrollsIntoHistory())
            'T' -> buffer.scrollDown(scrollTop, scrollBottom, param(0, 1), pen)
            'b' -> repeatLastCharacter(param(0, 1))
            'm' -> applyGraphicRendition(params, subParams)
            'r' -> setScrollRegion(param(0, 1) - 1, param(1, rows) - 1)
            'h' -> params.forEach { setAnsiMode(it, true) }
            'l' -> params.forEach { setAnsiMode(it, false) }
            'n' -> deviceStatusReport(param(0, 0))
            'c' -> observer.respond(DEVICE_ATTRIBUTES)
            'p' -> if (intermediates == "!") softReset() else log.v("csi.unhandled", "final" to final)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> windowReport(param(0, 0))
            'q' -> log.v("csi.cursorStyle", "params" to params.joinToString(","))
            else -> log.v("csi.unhandled", "final" to final, "params" to params.joinToString(","))
        }
    }

    override fun osc(identifier: Int, body: String) {
        when (identifier) {
            // OSC shares the clipboard's generous cap, and the title is laid out on every redraw of
            // the host list; nothing legible needs more than this.
            0, 2 -> {
                title = body.take(MAX_TITLE_LENGTH)
                observer.titleChanged(title)
            }
            1 -> Unit
            52 -> {
                val separator = body.indexOf(';')
                if (separator >= 0) {
                    val payload = body.substring(separator + 1)
                    // `?` asks the terminal to send the clipboard back to the remote. That is a
                    // read of the user's clipboard by whatever is running on the far end, so the
                    // request is dropped here rather than forwarded for someone to answer.
                    if (payload == "?") {
                        log.w("osc52.readRefused")
                    } else {
                        clipboardWrite(payload)
                    }
                }
            }
            else -> log.v("osc.unhandled", "id" to identifier)
        }
    }

    /**
     * The selection parameter is not consulted: it names X11 buffers, and on Android every one of
     * them can only mean the clipboard. A payload that does not decode is dropped rather than
     * clearing the clipboard on the remote's say-so.
     */
    private fun clipboardWrite(payload: String) {
        val text = try {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            log.w("osc52.rejected", "reason" to "base64")
            return
        }
        if (text.isEmpty()) {
            log.w("osc52.rejected", "reason" to "empty")
            return
        }
        observer.clipboardWrite(text)
    }

    override fun dcs(params: IntArray, intermediates: String, body: String) {
        log.v("dcs.ignored", "intermediates" to intermediates, "length" to body.length)
    }

    override fun apc(body: ByteArray, truncated: Boolean) {
        if (truncated) {
            graphicsLog.w("reject", "reason" to "oversize")
            graphics.abortTransfer("oversize")
            return
        }
        val command = GraphicsCommand.parse(body)
        if (command == null) {
            log.v("apc.ignored", "length" to body.size)
            return
        }
        when (command.action) {
            't', 'T', 'q' -> {
                val result = graphics.receive(command)
                if (result !is Transmission.Complete) return
                var error = result.error
                if (error == null && result.image != null && result.command.action == 'T') {
                    error = placeImage(result.command, result.image)
                }
                respondGraphics(result.command, error)
            }
            'p' -> {
                graphics.abortTransfer("placement")
                val image = graphics.image(command.imageId)
                val error = when {
                    command.error != null -> command.error
                    image == null -> "ENOENT:no image with id ${command.imageId.toUInt()}"
                    else -> placeImage(command, image)
                }
                respondGraphics(command, error)
            }
            'd' -> {
                graphics.abortTransfer("delete")
                if (command.error == null) deletePlacements(command)
                respondGraphics(command, command.error)
            }
            // Animation and anything newer: refused out loud, so a client waiting on a reply
            // for a frame it sent gets one instead of a stall.
            else -> respondGraphics(command, command.error)
        }
    }

    private fun respondGraphics(command: GraphicsCommand, error: String?) {
        GraphicsStore.response(command, error)?.let { observer.respond(it) }
    }

    /**
     * Puts [image] at the cursor and, unless asked not to, walks the cursor past it the way kitty
     * does: to the image's last row, just right of it. Returns the error to report, or null.
     */
    private fun placeImage(command: GraphicsCommand, image: TerminalImage): String? {
        val sourceX = command.sourceX
        val sourceY = command.sourceY
        if (sourceX >= image.width || sourceY >= image.height) return "EINVAL:crop starts outside the image"
        val sourceWidth = if (command.sourceWidth > 0) minOf(command.sourceWidth, image.width - sourceX) else image.width - sourceX
        val sourceHeight = if (command.sourceHeight > 0) minOf(command.sourceHeight, image.height - sourceY) else image.height - sourceY

        val cellWidth = cellPixelSize.width.toDouble()
        val cellHeight = cellPixelSize.height.toDouble()
        // Clamped before any cursor arithmetic: a client may ask for a placement wider than the
        // screen, but one wide enough to wrap an Int would walk the cursor off the row arrays.
        val requestedColumns = command.columns.coerceAtMost(MAX_PLACEMENT_SPAN)
        val requestedRows = command.rows.coerceAtMost(MAX_PLACEMENT_SPAN)
        val placementColumns: Int
        val placementRows: Int
        when {
            requestedColumns > 0 && requestedRows > 0 -> {
                placementColumns = requestedColumns
                placementRows = requestedRows
            }
            requestedColumns > 0 -> {
                placementColumns = requestedColumns
                placementRows = ceil(requestedColumns * cellWidth * sourceHeight / sourceWidth / cellHeight).toInt().coerceIn(1, MAX_PLACEMENT_SPAN)
            }
            requestedRows > 0 -> {
                placementRows = requestedRows
                placementColumns = ceil(requestedRows * cellHeight * sourceWidth / sourceHeight / cellWidth).toInt().coerceIn(1, MAX_PLACEMENT_SPAN)
            }
            else -> {
                placementColumns = ceil(sourceWidth / cellWidth).toInt().coerceIn(1, MAX_PLACEMENT_SPAN)
                placementRows = ceil(sourceHeight / cellHeight).toInt().coerceIn(1, MAX_PLACEMENT_SPAN)
            }
        }

        if (command.placementId != 0) {
            buffer.removePlacements { it.imageId == image.id && it.placementId == command.placementId }
        }
        buffer.place(
            Placement(
                imageId = image.id,
                placementId = command.placementId,
                row = cursorRow,
                column = cursorColumn,
                columns = placementColumns,
                rows = placementRows,
                sourceX = sourceX,
                sourceY = sourceY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            ),
        )
        graphicsLog.i(
            "place",
            "id" to image.id.toUInt(),
            "row" to cursorRow,
            "column" to cursorColumn,
            "columns" to placementColumns,
            "rows" to placementRows,
        )

        if (!command.keepCursor) {
            var targetRow = cursorRow + placementRows - 1
            cursorColumn += placementColumns
            if (cursorColumn >= columns) {
                cursorColumn = 0
                targetRow++
            }
            val overflow = targetRow - scrollBottom
            if (overflow > 0 && cursorRow <= scrollBottom) {
                buffer.scrollUp(scrollTop, scrollBottom, overflow, pen, retain = scrollsIntoHistory())
                targetRow = scrollBottom
            }
            cursorRow = targetRow.coerceIn(0, rows - 1)
            wrapPending = false
        }
        return null
    }

    private fun deletePlacements(command: GraphicsCommand) {
        val target = command.deleteTarget
        val removed = mutableSetOf<Int>()
        val matched: (Placement) -> Boolean = when (target.lowercaseChar()) {
            'a' -> { placement -> placement.reachesScreen() }
            'i' -> { placement ->
                placement.imageId == command.imageId && (command.placementId == 0 || placement.placementId == command.placementId)
            }
            'c' -> { placement ->
                cursorRow >= placement.row && cursorRow < placement.row + placement.rows &&
                    cursorColumn >= placement.column && cursorColumn < placement.column + placement.columns
            }
            else -> {
                graphicsLog.i("delete", "target" to "unsupported")
                return
            }
        }
        buffer.removePlacements { placement -> matched(placement).also { if (it) removed += placement.imageId } }
        graphicsLog.i("delete", "target" to target, "images" to removed.size)
        if (target.isUpperCase()) {
            if (target == 'I') removed += command.imageId
            removed.filterNot(::isPlaced).forEach { graphics.delete(it) }
        }
    }

    private fun isPlaced(imageId: Int): Boolean = buffer.hasPlacementOf(imageId) || alternateBuffer?.hasPlacementOf(imageId) == true

    /** XTWINOPS. Only the two reports a graphics client sizes itself by are answered. */
    private fun windowReport(kind: Int) {
        when (kind) {
            14 -> observer.respond(
                "\u001B[4;${rows * cellPixelSize.height};${columns * cellPixelSize.width}t".toByteArray(Charsets.US_ASCII),
            )
            16 -> observer.respond("\u001B[6;${cellPixelSize.height};${cellPixelSize.width}t".toByteArray(Charsets.US_ASCII))
            else -> log.v("csi.window", "kind" to kind)
        }
    }

    // --- cursor ---

    private fun moveCursorUp(count: Int) {
        val limit = if (cursorRow >= scrollTop) scrollTop else 0
        cursorRow = (cursorRow - count).coerceAtLeast(limit)
        wrapPending = false
    }

    private fun moveCursorDown(count: Int) {
        val limit = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = (cursorRow + count).coerceAtMost(limit)
        wrapPending = false
    }

    /**
     * At the right margin with a wrap already pending, the cursor is visually still on the last
     * column, so BS cancels the pending wrap instead of stepping back a cell — moving as well
     * would skip a column, which is what makes a full-width line of readline input drift.
     */
    private fun backspace() {
        if (wrapPending) {
            wrapPending = false
            return
        }
        moveCursorLeft(1)
    }

    private fun moveCursorLeft(count: Int) {
        cursorColumn = (cursorColumn - count).coerceAtLeast(0)
        wrapPending = false
    }

    private fun moveCursorRight(count: Int) {
        cursorColumn = (cursorColumn + count).coerceAtMost(columns - 1)
        wrapPending = false
    }

    private fun setColumn(column: Int) {
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
    }

    private fun setRow(row: Int) {
        cursorRow = resolveRow(row)
        wrapPending = false
    }

    private fun setPosition(row: Int, column: Int) {
        cursorRow = resolveRow(row)
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
    }

    /** Origin mode makes row numbers relative to the scrolling region rather than the screen. */
    private fun resolveRow(row: Int): Int = if (modes.originMode) (scrollTop + row).coerceIn(scrollTop, scrollBottom) else row.coerceIn(0, rows - 1)

    private fun lineFeed(retainOnScroll: Boolean) {
        if (cursorRow == scrollBottom) {
            buffer.scrollUp(scrollTop, scrollBottom, 1, pen, retain = retainOnScroll && scrollsIntoHistory())
        } else if (cursorRow < rows - 1) {
            cursorRow += 1
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            buffer.scrollDown(scrollTop, scrollBottom, 1, pen)
        } else if (cursorRow > 0) {
            cursorRow -= 1
        }
        wrapPending = false
    }

    /**
     * Lines scrolled out of a region that starts lower down belong to an application's own
     * viewport, not to the user's history. A region anchored at the top is different: apt and
     * dpkg reserve only the bottom row for a progress bar and scroll the whole log above it, and
     * xterm keeps those lines for the same reason.
     */
    private fun scrollsIntoHistory(): Boolean = scrollTop == 0 && !modes.alternateScreen

    private fun tabForward() {
        val next = ((cursorColumn / TAB_WIDTH) + 1) * TAB_WIDTH
        cursorColumn = next.coerceAtMost(columns - 1)
        wrapPending = false
    }

    private fun tabBackward() {
        val previous = ((cursorColumn - 1) / TAB_WIDTH) * TAB_WIDTH
        cursorColumn = previous.coerceAtLeast(0)
        wrapPending = false
    }

    private fun saveCursor(alternate: Boolean = modes.alternateScreen) {
        val saved = SavedCursor(cursorRow, cursorColumn, pen, g0LineDrawing, g1LineDrawing, shiftedOut)
        if (alternate) savedAlternate = saved else savedMain = saved
    }

    private fun restoreCursor(alternate: Boolean = modes.alternateScreen) {
        (if (alternate) savedAlternate else savedMain)?.let {
            cursorRow = it.row.coerceIn(0, rows - 1)
            cursorColumn = it.column.coerceIn(0, columns - 1)
            pen = it.pen
            g0LineDrawing = it.g0LineDrawing
            g1LineDrawing = it.g1LineDrawing
            shiftedOut = it.shiftedOut
        }
        wrapPending = false
    }

    private fun shiftSaved(alternate: Boolean, by: Int) {
        if (by == 0) return
        if (alternate) {
            savedAlternate = savedAlternate?.let { it.copy(row = (it.row + by).coerceIn(0, rows - 1)) }
        } else {
            savedMain = savedMain?.let { it.copy(row = (it.row + by).coerceIn(0, rows - 1)) }
        }
    }

    // --- erase ---

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                buffer.screenRow(cursorRow).clearRange(cursorColumn, columns, pen)
                for (row in cursorRow + 1 until rows) buffer.screenRow(row).clear(pen)
            }
            1 -> {
                for (row in 0 until cursorRow) buffer.screenRow(row).clear(pen)
                buffer.screenRow(cursorRow).clearRange(0, minOf(cursorColumn + 1, columns), pen)
            }
            2 -> graphics.release(buffer.clearScreen(pen))
            // `CSI 3 J` is the explicit "and drop the scrollback" form, which is what `clear`
            // sends; erasing the screen alone must never discard the user's history.
            3 -> graphics.release(buffer.clearScrollback())
        }
        wrapPending = false
    }

    private fun eraseInLine(mode: Int) {
        val row = buffer.screenRow(cursorRow)
        when (mode) {
            0 -> row.clearRange(cursorColumn, columns, pen)
            1 -> row.clearRange(0, minOf(cursorColumn + 1, columns), pen)
            2 -> row.clear(pen)
        }
        wrapPending = false
    }

    private fun eraseCharacters(count: Int) {
        val row = buffer.screenRow(cursorRow)
        row.clearRange(cursorColumn, minOf(cursorColumn + count, columns), pen)
        wrapPending = false
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollDown(cursorRow, scrollBottom, count, pen)
        cursorColumn = 0
        wrapPending = false
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollUp(cursorRow, scrollBottom, count, pen, retain = false)
        cursorColumn = 0
        wrapPending = false
    }

    private fun repeatLastCharacter(count: Int) {
        val codePoint = lastPrinted
        if (codePoint < 0) return
        repeat(count) { printCell(codePoint) }
    }

    private fun fillScreen(codePoint: Int) {
        for (row in 0 until rows) {
            val line = buffer.screenRow(row)
            for (column in 0 until columns) line.setCell(column, codePoint, pen)
        }
    }

    // --- modes ---

    private fun setScrollRegion(top: Int, bottom: Int) {
        val newTop = top.coerceIn(0, rows - 1)
        val newBottom = bottom.coerceIn(0, rows - 1)
        if (newTop >= newBottom) return
        scrollTop = newTop
        scrollBottom = newBottom
        cursorRow = if (modes.originMode) scrollTop else 0
        cursorColumn = 0
        wrapPending = false
    }

    private fun setAnsiMode(mode: Int, enabled: Boolean) {
        when (mode) {
            4 -> updateModes { copy(insertMode = enabled) }
            else -> log.v("mode.unhandled", "mode" to mode, "enabled" to enabled)
        }
    }

    private fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> updateModes { copy(keys = keys.copy(applicationCursorKeys = enabled)) }
            5 -> updateModes { copy(reverseVideo = enabled) }
            6 -> {
                updateModes { copy(originMode = enabled) }
                setPosition(0, 0)
            }
            7 -> updateModes { copy(autoWrap = enabled) }
            9 -> updateModes { copy(mouse = if (enabled) MouseTracking.X10 else MouseTracking.None) }
            12 -> Unit
            25 -> cursorVisible = enabled
            67 -> updateModes { copy(keys = keys.copy(backspaceSendsBackspace = enabled)) }
            1000 -> updateModes { copy(mouse = if (enabled) MouseTracking.Normal else MouseTracking.None) }
            1002 -> updateModes { copy(mouse = if (enabled) MouseTracking.ButtonEvent else MouseTracking.None) }
            1003 -> updateModes { copy(mouse = if (enabled) MouseTracking.AnyEvent else MouseTracking.None) }
            1004 -> updateModes { copy(focusReporting = enabled) }
            1005 -> updateModes { copy(mouseEncoding = if (enabled) MouseEncoding.Utf8 else MouseEncoding.Default) }
            1006 -> updateModes { copy(mouseEncoding = if (enabled) MouseEncoding.Sgr else MouseEncoding.Default) }
            1047, 1049 -> switchAlternateScreen(enabled, saveRestoreCursor = mode == 1049)
            1048 -> if (enabled) saveCursor() else restoreCursor()
            2004 -> updateModes { copy(bracketedPaste = enabled) }
            else -> log.v("privateMode.unhandled", "mode" to mode, "enabled" to enabled)
        }
    }

    private inline fun updateModes(block: TerminalModes.() -> TerminalModes) {
        val updated = modes.block()
        if (updated == modes) return
        modes = updated
        observer.modesChanged(updated)
    }

    /**
     * The alternate screen is a second buffer with no scrollback: full-screen applications get a
     * scratch surface, and leaving one restores the shell output that was on screen before it.
     */
    private fun switchAlternateScreen(enabled: Boolean, saveRestoreCursor: Boolean) {
        if (enabled == modes.alternateScreen) return
        if (enabled) {
            if (saveRestoreCursor) saveCursor(alternate = false)
            savedAlternate = null
            inactiveCursorRow = cursorRow
            alternateBuffer = TerminalBuffer(columns, rows, maxScrollback = 0)
            swapBuffers()
            buffer.clearScreen(pen)
            cursorRow = 0
            cursorColumn = 0
        } else {
            swapBuffers()
            alternateBuffer = null
            cursorRow = inactiveCursorRow.coerceIn(0, rows - 1)
            if (saveRestoreCursor) restoreCursor(alternate = false)
        }
        scrollTop = 0
        scrollBottom = rows - 1
        updateModes { copy(alternateScreen = enabled) }
    }

    private fun swapBuffers() {
        val other = alternateBuffer ?: return
        alternateBuffer = buffer
        buffer = other
    }

    private fun deviceStatusReport(mode: Int) {
        when (mode) {
            5 -> observer.respond(STATUS_OK)
            6 -> {
                val row = if (modes.originMode) cursorRow - scrollTop else cursorRow
                observer.respond("\u001B[${row + 1};${cursorColumn + 1}R".toByteArray(Charsets.US_ASCII))
            }
        }
    }

    // --- SGR ---

    private fun applyGraphicRendition(params: IntArray, subParams: List<IntArray>) {
        if (params.isEmpty()) {
            pen = Pen.Default
            return
        }
        var index = 0
        while (index < params.size) {
            val code = params[index].let { if (it == AnsiParser.ABSENT) 0 else it }
            when (code) {
                0 -> pen = Pen.Default
                1 -> pen = pen.with(CellFlags.BOLD)
                2 -> pen = pen.with(CellFlags.DIM)
                3 -> pen = pen.with(CellFlags.ITALIC)
                4 -> pen = applyUnderline(subParams.getOrNull(index))
                5, 6 -> pen = pen.with(CellFlags.BLINK)
                7 -> pen = pen.with(CellFlags.REVERSE)
                8 -> pen = pen.with(CellFlags.INVISIBLE)
                9 -> pen = pen.with(CellFlags.STRIKETHROUGH)
                21 -> pen = pen.with(CellFlags.DOUBLE_UNDERLINE).without(CellFlags.UNDERLINE)
                22 -> pen = pen.without(CellFlags.BOLD or CellFlags.DIM)
                23 -> pen = pen.without(CellFlags.ITALIC)
                24 -> pen = pen.without(CellFlags.UNDERLINE or CellFlags.DOUBLE_UNDERLINE)
                25 -> pen = pen.without(CellFlags.BLINK)
                27 -> pen = pen.without(CellFlags.REVERSE)
                28 -> pen = pen.without(CellFlags.INVISIBLE)
                29 -> pen = pen.without(CellFlags.STRIKETHROUGH)
                in 30..37 -> pen = pen.copy(foreground = TerminalColor.indexed(code - 30))
                38 -> index = applyExtendedColour(params, subParams, index, foreground = true)
                39 -> pen = pen.copy(foreground = TerminalColor.Default)
                in 40..47 -> pen = pen.copy(background = TerminalColor.indexed(code - 40))
                48 -> index = applyExtendedColour(params, subParams, index, foreground = false)
                49 -> pen = pen.copy(background = TerminalColor.Default)
                in 90..97 -> pen = pen.copy(foreground = TerminalColor.indexed(code - 90 + 8))
                in 100..107 -> pen = pen.copy(background = TerminalColor.indexed(code - 100 + 8))
                else -> log.v("sgr.unhandled", "code" to code)
            }
            index++
        }
    }

    private fun applyUnderline(sub: IntArray?): Pen = when (sub?.getOrNull(1)) {
        0 -> pen.without(CellFlags.UNDERLINE or CellFlags.DOUBLE_UNDERLINE)
        2 -> pen.with(CellFlags.DOUBLE_UNDERLINE)
        else -> pen.with(CellFlags.UNDERLINE)
    }

    /**
     * Extended colour arrives in two shapes: `38;5;n` / `38;2;r;g;b` with semicolons, and
     * `38:5:n` / `38:2:...` with colons. Both are in active use — the colon form is what nvim
     * emits — so both are accepted, and the semicolon form reports how many parameters it ate.
     */
    private fun applyExtendedColour(
        params: IntArray,
        subParams: List<IntArray>,
        index: Int,
        foreground: Boolean,
    ): Int {
        val colon = subParams.getOrNull(index)
        if (colon != null && colon.size >= 2) {
            val colour = when (colon[1]) {
                5 -> colon.getOrNull(2)?.let { TerminalColor.indexed(it) }
                2 -> {
                    // The colon form may carry a colour-space id that the semicolon form lacks.
                    val base = if (colon.size >= 6) 3 else 2
                    if (colon.size >= base + 3) {
                        TerminalColor.rgb(colon[base], colon[base + 1], colon[base + 2])
                    } else {
                        null
                    }
                }
                else -> null
            } ?: return index
            pen = if (foreground) pen.copy(foreground = colour) else pen.copy(background = colour)
            return index
        }

        return when (params.getOrNull(index + 1)) {
            5 -> {
                params.getOrNull(index + 2)?.takeIf { it != AnsiParser.ABSENT }?.let {
                    val colour = TerminalColor.indexed(it)
                    pen = if (foreground) pen.copy(foreground = colour) else pen.copy(background = colour)
                }
                index + 2
            }
            2 -> {
                val red = params.getOrNull(index + 2) ?: 0
                val green = params.getOrNull(index + 3) ?: 0
                val blue = params.getOrNull(index + 4) ?: 0
                val colour = TerminalColor.rgb(maxOf(red, 0), maxOf(green, 0), maxOf(blue, 0))
                pen = if (foreground) pen.copy(foreground = colour) else pen.copy(background = colour)
                index + 4
            }
            else -> index
        }
    }

    // --- reset ---

    /**
     * DECSTR. Returns the terminal's settings to their defaults but leaves the screen alone —
     * unlike RIS, which also clears it. terminfo's `rs2` uses this, so treating it as a full
     * reset would wipe the screen every time an application resets the terminal.
     */
    fun softReset() {
        pen = Pen.Default
        // Only this screen's slot: the main screen's is what `?1049l` will need on the way out.
        if (modes.alternateScreen) savedAlternate = null else savedMain = null
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        resetCharsets()
        // Which screen is showing is not a setting DECSTR restores. Clearing the flag without
        // swapping the buffers back left the session stuck on the alternate screen, since the
        // application's own `?1049l` then looked like a no-op.
        modes = TerminalModes(alternateScreen = modes.alternateScreen)
        cursorVisible = true
        observer.modesChanged(modes)
        log.i("softReset")
    }

    fun reset() {
        pen = Pen.Default
        cursorRow = 0
        cursorColumn = 0
        cursorVisible = true
        savedMain = null
        savedAlternate = null
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        resetCharsets()
        lastPrinted = -1
        if (modes.alternateScreen) switchAlternateScreen(enabled = false, saveRestoreCursor = false)
        modes = TerminalModes()
        buffer.clearScreen(pen)
        // A reset is the one erase that reaches into scrollback for images: the client that put
        // them there is gone, and there is no command left that could take them down.
        buffer.removePlacements { true }
        graphics.clear()
        parser.reset()
        observer.modesChanged(modes)
        observer.invalidate()
    }

    private fun resetCharsets() {
        g0LineDrawing = false
        g1LineDrawing = false
        shiftedOut = false
    }

    /** The visible screen as text, for tests and for copy-all style operations. */
    fun screenText(): String = buffer.snapshotText(0, rows)

    private data class SavedCursor(
        val row: Int,
        val column: Int,
        val pen: Pen,
        val g0LineDrawing: Boolean,
        val g1LineDrawing: Boolean,
        val shiftedOut: Boolean,
    )

    companion object {
        private const val TAB_WIDTH = 8

        private const val MAX_TITLE_LENGTH = 1024

        private const val LINE_DRAWING_FIRST = 0x5F
        private const val LINE_DRAWING_LAST = 0x7E

        /** DEC Special Graphics for `_` through `~`, as xterm draws them. */
        private const val LINE_DRAWING = "\u00A0◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        /** Cells on one axis of a placement. Far beyond any screen, and far short of an Int. */
        private const val MAX_PLACEMENT_SPAN = 4096

        /** XTMODKEYS resource number for "other" keys. */
        private const val MODIFY_OTHER_KEYS = 4

        /**
         * VT220 with ANSI colour. Deliberately short: advertising capabilities that are not
         * implemented (selective erase, DRCS) invites applications to use them.
         */
        private val DEVICE_ATTRIBUTES = "\u001B[?62;22c".toByteArray(Charsets.US_ASCII)
        private val STATUS_OK = "\u001B[0n".toByteArray(Charsets.US_ASCII)
    }
}
