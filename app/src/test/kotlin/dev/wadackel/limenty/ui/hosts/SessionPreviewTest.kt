package dev.wadackel.limenty.ui.hosts

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.offlineSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What a host row costs while the session behind it is printing.
 *
 * Every update takes the terminal lock and builds seven strings on the thread drawing the list,
 * so the interval is the whole point; the second assertion is there because a limit that never
 * shows the last screen would be no better than not updating at all.
 */
class SessionPreviewTest {
    @Test
    fun `a burst of output updates the preview no more often than the interval`() = runTest {
        val session = offlineSession()
        val updates = mutableListOf<List<String>>()
        backgroundScope.launch { session.previewLines().collect { updates += it } }
        runCurrent()

        // A second of output at forty lines a second, against the interval the app itself uses.
        repeat(40) { line ->
            session.emulator.write("line $line\r\n")
            advanceTimeBy(25)
        }
        advanceTimeBy(PREVIEW_INTERVAL_MILLIS)
        runCurrent()

        assertThat(updates.size).isAtMost(5)
        assertThat(updates.size).isAtLeast(2)
    }

    @Test
    fun `the screen the output ended on is shown once it stops`() = runTest {
        val session = offlineSession()
        val updates = mutableListOf<List<String>>()
        backgroundScope.launch { session.previewLines().collect { updates += it } }
        runCurrent()

        repeat(40) { line ->
            session.emulator.write("line $line\r\n")
            advanceTimeBy(25)
        }
        advanceTimeBy(PREVIEW_INTERVAL_MILLIS)
        runCurrent()

        assertThat(updates.last().last()).isEqualTo("line 39")
    }

    /**
     * The gap itself, not the number of updates.
     *
     * A count can be met by a flow that emits everything at once and then stops, which is the
     * shape the pause was added to prevent.
     */
    @Test
    fun `consecutive updates are at least the interval apart`() = runTest {
        val session = offlineSession()
        val emittedAt = mutableListOf<Long>()
        backgroundScope.launch { session.previewLines().collect { emittedAt += currentTime } }
        runCurrent()

        repeat(40) { line ->
            session.emulator.write("line $line\r\n")
            advanceTimeBy(25)
        }
        advanceTimeBy(PREVIEW_INTERVAL_MILLIS)
        runCurrent()

        assertThat(emittedAt.size).isAtLeast(2)
        emittedAt.zipWithNext { earlier, later ->
            assertThat(later - earlier).isAtLeast(PREVIEW_INTERVAL_MILLIS)
        }
    }
}
