package dev.wadackel.limenty.core.terminal.graphics

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Pixels as the renderer will want them: either a PNG to decode, or straight RGBA rows. */
sealed class ImageData {
    abstract val bytes: ByteArray

    class Png(override val bytes: ByteArray) : ImageData()

    class Rgba(override val bytes: ByteArray) : ImageData()
}

/**
 * An image a client has transmitted. [serial] is unique across the life of the store, so a
 * renderer's decoded copy keyed on it can never be reused for a different image that happens to
 * take over the same id.
 */
class TerminalImage(
    val id: Int,
    val width: Int,
    val height: Int,
    val data: ImageData,
    val serial: Long,
) {
    /** What the image costs once decoded. */
    val pixelBytes: Long = width.toLong() * height * 4

    /**
     * What the quota charges: the decoded size or the bytes actually held, whichever is larger.
     * A PNG whose header claims one pixel can still carry megabytes behind it.
     */
    val retainedBytes: Long = maxOf(pixelBytes, data.bytes.size.toLong())
}

/** What handing a transmit command to the store produced. */
sealed class Transmission {
    /** More chunks are expected; nothing to answer yet. */
    object Pending : Transmission()

    /**
     * The transfer ended. [command] is the first chunk's, so it carries the id and the quiet
     * level a response needs; [image] is null for a query or a failure.
     */
    class Complete(val command: GraphicsCommand, val image: TerminalImage?, val error: String?) : Transmission()
}

/**
 * Image data for the kitty graphics protocol: transmission, storage and the answers owed.
 *
 * Placements are not here — they belong to the screen they were put on, which is why the store
 * asks [isPlaced] rather than counting. It only needs the answer at two moments: when deciding
 * what may be evicted, and when a clear has taken the last placement away.
 */
class GraphicsStore(
    private val isPlaced: (imageId: Int) -> Boolean,
    private val onRemoved: (imageId: Int) -> Unit,
    private val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
) {
    private val log = TermLog.logger(LogTags.GRAPHICS)

    private val images = LinkedHashMap<Int, TerminalImage>()
    private var usedBytes = 0L
    private var nextSerial = 1L
    private var pending: PendingTransfer? = null

    /** Images by id, oldest first. */
    val all: Collection<TerminalImage> get() = images.values

    fun image(id: Int): TerminalImage? = images[id]

    val isTransferring: Boolean get() = pending != null

    private class PendingTransfer(
        val first: GraphicsCommand,
        var quiet: Int,
        val decoded: ByteArrayOutputStream,
        val cap: Long,
    )

    /**
     * Feeds one `a=t`, `a=T` or `a=q` command. While a chunked transfer is open every one of those
     * continues it, whatever other keys it carries — that is how kitty reads a continuation, and
     * a client that sends `m=1` then only `m=` chunks depends on it.
     */
    fun receive(command: GraphicsCommand): Transmission {
        val open = pending
        if (open == null) {
            command.error?.let { return Transmission.Complete(command, null, it) }
            if (command.format != PNG) {
                // Checked before a single byte is buffered: the declared size is what bounds the
                // transfer, so a remote must not get to declare one the quota could never hold.
                rawSizeError(command)?.let {
                    log.w("reject", "id" to command.imageId.toUInt(), "reason" to it.substringBefore(':'))
                    return Transmission.Complete(command, null, it)
                }
            }
            // Deflate adds framing, so a compressed stream can legitimately be longer than the
            // pixels it holds; the expansion is bounded at inflate time instead.
            val cap = when {
                command.format == PNG -> MAX_IMAGE_BYTES
                command.compressed -> deflatedCeiling(declaredBytes(command))
                else -> declaredBytes(command)
            }
            val transfer = PendingTransfer(command, command.quiet, ByteArrayOutputStream(), cap)
            return continueTransfer(transfer, command)
        }
        if (command.quiet != 0) open.quiet = command.quiet
        return continueTransfer(open, command)
    }

    /** Drops a chunked transfer part way through, for a parser overflow or a command that ends it. */
    fun abortTransfer(reason: String) {
        val open = pending ?: return
        pending = null
        log.w("transfer.aborted", "id" to open.first.imageId.toUInt(), "reason" to reason)
    }

    private fun continueTransfer(transfer: PendingTransfer, chunk: GraphicsCommand): Transmission {
        val first = transfer.first
        val fail = { error: String ->
            pending = null
            log.w("reject", "id" to first.imageId.toUInt(), "reason" to error.substringBefore(':'))
            Transmission.Complete(first.withQuiet(transfer.quiet), null, error)
        }

        chunk.error?.let { return fail(it) }
        val decoded = try {
            Base64.getDecoder().decode(chunk.payload)
        } catch (e: IllegalArgumentException) {
            return fail("EINVAL:payload is not base64")
        }
        if (transfer.decoded.size() + decoded.size > transfer.cap) return fail("EFBIG:too much data")
        // Bytes in flight count against the quota as they arrive, so a store that is already full
        // cannot be made to hold a second image's worth on the side while a transfer stays open.
        if (!makeRoom(transfer.decoded.size().toLong() + decoded.size)) return fail("ENOSPC:image storage is full")
        transfer.decoded.write(decoded)

        if (chunk.more) {
            pending = transfer
            return Transmission.Pending
        }
        pending = null

        val command = first.withQuiet(transfer.quiet)
        val result = decode(command, transfer.decoded.toByteArray()).getOrElse { return fail(it.message ?: "EINVAL:bad image") }
        val (width, height, data) = result

        if (command.action == 'q') {
            log.i("query", "id" to command.imageId.toUInt(), "format" to command.format)
            return Transmission.Complete(command, null, null)
        }

        val image = TerminalImage(command.imageId, width, height, data, nextSerial++)
        if (image.retainedBytes > MAX_IMAGE_BYTES) return fail("EFBIG:image data is too large")
        // Re-sending an id replaces the image, and a placement of the old one would otherwise
        // keep drawing pixels the client has just told us to forget.
        images[command.imageId]?.let { remove(it) }
        if (!makeRoom(image.retainedBytes)) return fail("ENOSPC:image storage is full")

        images[command.imageId] = image
        usedBytes += image.retainedBytes
        log.i(
            "transmit",
            "id" to command.imageId.toUInt(),
            "format" to command.format,
            "width" to width,
            "height" to height,
            "usedMiB" to usedBytes / MIB,
        )
        return Transmission.Complete(command, image, null)
    }

    private fun decode(command: GraphicsCommand, raw: ByteArray): Result<Triple<Int, Int, ImageData>> {
        val bytes = if (command.compressed) inflate(raw, command).getOrElse { return Result.failure(it) } else raw
        return when (command.format) {
            PNG -> readPngHeader(bytes).map { (width, height) -> Triple(width, height, ImageData.Png(bytes)) }
            else -> {
                val expected = declaredBytes(command)
                if (bytes.size.toLong() != expected) return failure("ENODATA:expected $expected bytes, got ${bytes.size}")
                val rgba = if (command.format == 24) toRgba(bytes) else bytes
                Result.success(Triple(command.width, command.height, ImageData.Rgba(rgba)))
            }
        }
    }

    private fun inflate(raw: ByteArray, command: GraphicsCommand): Result<ByteArray> {
        val bound = if (command.format == PNG) MAX_IMAGE_BYTES else declaredBytes(command)
        val inflater = Inflater()
        inflater.setInput(raw)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                // Bounded so a few kilobytes of deflate cannot expand into a heap's worth of zeros.
                if (out.size() + count > bound) return failure("EFBIG:inflated data exceeds the declared size")
                out.write(chunk, 0, count)
            }
        } catch (e: DataFormatException) {
            return failure("EINVAL:deflate data is corrupt")
        } finally {
            inflater.end()
        }
        return Result.success(out.toByteArray())
    }

    /** Evicts what it may until [needed] more bytes fit beside what is stored; false if they never will. */
    private fun makeRoom(needed: Long): Boolean {
        fun fits() = usedBytes + needed <= quotaBytes && images.size < MAX_IMAGES
        if (fits()) return true
        // Oldest first, but never an image that is still on screen: taking one of those away
        // would blank a preview the client believes is showing, with q=2 hiding the reason.
        val evictable = images.values.filter { !isPlaced(it.id) }
        for (victim in evictable) {
            if (fits()) break
            remove(victim)
            log.i("evict", "id" to victim.id.toUInt(), "usedMiB" to usedBytes / MIB)
        }
        return fits()
    }

    private fun remove(image: TerminalImage) {
        images.remove(image.id)
        usedBytes -= image.retainedBytes
        onRemoved(image.id)
    }

    /** `a=d` with a capital target: the placements are already gone; the pixels follow. */
    fun delete(imageId: Int): Boolean {
        val image = images[imageId] ?: return false
        remove(image)
        log.i("delete", "id" to imageId.toUInt())
        return true
    }

    /**
     * After a screen clear took placements away: of the images they pointed at, those nothing
     * points at any more are not worth their pixels. Images never placed are left alone — a
     * client that transmits, then clears, then places would otherwise find its image gone.
     */
    fun release(candidates: Set<Int>) {
        candidates.mapNotNull { images[it] }.filter { !isPlaced(it.id) }.forEach { remove(it) }
    }

    /** For a reset, whose caller has already taken every placement down. */
    fun clear() {
        pending = null
        images.clear()
        usedBytes = 0
    }

    companion object {
        private const val PNG = 100
        private const val MIB = 1024L * 1024L

        /** A quarter of what a mid-range phone will lend the app, with the decoded copies on top. */
        const val DEFAULT_QUOTA_BYTES: Long = 64 * MIB

        const val MAX_IMAGE_SIDE = 4096
        private const val MAX_IMAGE_BYTES: Long = MAX_IMAGE_SIDE.toLong() * MAX_IMAGE_SIDE * 4

        /** Tiny images cost the quota almost nothing; this is what keeps their count honest. */
        private const val MAX_IMAGES = 256

        // `EFBIG` is kitty's own code for a transfer that is too large; it sits beside the four
        // the protocol document lists.
        private fun declaredBytes(command: GraphicsCommand): Long = command.width.toLong() * command.height * (if (command.format == 24) 3 else 4)

        /** Deflate's worst case is a stored block every 16 KiB plus a header, not a free-for-all. */
        private fun deflatedCeiling(bytes: Long): Long = bytes + bytes / 16383 * 5 + 64

        private fun rawSizeError(command: GraphicsCommand): String? = when {
            command.width <= 0 || command.height <= 0 -> "EINVAL:zero width or height"
            command.width > MAX_IMAGE_SIDE || command.height > MAX_IMAGE_SIDE -> "EFBIG:image exceeds ${MAX_IMAGE_SIDE}x$MAX_IMAGE_SIDE"
            else -> null
        }

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        private fun failure(message: String): Result<Nothing> = Result.failure(IllegalArgumentException(message))

        /** Only the signature and IHDR are read; whether the rest decodes is the renderer's problem. */
        private fun readPngHeader(bytes: ByteArray): Result<Pair<Int, Int>> {
            if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return failure("EINVAL:not a PNG")
            if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return failure("EINVAL:PNG without IHDR")
            val width = readInt(bytes, 16)
            val height = readInt(bytes, 20)
            if (width <= 0 || height <= 0) return failure("EINVAL:zero width or height")
            if (width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE) return failure("EFBIG:image exceeds ${MAX_IMAGE_SIDE}x$MAX_IMAGE_SIDE")
            return Result.success(width to height)
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

        private fun toRgba(rgb: ByteArray): ByteArray {
            val pixels = rgb.size / 3
            val rgba = ByteArray(pixels * 4)
            for (index in 0 until pixels) {
                rgba[index * 4] = rgb[index * 3]
                rgba[index * 4 + 1] = rgb[index * 3 + 1]
                rgba[index * 4 + 2] = rgb[index * 3 + 2]
                rgba[index * 4 + 3] = 0xFF.toByte()
            }
            return rgba
        }

        /**
         * The reply a command is owed, or null when the protocol says to stay silent: no id to
         * answer to, `q=1` on success, or `q=2` at all.
         */
        fun response(command: GraphicsCommand, error: String?): ByteArray? {
            if (!command.hasImageId) return null
            if (command.quiet >= 2 || (command.quiet == 1 && error == null)) return null
            val body = buildString {
                append("\u001B_Gi=").append(command.imageId.toUInt())
                if (command.placementId != 0) append(",p=").append(command.placementId.toUInt())
                append(';').append(error ?: "OK").append("\u001B\\")
            }
            return body.toByteArray(Charsets.US_ASCII)
        }
    }
}
