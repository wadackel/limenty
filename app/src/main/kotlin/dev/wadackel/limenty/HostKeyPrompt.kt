package dev.wadackel.limenty

import dev.wadackel.limenty.core.transport.HostKeyInfo
import dev.wadackel.limenty.core.transport.HostKeyVerifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns SSHJ's mid-handshake trust question into something the UI can answer.
 *
 * The transport suspends on [HostKeyVerifier.verify] until a screen resolves it, which is what
 * makes "never auto-accept" enforceable: there is no default answer to fall through to.
 *
 * Requests queue rather than replace one another. Two handshakes can be in flight at once — going
 * back to the list mid-connect and opening a second host is enough — and a single slot meant the
 * later one overwrote the earlier: the first question vanished from the screen while its
 * handshake went on waiting for an answer that could no longer be given.
 */
class HostKeyPrompt {
    private val waiting = ArrayDeque<Request>()

    private val _pending = MutableStateFlow<Request?>(null)

    /** The request the user is being asked about, or null when there is none. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * A verifier whose questions belong to [connectionId].
     *
     * The address a connection dialled cannot serve as the identity: reconnecting closes one
     * connection and opens another to the same host and port, so a question left behind by the
     * first would look to [withdrawExcept] exactly like the one the second is waiting on, and
     * survive at the head of the queue in front of it.
     */
    fun forConnection(connectionId: String): HostKeyVerifier = object : HostKeyVerifier {
        override suspend fun verify(host: String, port: Int, key: HostKeyInfo): HostKeyVerifier.Decision = ask(Request(connectionId, host, port, key))
    }

    private suspend fun ask(request: Request): HostKeyVerifier.Decision {
        synchronized(waiting) {
            waiting.addLast(request)
            publish()
        }
        // Removed in a finally so a handshake that is cancelled — its session closed, the app
        // shut down — takes its question off the screen with it rather than leaving one nothing
        // is listening to at the head of the queue.
        return try {
            request.answer.await()
        } finally {
            drop(request)
        }
    }

    /**
     * Withdraws every question no live connection is still waiting on.
     *
     * A handshake can end underneath a question the user has not answered — a timeout, the network
     * dropping, the session being closed — and the transport cannot take its own question back:
     * it asks from a blocking call on an SSHJ thread, which cancelling the session's coroutines
     * does not reach. Left in the queue, a dead question sits in front of a live one and the
     * connection behind it waits for a prompt that is never shown.
     *
     * [stillWaitedOn] holds the id of every connection that is still in its handshake.
     */
    fun withdrawExcept(stillWaitedOn: Set<String>) {
        val withdrawn = synchronized(waiting) {
            val dead = waiting.filterNot { it.connectionId in stillWaitedOn }
            waiting.removeAll(dead.toSet())
            publish()
            dead
        }
        withdrawn.forEach { it.reject() }
    }

    private fun drop(request: Request) = synchronized(waiting) {
        waiting.remove(request)
        publish()
    }

    private fun publish() {
        _pending.value = waiting.firstOrNull()
    }

    inner class Request(
        val connectionId: String,
        val host: String,
        val port: Int,
        val key: HostKeyInfo,
    ) {
        internal val answer = CompletableDeferred<HostKeyVerifier.Decision>()

        fun accept() {
            answer.complete(HostKeyVerifier.Decision.Accept)
            drop(this)
        }

        fun reject() {
            answer.complete(HostKeyVerifier.Decision.Reject)
            drop(this)
        }
    }
}
