package dev.wadackel.limenty.core.terminal.graphics

/**
 * One kitty graphics command: the `key=value` control data of an APC `G` string plus its payload.
 *
 * Every key the protocol defines is read, and the ones this terminal does not act on are still
 * checked so that a command can be rejected with a reason rather than acted on wrongly. Unknown
 * keys are skipped, as the protocol asks, so a newer client keeps working.
 *
 * Error messages are fixed text: a response travels back down the user's input stream, so a
 * remote string echoed into one would land in the shell as if the user had typed it.
 *
 * Ids are 32-bit unsigned on the wire; they are held in an [Int] carrying the same bits, which
 * keeps identity intact and only needs `toUInt()` at the point of writing a response.
 */
class GraphicsCommand private constructor(
    val action: Char,
    val format: Int,
    val width: Int,
    val height: Int,
    val compressed: Boolean,
    val more: Boolean,
    val imageId: Int,
    val placementId: Int,
    val quiet: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val columns: Int,
    val rows: Int,
    val keepCursor: Boolean,
    val deleteTarget: Char,
    /** Base64 text as it arrived; decoding is the receiver's business, and it may accumulate first. */
    val payload: ByteArray,
    /** Why the command cannot be acted on, as the `CODE:message` a response carries, or null. */
    val error: String?,
) {
    val hasImageId: Boolean get() = imageId != 0

    /** A later chunk of a transfer may change the quiet level; the rest is the first chunk's. */
    fun withQuiet(quiet: Int): GraphicsCommand = if (quiet == this.quiet) {
        this
    } else {
        GraphicsCommand(
            action = action,
            format = format,
            width = width,
            height = height,
            compressed = compressed,
            more = more,
            imageId = imageId,
            placementId = placementId,
            quiet = quiet,
            sourceX = sourceX,
            sourceY = sourceY,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            columns = columns,
            rows = rows,
            keepCursor = keepCursor,
            deleteTarget = deleteTarget,
            payload = payload,
            error = error,
        )
    }

    companion object {
        /**
         * Reads an APC body. Returns null when the body is not a graphics command at all, which
         * is the signal to ignore it silently rather than answer it.
         */
        fun parse(body: ByteArray): GraphicsCommand? {
            if (body.isEmpty() || body[0] != 'G'.code.toByte()) return null

            var separator = body.size
            for (index in 1 until body.size) {
                if (body[index] == ';'.code.toByte()) {
                    separator = index
                    break
                }
            }
            val control = String(body, 1, separator - 1, Charsets.US_ASCII)
            val payload = if (separator < body.size) body.copyOfRange(separator + 1, body.size) else EMPTY

            var action = 't'
            var transmission = 'd'
            var format = 32
            var width = 0
            var height = 0
            var compressed = false
            var more = false
            var imageId = 0
            var imageNumber = 0
            var placementId = 0
            var quiet = 0
            var sourceX = 0
            var sourceY = 0
            var sourceWidth = 0
            var sourceHeight = 0
            var columns = 0
            var rows = 0
            var keepCursor = false
            var deleteTarget = 'a'
            var unicodePlacement = false
            var error: String? = null

            fun fail(message: String) {
                if (error == null) error = "EINVAL:$message"
            }

            for (pair in control.split(',')) {
                if (pair.isEmpty()) continue
                val equals = pair.indexOf('=')
                if (equals != 1) {
                    fail("malformed key")
                    continue
                }
                val key = pair[0]
                val value = pair.substring(2)
                when (key) {
                    'a' -> action = value.singleCharOr { fail("bad action") }
                    't' -> transmission = value.singleCharOr { fail("bad transmission") }
                    'o' -> if (value == "z") compressed = true else fail("unsupported compression")
                    'd' -> deleteTarget = value.singleCharOr { fail("bad delete target") }
                    else -> {
                        val number = value.toLongOrNull()
                        // Only ids use the full unsigned range; every other value is a size or a
                        // count, and letting it wrap through toInt() would make a negative one.
                        val limit = if (key == 'i' || key == 'p') UINT_MAX else Int.MAX_VALUE.toLong()
                        if (number == null || (number < 0 && key != 'z') || number > limit) {
                            fail("bad value")
                            continue
                        }
                        val int = number.toInt()
                        when (key) {
                            'f' -> format = int
                            's' -> width = int
                            'v' -> height = int
                            'm' -> more = int != 0
                            'i' -> imageId = int
                            'I' -> imageNumber = int
                            'p' -> placementId = int
                            'q' -> quiet = int
                            'x' -> sourceX = int
                            'y' -> sourceY = int
                            'w' -> sourceWidth = int
                            'h' -> sourceHeight = int
                            'c' -> columns = int
                            'r' -> rows = int
                            'C' -> keepCursor = int != 0
                            'U' -> unicodePlacement = int != 0
                            else -> Unit
                        }
                    }
                }
            }

            when {
                action !in SUPPORTED_ACTIONS -> fail("unsupported action")
                imageNumber != 0 -> fail("image numbers are not supported")
                unicodePlacement -> fail("unicode placeholders are not supported")
                transmission != 'd' && action in TRANSMITTING_ACTIONS -> fail("only direct transmission is supported")
                format != 24 && format != 32 && format != 100 -> fail("unsupported format $format")
                action == 'q' && imageId == 0 -> fail("a query needs an image id")
            }

            return GraphicsCommand(
                action = action,
                format = format,
                width = width,
                height = height,
                compressed = compressed,
                more = more,
                imageId = imageId,
                placementId = placementId,
                quiet = quiet,
                sourceX = sourceX,
                sourceY = sourceY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                columns = columns,
                rows = rows,
                keepCursor = keepCursor,
                deleteTarget = deleteTarget,
                payload = payload,
                error = error,
            )
        }

        private inline fun String.singleCharOr(onError: () -> Unit): Char {
            if (length != 1) {
                onError()
                return ' '
            }
            return this[0]
        }

        private const val UINT_MAX = 0xFFFF_FFFFL
        private const val SUPPORTED_ACTIONS = "tTpdq"
        private const val TRANSMITTING_ACTIONS = "tTq"
        private val EMPTY = ByteArray(0)
    }
}
