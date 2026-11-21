package dev.wadackel.limenty.terminal

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.graphics.ImageData
import dev.wadackel.limenty.core.terminal.graphics.TerminalImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * How many conversions the cache allows to run at once.
 *
 * Real threads rather than the scheduler the other cache tests use: those run the launch and the
 * decode on one test dispatcher, so nothing there ever overlaps and a ceiling would look
 * respected whether or not one existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GraphicsDecodeLimitTest {
    private val pools = mutableListOf<ExecutorService>()

    @After
    fun stopPools() {
        pools.forEach { it.shutdownNow() }
    }

    /**
     * Counts how many decodes dispatched to [delegate] are in flight at the same moment.
     *
     * A decode is the coroutine `withContext` runs here, counted from its first block until its
     * job completes. Counting the blocks themselves overlapped two of them: the one that finished
     * a decode still had to return after resuming its caller, and by then the caller had handed
     * back the permit and the next decode had started.
     */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val active = AtomicInteger()
        private val started = ConcurrentHashMap.newKeySet<Job>()
        val peak = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                val job = context[Job]
                if (job != null && started.add(job)) {
                    val running = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, running) }
                    job.invokeOnCompletion { active.decrementAndGet() }
                }
                block.run()
            }
        }
    }

    private fun image(serial: Long) = TerminalImage(
        id = serial.toInt(),
        width = SIDE,
        height = SIDE,
        data = ImageData.Rgba(ByteArray(SIDE * SIDE * 4) { -1 }),
        serial = serial,
    )

    private fun pool(threads: Int) = Executors.newFixedThreadPool(threads).also(pools::add)

    private fun peakConcurrentDecodes(cacheOf: (CoroutineScope, CoroutineDispatcher, () -> Unit) -> GraphicsBitmapCache): Int = runBlocking {
        val decodeOn = CountingDispatcher(pool(IMAGES).asCoroutineDispatcher())
        val drawing = CoroutineScope(pool(1).asCoroutineDispatcher())
        val decoded = CountDownLatch(IMAGES)
        val cache = cacheOf(drawing, decodeOn) { decoded.countDown() }

        // On the scope's own thread, as the renderer asks from the one that draws.
        withContext(drawing.coroutineContext) {
            (1..IMAGES).forEach { cache.bitmapFor(image(it.toLong())) }
        }

        assertThat(decoded.await(60, TimeUnit.SECONDS)).isTrue()
        drawing.cancel()
        decodeOn.peak.get()
    }

    @Test
    fun `no more images are converted at once than the cache allows`() {
        val peak = peakConcurrentDecodes { scope, decodeOn, onDecoded ->
            GraphicsBitmapCache(scope, decodeOn, onDecoded = onDecoded)
        }

        assertThat(peak).isAtMost(2)
    }

    @Test
    fun `a wider ceiling is what actually widens the fan-out`() {
        val peak = peakConcurrentDecodes { scope, decodeOn, onDecoded ->
            GraphicsBitmapCache(scope, decodeOn, concurrentDecodes = IMAGES, onDecoded = onDecoded)
        }

        assertThat(peak).isGreaterThan(2)
    }

    private companion object {
        /** Large enough that a conversion lasts long enough to overlap another one. */
        const val SIDE = 512
        const val IMAGES = 8
    }
}
