package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What a write does while the first read is still in flight.
 *
 * Both stores publish a value and then write the whole of it back, so a mutation that ran before
 * the file had been read would persist what it believed the device held — nothing — and take the
 * real contents with it. Real time rather than virtual: the writes run on [Dispatchers.IO] and the
 * question is whether they reach the disk, not when a scheduler says they did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StartupLoadOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val readers = mutableListOf<ExecutorService>()

    @After
    fun stopReading() {
        scope.cancel()
        readers.forEach { it.shutdownNow() }
    }

    /**
     * A repository whose first read cannot start until the returned latch is counted down.
     *
     * The single worker is occupied by a task that blocks on the latch, so the read queues behind
     * it. A test dispatcher was tried first and does not work here: the read hops to
     * [Dispatchers.IO] and its resumption comes back to the dispatcher, which then needs advancing
     * again from a thread that is itself waiting on the result.
     */
    private fun parkedRepository(): Pair<HostRepository, CountDownLatch> {
        val (scopeForRead, release) = parkedScope()
        return HostRepository(context, CredentialStore(context), scopeForRead) to release
    }

    private fun parkedSettings(): Triple<TerminalSettings, CountDownLatch, CoroutineScope> {
        val (scopeForRead, release) = parkedScope()
        return Triple(TerminalSettings(context, scopeForRead), release, scopeForRead)
    }

    /**
     * Waits for the writes a store has launched into [on].
     *
     * The stores write without offering a result, which is the behaviour the app wants and the
     * one thing a test seeding the disk cannot work with. Joining the scope's own children asks
     * the same question without adding an API that only a test would use.
     */
    private suspend fun awaitWrites(on: CoroutineScope) = withTimeout(10_000) {
        // Until the scope is quiet, not once: a deferred change starts one coroutine that starts
        // another to do the writing, so a single pass can finish before the write even exists.
        while (true) {
            val running = on.coroutineContext.job.children.filter { it.isActive }.toList()
            if (running.isEmpty()) return@withTimeout
            running.forEach { it.join() }
        }
    }

    private fun parkedScope(): Pair<CoroutineScope, CountDownLatch> {
        val reader = Executors.newSingleThreadExecutor().also(readers::add)
        val release = CountDownLatch(1)
        reader.execute { release.await() }
        return CoroutineScope(reader.asCoroutineDispatcher()) to release
    }

    private fun host(id: String, name: String) = HostProfile(
        id = id,
        name = name,
        hostname = "$name.example.test",
        username = "someone",
    )

    private suspend fun storedIds(): List<String> {
        val reloaded = HostRepository(context, CredentialStore(context), scope)
        reloaded.awaitLoaded()
        return reloaded.hosts.value.orEmpty().map { it.id }
    }

    @Test
    fun `a save issued before the first load completes keeps the hosts already on disk`() = runBlocking<Unit> {
        HostRepository(context, CredentialStore(context), scope)
            .upsert(host("h1", "First"), password = null)

        val (hosts, release) = parkedRepository()

        val save = async(Dispatchers.IO) { hosts.upsert(host("h2", "Second"), password = null) }
        assertThat(withTimeoutOrNull(500) { save.await() }).isNull()

        release.countDown()
        assertThat(save.await()).isEqualTo(SaveOutcome.Saved)
        assertThat(storedIds()).containsExactly("h1", "h2")
    }

    @Test
    fun `a delete issued before the first load completes removes only what it names`() = runBlocking<Unit> {
        val seeding = HostRepository(context, CredentialStore(context), scope)
        seeding.upsert(host("h1", "First"), password = null)
        seeding.upsert(host("h2", "Second"), password = null)

        val (hosts, release) = parkedRepository()

        val removal = async(Dispatchers.IO) { hosts.delete("h1") }
        assertThat(withTimeoutOrNull(500) { removal.await() }).isNull()

        release.countDown()
        assertThat(removal.await().isSuccess).isTrue()
        assertThat(storedIds()).containsExactly("h2")
    }

    /**
     * The change is held until the file has been read and then applied on top of it, so a setting
     * the change does not name is still the one the device had.
     */
    @Test
    fun `a settings change made before the first load completes is applied on top of what was stored`() = runBlocking<Unit> {
        val seeded = TerminalSettings(context, scope)
        seeded.awaitLoaded()
        seeded.update { it.copy(paletteName = "Nord", keepScreenAwake = false) }
        awaitWrites(scope)

        val (settings, release, settingsScope) = parkedSettings()
        settings.update { it.copy(keepScreenAwake = true) }

        release.countDown()
        settings.awaitLoaded()
        awaitWrites(settingsScope)

        val reloaded = TerminalSettings(context, scope)
        reloaded.awaitLoaded()
        assertThat(reloaded.values.value?.paletteName).isEqualTo("Nord")
        assertThat(reloaded.values.value?.keepScreenAwake).isTrue()
    }
}
