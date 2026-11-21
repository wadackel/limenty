package dev.wadackel.limenty

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.transport.HostKeyInfo
import dev.wadackel.limenty.core.transport.HostKeyVerifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Two handshakes asking at once, which is what going back to the host list mid-connect and
 * opening a second host produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostKeyPromptTest {
    private val prompt = HostKeyPrompt()

    private fun key() = HostKeyInfo("ssh-ed25519", "SHA256:synthetic", byteArrayOf(1, 2, 3))

    private suspend fun ask(connection: String, host: String, port: Int = 22) = prompt.forConnection(connection).verify(host, port, key())

    @Test
    fun `a second request waits its turn instead of replacing the first`() = runTest {
        val first = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        val second = backgroundScope.async { ask("s2", "two.example.test") }
        runCurrent()

        assertThat(prompt.pending.value!!.host).isEqualTo("one.example.test")

        prompt.pending.value!!.accept()
        runCurrent()

        assertThat(first.await()).isEqualTo(HostKeyVerifier.Decision.Accept)
        assertThat(second.isCompleted).isFalse()
        assertThat(prompt.pending.value!!.host).isEqualTo("two.example.test")
    }

    @Test
    fun `both requests can be decided, each on its own answer`() = runTest {
        val first = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        val second = backgroundScope.async { ask("s2", "two.example.test") }
        runCurrent()

        prompt.pending.value!!.accept()
        runCurrent()
        prompt.pending.value!!.reject()
        runCurrent()

        assertThat(first.await()).isEqualTo(HostKeyVerifier.Decision.Accept)
        assertThat(second.await()).isEqualTo(HostKeyVerifier.Decision.Reject)
        assertThat(prompt.pending.value).isNull()
    }

    /**
     * The connection behind a question can end before it is answered — its session closed, the
     * app shut down. The question has to go with it, or it sits at the head of the queue holding
     * up a prompt somebody could still answer.
     */
    @Test
    fun `a cancelled handshake takes its own question off the queue`() = runTest {
        val first = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        backgroundScope.async { ask("s2", "two.example.test") }
        runCurrent()

        first.cancel()
        runCurrent()

        assertThat(prompt.pending.value!!.host).isEqualTo("two.example.test")
    }

    @Test
    fun `withdrawing rejects every question no connection is waiting on`() = runTest {
        val first = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        val second = backgroundScope.async { ask("s2", "two.example.test") }
        runCurrent()

        prompt.withdrawExcept(emptySet())
        runCurrent()

        assertThat(first.await()).isEqualTo(HostKeyVerifier.Decision.Reject)
        assertThat(second.await()).isEqualTo(HostKeyVerifier.Decision.Reject)
        assertThat(prompt.pending.value).isNull()
    }

    /**
     * The transport asks from a blocking call on an SSHJ thread, so a handshake that ends cannot
     * take its own question back. Left in the queue it would sit in front of a live one, and the
     * connection behind it would wait for a prompt that is never shown.
     */
    @Test
    fun `a question whose connection has gone stops hiding the one behind it`() = runTest {
        val dead = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        backgroundScope.async { ask("s2", "two.example.test") }
        runCurrent()

        prompt.withdrawExcept(setOf("s2"))
        runCurrent()

        assertThat(dead.await()).isEqualTo(HostKeyVerifier.Decision.Reject)
        assertThat(prompt.pending.value!!.host).isEqualTo("two.example.test")
    }

    /**
     * Reconnecting closes one connection and opens another to the same host and port. While the
     * address was the identity, the live connection's entry in [HostKeyPrompt.withdrawExcept] kept
     * the closed one's question alive too, and it stayed in front of the question being asked now.
     */
    @Test
    fun `a question outlived by a reconnect to the same address is still withdrawn`() = runTest {
        val closed = backgroundScope.async { ask("s1", "one.example.test") }
        runCurrent()
        backgroundScope.async { ask("s2", "one.example.test") }
        runCurrent()

        prompt.withdrawExcept(setOf("s2"))
        runCurrent()

        assertThat(closed.await()).isEqualTo(HostKeyVerifier.Decision.Reject)
        assertThat(prompt.pending.value!!.connectionId).isEqualTo("s2")
    }
}
