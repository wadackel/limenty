package dev.wadackel.limenty.terminal

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.graphics.GraphicsStore
import dev.wadackel.limenty.core.terminal.graphics.ImageData
import dev.wadackel.limenty.core.terminal.graphics.TerminalImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the pixels are produced, relative to the frame that asked for them.
 *
 * The decode used to happen inside `onDraw`, under the lock the receiving pump writes the screen
 * with, so an image arriving stopped drawing and typing for as long as it took.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GraphicsBitmapCacheTest {
    private fun image(serial: Long, side: Int = 2) = TerminalImage(
        id = 1,
        width = side,
        height = side,
        data = ImageData.Rgba(ByteArray(side * side * 4) { -1 }),
        serial = serial,
    )

    private fun emptyStore() = GraphicsStore(isPlaced = { false }, onRemoved = {})

    @Test
    fun `the frame that first asks for an image does not decode it`() = runTest {
        var frames = 0
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) { frames++ }

        val onFirstAsk = cache.bitmapFor(image(serial = 1))

        assertThat(onFirstAsk).isNull()
        assertThat(frames).isEqualTo(0)
    }

    @Test
    fun `the image is there, and a frame asked for, once the decode finishes`() = runTest {
        var frames = 0
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) { frames++ }
        val image = image(serial = 1)

        cache.bitmapFor(image)
        advanceUntilIdle()

        assertThat(cache.bitmapFor(image)).isNotNull()
        assertThat(frames).isEqualTo(1)
    }

    @Test
    fun `a decode is started once however many frames ask for it`() = runTest {
        var frames = 0
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) { frames++ }
        val image = image(serial = 1)

        repeat(5) { cache.bitmapFor(image) }
        advanceUntilIdle()

        assertThat(frames).isEqualTo(1)
    }

    /**
     * A client may delete or re-send an image while its pixels are still being decoded. Storing
     * the late result would put a picture back on screen after it was asked to go.
     */
    @Test
    fun `pixels that finish decoding after their image is gone are discarded`() = runTest {
        var frames = 0
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) { frames++ }
        val image = image(serial = 1)

        cache.bitmapFor(image)
        cache.prune(emptyStore())
        advanceUntilIdle()

        assertThat(frames).isEqualTo(0)
        assertThat(cache.bitmapFor(image)).isNull()
    }

    /**
     * A view leaving the hierarchy drops what it was in the middle of and keeps what it has.
     */
    @Test
    fun `dropping work in flight keeps the pixels already decoded`() = runTest {
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) {}
        val drawn = image(serial = 1)
        cache.bitmapFor(drawn)
        advanceUntilIdle()
        val pending = image(serial = 2)
        cache.bitmapFor(pending)

        cache.dropInFlight()
        advanceUntilIdle()

        assertThat(cache.bitmapFor(drawn)).isNotNull()
    }

    /** The same id sent again is different pixels, which is why the cache keys on the serial. */
    @Test
    fun `re-sending an image draws the pixels that came with it`() = runTest {
        val cache = GraphicsBitmapCache(this, StandardTestDispatcher(testScheduler)) {}
        val first = image(serial = 1)
        cache.bitmapFor(first)
        advanceUntilIdle()
        val firstPixels = cache.bitmapFor(first)!!.getPixel(0, 0)

        val resent = TerminalImage(
            id = first.id,
            width = first.width,
            height = first.height,
            data = ImageData.Rgba(
                ByteArray(first.width * first.height * 4) { index ->
                    if (index % 4 == 3) -1 else 0
                },
            ),
            serial = 2,
        )
        cache.bitmapFor(resent)
        advanceUntilIdle()

        assertThat(cache.bitmapFor(resent)!!.getPixel(0, 0)).isNotEqualTo(firstPixels)
    }
}
