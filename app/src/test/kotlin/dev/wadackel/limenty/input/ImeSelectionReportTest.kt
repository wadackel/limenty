package dev.wadackel.limenty.input

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.ImeEditBuffer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the terminal says where its cursor and composing region are.
 *
 * Both halves matter and each was learned from a failure. Saying nothing at all leaves an
 * accessibility service unable to tell that a conversion is open, and one deletes into the preedit
 * blind. Saying it mid-batch describes a state the IME is half way through setting, which Gboard
 * reads as the composition being taken from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImeSelectionReportTest {
    private lateinit var view: View
    private val buffer = ImeEditBuffer()
    private var reports = 0

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = View(ApplicationProvider.getApplicationContext())
        }
    }

    private fun connection() = TerminalInputConnection(
        view = view,
        buffer = buffer,
        onEvents = {},
        onComposingChanged = {},
        onKeyEvent = { false },
        onSelectionSettled = { reports++ },
    )

    @Test
    fun nothingIsReportedWhileTheImeIsStillMovingThings() {
        val connection = connection()

        connection.beginBatchEdit()
        connection.setComposingText("に", 1)
        connection.setComposingText("にほ", 1)
        connection.setComposingText("にほん", 1)

        assertThat(reports).isEqualTo(0)
    }

    @Test
    fun closingTheBatchReportsOnce() {
        val connection = connection()

        connection.beginBatchEdit()
        connection.setComposingText("に", 1)
        connection.setComposingText("にほん", 1)
        connection.endBatchEdit()

        assertThat(reports).isEqualTo(1)
    }

    /** The commit-then-compose batch is the one that cost a composition when answered midway. */
    @Test
    fun aCommitFollowedByAFreshCompositionIsOneReport() {
        val connection = connection()

        connection.beginBatchEdit()
        connection.commitText("日本", 1)
        connection.setComposingText("ご", 1)
        connection.endBatchEdit()

        assertThat(reports).isEqualTo(1)
        assertThat(buffer.composing.text).isEqualTo("ご")
    }

    @Test
    fun onlyTheOutermostBatchReports() {
        val connection = connection()

        connection.beginBatchEdit()
        connection.beginBatchEdit()
        connection.setComposingText("に", 1)
        connection.endBatchEdit()
        assertThat(reports).isEqualTo(0)

        connection.endBatchEdit()
        assertThat(reports).isEqualTo(1)
    }

    /** Outside a batch there is nothing to wait for, and waiting would delay every keystroke. */
    @Test
    fun aChangeOutsideABatchIsReportedAtOnce() {
        val connection = connection()

        connection.setComposingText("に", 1)

        assertThat(reports).isEqualTo(1)
    }

    /** A batch the IME opened and closed without touching anything has nothing to report. */
    @Test
    fun anEmptyBatchReportsNothing() {
        val connection = connection()

        connection.beginBatchEdit()
        connection.endBatchEdit()

        assertThat(reports).isEqualTo(0)
    }

    /** An unbalanced close from a confused IME must not leave the count below zero. */
    @Test
    fun aStrayCloseDoesNotUnbalanceTheCount() {
        val connection = connection()

        connection.endBatchEdit()
        connection.beginBatchEdit()
        connection.setComposingText("に", 1)
        assertThat(reports).isEqualTo(0)

        connection.endBatchEdit()
        assertThat(reports).isEqualTo(1)
    }
}
