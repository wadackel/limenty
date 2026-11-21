package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.common.log.LogLevel
import dev.wadackel.limenty.core.common.log.RingBufferLogSink
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.transport.HostKeyInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which thread the storage work actually ran on.
 *
 * Asked through the app's own records rather than by instrumenting the stores: every record
 * carries the thread that produced it, precisely because a question like this one is hard to
 * answer any other way. A `suspend` keyword on its own moves nothing, which is what made these
 * calls look safe while they blocked the thread that was drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StorageThreadingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val records = RingBufferLogSink()
    private var previousLevel = LogLevel.INFO
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun listen() {
        previousLevel = TermLog.minLevel
        TermLog.minLevel = LogLevel.VERBOSE
        TermLog.addSink(records, LogLevel.VERBOSE)
    }

    @After
    fun stopListening() {
        TermLog.removeSink(records)
        TermLog.minLevel = previousLevel
        scope.cancel()
    }

    private fun threadOf(event: String): String = records.snapshot().first { it.event == event }.thread

    @Test
    fun `a host is written away from the thread that asked for it`() = runBlocking {
        val caller = Thread.currentThread().name
        val hosts = HostRepository(context, CredentialStore(context), scope)

        hosts.upsert(
            HostProfile(id = "h1", name = "MacBook", hostname = "macbook.example.test", username = "someone"),
            password = null,
        )

        assertThat(threadOf("host.saved")).isNotEqualTo(caller)
    }

    @Test
    fun `a trusted host key is written away from the thread that asked for it`() = runBlocking {
        val caller = Thread.currentThread().name
        val knownHosts = AndroidKnownHostsStore(context)

        knownHosts.remember("example.test", 22, HostKeyInfo("ssh-ed25519", "SHA256:synthetic", byteArrayOf(1, 2, 3)))

        assertThat(threadOf("knownHost.remembered")).isNotEqualTo(caller)
    }

    @Test
    fun `a stored password is decrypted away from the thread that asked for it`() = runBlocking {
        // Seeded directly, because the Keystore this would normally come from does not exist
        // under Robolectric — which also means the decryption fails, and the failure is the
        // record carrying the thread that attempted it.
        context.getSharedPreferences("limenty-credentials", Context.MODE_PRIVATE)
            .edit()
            .putString("h1", "AAAAAAAAAAAAAAAAAAAAAAAA")
            .commit()
        val caller = Thread.currentThread().name
        val hosts = HostRepository(context, CredentialStore(context), scope)

        hosts.passwordFor("h1")

        assertThat(threadOf("credential.loadFailed")).isNotEqualTo(caller)
    }
}
