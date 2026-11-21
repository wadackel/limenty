package dev.wadackel.limenty.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A report has to be enough to work out what went wrong after the fact, on a device
 * that is not attached to a debugger. That means the events leading up to it, what was running,
 * and — since a terminal carries passwords — nothing that was typed.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "diagnostics")
    private lateinit var store: DiagnosticsStore

    @Before
    fun setUp() {
        AppLogging.install(context)
        directory.deleteRecursively()
        store = DiagnosticsStore(context)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aReportCarriesTheEventsThatCameBeforeIt() {
        TermLog.logger(LogTags.SESSION).i("something.happened", "host" to "example")

        val report = store.write("manual")!!.readText()

        assertThat(report).contains("something.happened")
        assertThat(report).contains("host=example")
    }

    @Test
    fun aReportSaysWhatItWasRunningOn() {
        val report = store.write("manual")!!.readText()

        assertThat(report).contains("reason=manual")
        assertThat(report).contains("device=")
        assertThat(report).contains("sdk=")
        assertThat(report).contains("version=")
        assertThat(report).contains("capturedAt=")
    }

    @Test
    fun aCrashReportCarriesTheStackThatCausedIt() {
        val cause = IllegalStateException("the thing that broke")

        val report = store.write("crash", cause)!!.readText()

        assertThat(report).contains("the thing that broke")
        assertThat(report).contains("IllegalStateException")
    }

    /** A terminal log must never become the place a password ends up on disk. */
    @Test
    fun aReportDoesNotCarryWhatWasTyped() {
        TermLog.logger(LogTags.SSH).i(
            "connect.start",
            "host" to "example",
            "password" to dev.wadackel.limenty.core.common.log.Redacted("hunter2"),
        )

        val report = store.write("manual")!!.readText()

        assertThat(report).doesNotContain("hunter2")
        assertThat(report).contains("redacted")
    }

    @Test
    fun reportsAreFoundAgainAfterBeingWritten() {
        store.write("first")
        store.write("second")

        assertThat(DiagnosticsStore(context).files()).hasSize(2)
    }

    /** Device storage, not an archive: the oldest go once there are enough of them. */
    @Test
    fun oldReportsAreDroppedRatherThanAccumulating() {
        repeat(14) { index -> store.write("run$index") }

        val kept = store.files()
        assertThat(kept.size).isAtMost(10)
        assertThat(kept.last().name).contains("run13")
    }

    @Test
    fun theReasonIsVisibleInTheFileNameSoOneCanBePickedOutWithoutOpeningIt() {
        val file = store.write("connection-lost")!!

        assertThat(file.name).contains("connection-lost")
        assertThat(file.name).endsWith(".log")
    }
}
