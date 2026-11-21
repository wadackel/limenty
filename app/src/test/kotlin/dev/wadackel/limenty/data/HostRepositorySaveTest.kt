package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the screen is told when a save does not reach the disk, and what is left on the disk when
 * one does not finish. There is no Keystore under Robolectric, so every password here fails to
 * store — which is the case the outcome exists to describe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HostRepositorySaveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * A real scope rather than the test's own: the repository reads on [Dispatchers.IO], and the
     * point of every reload below is that the read actually happened.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stopReading() {
        scope.cancel()
    }

    private fun repository() = HostRepository(context, CredentialStore(context), scope)

    /** The ids a freshly built repository finds on disk, once it has finished looking. */
    private suspend fun storedIds(): List<String> {
        val reloaded = repository()
        reloaded.awaitLoaded()
        return reloaded.hosts.value.orEmpty().map { it.id }
    }

    private fun host(id: String = "h1", name: String = "MacBook") = HostProfile(
        id = id,
        name = name,
        hostname = "$name.example.test",
        username = "someone",
    )

    /**
     * Takes away the right to create the temporary file the atomic write needs.
     *
     * Not by occupying the temporary name, which is what this used to do: the write deletes that
     * name on its way out of the failure, so the injection undid itself and only ever caught the
     * first attempt. A directory that cannot be written to catches every attempt and leaves what
     * is already stored where it is.
     */
    private fun blockWrites() {
        check(context.filesDir.setWritable(false)) {
            "could not take write permission from ${context.filesDir}"
        }
        // A read-only directory refuses by throwing as readily as by answering false, and a
        // container running as root refuses in neither way — which is the case worth failing on.
        val probe = File(context.filesDir, "probe.tmp")
        val created = runCatching { probe.createNewFile() }.getOrDefault(false)
        if (created) probe.delete()
        check(!created) { "writes still succeed with the directory marked read-only" }
    }

    private fun allowWrites() {
        context.filesDir.setWritable(true)
    }

    @After
    fun restoreWrites() = allowWrites()

    @Test
    fun `a host that reached the disk is there again after a reload`() = runTest {
        val outcome = repository().upsert(host(), password = null)

        assertThat(outcome).isEqualTo(SaveOutcome.Saved)
        assertThat(storedIds()).containsExactly("h1")
    }

    @Test
    fun `a host found on the network is still one after a reload`() = runTest {
        repository().upsert(host().copy(hostname = "Someone’s Mac", lan = true), password = null)

        val reloaded = repository()
        reloaded.awaitLoaded()

        assertThat(reloaded.hosts.value.orEmpty().single().lan).isTrue()
    }

    @Test
    fun `a host saved before the network existed reads back as an ordinary one`() = runTest {
        File(context.filesDir, "hosts.json").writeText(
            """[{"id":"h1","name":"","hostname":"10.0.2.2","username":"someone","port":22}]""",
        )

        val reloaded = repository()
        reloaded.awaitLoaded()

        assertThat(reloaded.hosts.value.orEmpty().single().lan).isFalse()
    }

    @Test
    fun `a save that cannot be written is reported as a failure`() = runTest {
        blockWrites()
        val hosts = repository()

        val outcome = hosts.upsert(host(), password = null)

        assertThat(outcome).isInstanceOf(SaveOutcome.Failed::class.java)
    }

    @Test
    fun `a save that cannot be written leaves nothing behind in memory either`() = runTest {
        blockWrites()
        val hosts = repository()

        hosts.upsert(host(), password = null)

        assertThat(hosts.hosts.value.orEmpty()).isEmpty()
        assertThat(storedIds()).isEmpty()
    }

    @Test
    fun `a failed save leaves the hosts already on disk readable`() = runTest {
        repository().upsert(host("h1", "First"), password = null)
        blockWrites()

        val hosts = repository()
        hosts.upsert(host("h2", "Second"), password = null)
        allowWrites()

        assertThat(storedIds()).containsExactly("h1")
    }

    @Test
    fun `a password the device refuses to keep still saves the host`() = runTest {
        val outcome = repository().upsert(host(), password = "synthetic")

        assertThat(outcome).isInstanceOf(SaveOutcome.PasswordNotStored::class.java)
        assertThat(storedIds()).containsExactly("h1")
    }

    @Test
    fun `a delete that cannot be written leaves the host in the list`() = runTest {
        repository().upsert(host(), password = null)
        blockWrites()

        val hosts = repository()
        val outcome = hosts.delete("h1")
        allowWrites()

        assertThat(outcome.isFailure).isTrue()
        assertThat(hosts.hosts.value.orEmpty().map { it.id }).containsExactly("h1")
        assertThat(storedIds()).containsExactly("h1")
    }

    @Test
    fun `a password the device refuses to keep is reported to the caller`() = runTest {
        val hosts = repository()
        hosts.upsert(host(), password = null)

        val outcome = hosts.updatePassword("h1", "proven-at-the-prompt")

        assertThat(outcome.isFailure).isTrue()
    }

    /**
     * The injection above catches the second attempt as well as the first, which is what the
     * previous one could not do.
     */
    @Test
    fun `a save that cannot be written fails again the next time it is tried`() = runTest {
        val hosts = repository()
        hosts.upsert(host("h1", "First"), password = null)
        blockWrites()

        val first = hosts.upsert(host("h2", "Second"), password = null)
        val second = hosts.upsert(host("h3", "Third"), password = null)
        allowWrites()

        assertThat(first).isInstanceOf(SaveOutcome.Failed::class.java)
        assertThat(second).isInstanceOf(SaveOutcome.Failed::class.java)
        assertThat(storedIds()).containsExactly("h1")
    }
}
