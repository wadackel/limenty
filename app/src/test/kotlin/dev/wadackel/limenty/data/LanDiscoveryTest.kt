package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.wadackel.limenty.core.common.log.LogLevel
import dev.wadackel.limenty.core.common.log.RingBufferLogSink
import dev.wadackel.limenty.core.common.log.TermLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the rest of the app reads from discovery. NSD itself does not run off a device, so the
 * services are handed in as NSD would report them and the lookup at connection time is a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LanDiscoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val records = RingBufferLogSink()
    private var previousLevel = LogLevel.INFO

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
    }

    private fun TestScope.discovery(lookup: suspend (String) -> LanService? = { null }) = LanDiscovery(context, backgroundScope, lookup)

    private val mac = LanService(name = "alice.smith’s Mac", address = "192.168.1.20", port = 22)

    @Test
    fun `a service already found is looked up again so a changed address is used`() = runTest {
        val lan = discovery { mac.copy(address = "192.168.1.31") }
        lan.found(mac)

        assertThat(lan.resolve(mac.name, 3_000)?.address).isEqualTo("192.168.1.31")
    }

    @Test
    fun `a service on the list is still reached when the lookups ahead of it run out the wait`() = runTest {
        val lan = discovery {
            delay(10_000)
            null
        }
        val other = mac.copy(name = "Bob’s Mac")
        lan.found(mac)
        lan.found(other)
        val ahead = async { lan.resolve(other.name, 10_000) }
        runCurrent()

        assertThat(lan.resolve(mac.name, 3_000)).isEqualTo(mac)
        ahead.await()
    }

    @Test
    fun `a service found while a connection waits is handed to it`() = runTest {
        val lan = discovery()
        val waiting = async { lan.resolve(mac.name, 3_000) }
        runCurrent()

        lan.found(mac)

        assertThat(waiting.await()).isEqualTo(mac)
    }

    @Test
    fun `a lookup that fails is recorded without the name or address of the machine`() = runTest {
        // On a shared network the services are other people's machines, and the record outlives
        // the moment in a crash report.
        val lan = discovery { null }
        lan.found(mac)

        lan.resolve(mac.name, 3_000)

        val failed = records.snapshot().filter { it.event == "lan.resolveFailed" }
        assertThat(failed).hasSize(1)
        assertWithMessage("leaks a discovered name").that(failed.single().fields.toString()).doesNotContain(mac.name)
        assertWithMessage("leaks a discovered address").that(failed.single().fields.toString()).doesNotContain(mac.address)
    }

    @Test
    fun `a service that never appears gives up when the wait runs out`() = runTest {
        val lan = discovery()

        assertThat(lan.resolve(mac.name, 3_000)).isNull()
    }

    @Test
    fun `a service that goes away leaves the list`() = runTest {
        val lan = discovery()
        lan.found(mac)

        lan.lost(mac.name)

        assertThat(lan.services.value).isEmpty()
    }

    @Test
    fun `a service reported on two interfaces is listed once`() = runTest {
        val lan = discovery()
        lan.found(mac)
        lan.found(mac.copy(address = "192.168.1.21"))

        assertThat(lan.services.value).containsExactly(mac.copy(address = "192.168.1.21"))
    }

    @Test
    fun `absence means something only once discovery has had time to look`() = runTest {
        val lan = discovery()

        lan.networkChanged()
        assertThat(lan.settled.value).isFalse()
        advanceTimeBy(LanDiscovery.SETTLE_MILLIS + 1)
        assertThat(lan.settled.value).isTrue()

        lan.networkChanged()
        assertThat(lan.settled.value).isFalse()
    }

    @Test
    fun `a network change forgets what the previous network had`() = runTest {
        val lan = discovery()
        lan.found(mac)

        lan.networkChanged()

        assertThat(lan.services.value).isEmpty()
    }

    @Test
    fun `a service already saved as a host is not offered again`() {
        val saved = HostProfile(name = "", hostname = mac.name, username = "someone", lan = true)
        val other = mac.copy(name = "Bob’s Mac")

        assertThat(listOf(mac, other).notSaved(listOf(saved))).containsExactly(other)
    }

    @Test
    fun `a host saved by address does not hide a service of the same name`() {
        val byAddress = HostProfile(name = "", hostname = mac.name, username = "someone")

        assertThat(listOf(mac).notSaved(listOf(byAddress))).containsExactly(mac)
    }
}
