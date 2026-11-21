package dev.wadackel.limenty.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.graphics.GraphicsStore
import dev.wadackel.limenty.core.terminal.graphics.ImageData
import dev.wadackel.limenty.core.terminal.graphics.TerminalImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Decoded pixels for the images a [GraphicsStore] holds, keyed by the image's serial.
 *
 * The store keeps bytes because it lives in a module that cannot see [Bitmap]; this is the
 * other half, on the drawing side. Keying on the serial rather than the id means a client that
 * re-sends an id gets fresh pixels instead of the cached ones the old id had.
 *
 * Everything except the decode itself runs on [scope]'s thread, which is the one that draws, so
 * the two maps below need no locking of their own.
 */
class GraphicsBitmapCache(
    private val scope: CoroutineScope,
    /**
     * Where a decode runs. Tests substitute one they can advance by hand.
     *
     * Never a dispatcher that runs on [scope]'s own thread: the launch below begins inline while
     * the caller still holds the terminal lock, and only the hop to another thread lets go of it.
     */
    private val decodeOn: CoroutineDispatcher = Dispatchers.Default,
    /**
     * How many images may be converted at once.
     *
     * [Dispatchers.Default] is as wide as the device has cores, and each conversion holds a colour
     * int array the size of the image plus the bitmap it becomes. At the protocol's 4096-square
     * ceiling that is 64 MiB apiece, so an unbounded fan-out turns a screenful of images into
     * hundreds of megabytes for as long as they take.
     */
    concurrentDecodes: Int = DEFAULT_CONCURRENT_DECODES,
    private val onDecoded: () -> Unit,
) {
    private val log = TermLog.logger(LogTags.GRAPHICS)
    private val bitmaps = HashMap<Long, Bitmap?>()
    private val decoding = HashSet<Long>()
    private val permits = Semaphore(concurrentDecodes)

    /**
     * The decoded image, or null when there is nothing to draw yet.
     *
     * A miss starts a decode and returns at once rather than decoding here. At the protocol's
     * 4096-square ceiling an image is 64 MiB of source bytes, another 64 MiB of colour ints and a
     * bitmap of the same size again, and producing all three inside `onDraw` held the frame and
     * the terminal lock the receiving pump writes under — so a picture arriving stopped both
     * drawing and typing. The image appears on the frame after the decode finishes, which
     * [onDecoded] asks for.
     *
     * A failure is remembered rather than retried, which is what keeps a broken image from being
     * decoded and logged on every frame; `getOrPut` would not do that, because it reads a stored
     * null as absent.
     */
    fun bitmapFor(image: TerminalImage): Bitmap? {
        if (bitmaps.containsKey(image.serial)) return bitmaps[image.serial]
        if (!decoding.add(image.serial)) return null
        scope.launch {
            val decoded = permits.withPermit {
                // The store can drop the image while this waits its turn, and `prune` has then
                // already forgotten the serial. Converting it now would spend the very memory the
                // permit exists to bound on pixels nothing is going to draw.
                if (image.serial !in decoding) return@launch
                withContext(decodeOn) { decode(image) }
            }
            // The image can be deleted or replaced while its pixels are being decoded, and
            // `prune` has then already forgotten the serial. Keeping the result would put a
            // picture back on screen after the client asked for it to go.
            if (!decoding.remove(image.serial)) return@launch
            bitmaps[image.serial] = decoded
            onDecoded()
        }
        return null
    }

    /**
     * Forgets everything the store no longer has, so the cache never outgrows the store's quota.
     *
     * Dropped bitmaps are left to the garbage collector rather than recycled: this runs inside
     * `onDraw`, and the previous frame's display list may still be reading the very bitmap that
     * has just gone out of the store.
     */
    fun prune(store: GraphicsStore) {
        if (bitmaps.isEmpty() && decoding.isEmpty()) return
        val live = store.all.mapTo(HashSet()) { it.serial }
        bitmaps.keys.retainAll(live)
        decoding.retainAll(live)
    }

    fun clear() {
        bitmaps.clear()
        decoding.clear()
    }

    /**
     * Forgets the conversions in flight and keeps the pixels already produced.
     *
     * For a view leaving the hierarchy, whose scope is deliberately not cancelled with it. The
     * serials are dropped rather than kept because their coroutines have been cancelled and will
     * never remove themselves; left behind they would read as "already being decoded" and the
     * images they stand for would never be asked for again.
     */
    fun dropInFlight() {
        decoding.clear()
    }

    private companion object {
        /**
         * Two rather than one: a single permit makes a second image wait for the whole of the
         * first, which is what drawing used to do, and rather than the core count because the
         * memory a decode holds costs more here than the time it takes.
         */
        const val DEFAULT_CONCURRENT_DECODES = 2
    }

    private fun decode(image: TerminalImage): Bitmap? {
        val bitmap = try {
            when (val data = image.data) {
                is ImageData.Png -> BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size)
                is ImageData.Rgba -> fromRgba(data.bytes, image.width, image.height)
            }
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bitmap == null) log.w("reject", "reason" to "decode", "id" to image.id.toUInt())
        return bitmap
    }

    /**
     * Straight-alpha RGBA rows into an ARGB_8888 bitmap. Going through colour ints rather than
     * copying the buffer lets the platform premultiply, which a raw copy would silently skip.
     */
    private fun fromRgba(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        if (bytes.size != width * height * 4) return null
        val colors = IntArray(width * height)
        var offset = 0
        for (index in colors.indices) {
            val r = bytes[offset].toInt() and 0xFF
            val g = bytes[offset + 1].toInt() and 0xFF
            val b = bytes[offset + 2].toInt() and 0xFF
            val a = bytes[offset + 3].toInt() and 0xFF
            colors[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
            offset += 4
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }
}
