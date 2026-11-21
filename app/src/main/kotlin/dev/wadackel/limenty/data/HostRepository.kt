package dev.wadackel.limenty.data

import android.content.Context
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What became of a save, as far as the screen that asked for it needs to know. */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome

    /**
     * The profile is on disk; the Keystore refused the password.
     *
     * Kept apart from a failure because the host is genuinely saved and genuinely usable: the
     * connection asks for a password it does not have, is refused, and the terminal's retry
     * prompt stores a working one. A Keystore that refuses tends to keep refusing, so treating
     * this as a failure would make the host impossible to add at all.
     */
    data class PasswordNotStored(val cause: Throwable) : SaveOutcome

    data class Failed(val cause: Throwable) : SaveOutcome
}

/**
 * Persists host profiles as a small JSON file.
 *
 * A handful of records with no querying does not justify a database; a file that can be read at
 * a glance is easier to reason about when a saved host misbehaves.
 */
class HostRepository(
    context: Context,
    private val credentials: CredentialStore,
    scope: CoroutineScope,
) {
    private val log = TermLog.logger(LogTags.STORAGE)

    // Resolved on first use, not here: `filesDir` creates the directory if it is missing, which
    // is a disk read and a disk write on the thread that built the container.
    private val file by lazy { File(context.filesDir, FILE_NAME) }

    /** One writer at a time, so two saves cannot each write the list the other started from. */
    private val mutex = Mutex()

    /**
     * Null until the file has been read, rather than an empty list.
     *
     * An empty list would be indistinguishable from "this device has no hosts", and a screen that
     * cannot tell the two apart draws the empty state over hosts that are about to appear.
     */
    private val _hosts = MutableStateFlow<List<HostProfile>?>(null)
    val hosts: StateFlow<List<HostProfile>?> = _hosts.asStateFlow()

    // The hop to IO is inside the coroutine rather than on `async`, so a caller that hands over a
    // scope it controls can decide when the read is allowed to start. `async(Dispatchers.IO)`
    // would take that away, and the ordering below is the thing most worth being able to test.
    private val loading: Deferred<Unit> = scope.async {
        val stored = withContext(Dispatchers.IO) { load() }
        _hosts.value = stored
        log.i("host.loaded", "hosts" to stored.size)
    }

    /**
     * Waits for the first read, so nothing writes a list it has not seen.
     *
     * Every mutation here is a read-modify-write over the published list. Without this a save
     * arriving before the read finished would persist a list of one and take every host already
     * on disk with it.
     */
    suspend fun awaitLoaded() {
        loading.await()
    }

    /**
     * [password] null leaves the stored credential alone; an empty string clears it, which is the
     * only way a user can remove a password they no longer want on the device.
     *
     * The profile is written before the credential, and the published list only after the write
     * succeeded. Both orderings are what keep memory and disk agreeing: publishing first left the
     * list showing a host that a restart could not find, and writing the credential first left one
     * belonging to a host that was never saved.
     */
    suspend fun upsert(host: HostProfile, password: String?): SaveOutcome = withContext(Dispatchers.IO) {
        awaitLoaded()
        mutex.withLock {
            val updated = _hosts.value.orEmpty().toMutableList()
            val index = updated.indexOfFirst { it.id == host.id }
            if (index >= 0) updated[index] = host else updated += host

            val failure = persist(updated).exceptionOrNull()
            if (failure != null) {
                log.e(failure, "host.persistFailed", "hostId" to host.id)
                return@withLock SaveOutcome.Failed(failure)
            }
            _hosts.value = updated
            log.i("host.saved", "hostId" to host.id, "name" to host.name)

            when {
                password == null -> SaveOutcome.Saved
                password.isEmpty() -> {
                    credentials.forget(host.id)
                    SaveOutcome.Saved
                }
                else -> credentials.save(host.id, password)
                    .fold({ SaveOutcome.Saved }, { SaveOutcome.PasswordNotStored(it) })
            }
        }
    }

    /**
     * Removes a host, or leaves it where it is and says so.
     *
     * The row staying in the list is most of what tells the user a delete did not happen, but the
     * caller needs the answer too: deletion takes the trusted host key with it, and dropping that
     * for a host still on the list would leave it asking about a fingerprint it had already been
     * told to trust.
     */
    suspend fun delete(hostId: String): Result<Unit> = withContext(Dispatchers.IO) {
        awaitLoaded()
        mutex.withLock {
            val remaining = _hosts.value.orEmpty().filterNot { it.id == hostId }
            val failure = persist(remaining).exceptionOrNull()
            if (failure != null) {
                log.e(failure, "host.persistFailed", "hostId" to hostId)
                return@withLock Result.failure(failure)
            }
            _hosts.value = remaining
            // The credential goes with the profile so a re-added host is genuinely new rather than
            // silently inheriting old trust.
            credentials.forget(hostId)
            log.i("host.deleted", "hostId" to hostId)
            Result.success(Unit)
        }
    }

    /** Decrypts through the Keystore, which is why it does not belong on the thread that taps. */
    suspend fun passwordFor(hostId: String): String? = withContext(Dispatchers.IO) {
        credentials.load(hostId)
    }

    /**
     * Replaces a stored password after one has been proven to work, or says why it could not.
     *
     * The answer matters even though the connection is already up: a password that worked at the
     * retry prompt and then failed to store is a prompt the user will meet again, and nothing else
     * in the session says so.
     */
    suspend fun updatePassword(hostId: String, password: String): Result<Unit> {
        if (password.isEmpty()) return Result.success(Unit)
        return withContext(Dispatchers.IO) { credentials.save(hostId, password) }
    }

    suspend fun host(hostId: String): HostProfile? {
        awaitLoaded()
        return _hosts.value.orEmpty().firstOrNull { it.id == hostId }
    }

    private fun load(): List<HostProfile> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            HostProfile(
                id = item.getString("id"),
                name = item.getString("name"),
                hostname = item.getString("hostname"),
                username = item.getString("username"),
                port = item.optInt("port", HostProfile.DEFAULT_PORT),
                lan = item.optBoolean("lan", false),
            )
        }
    }.onFailure { log.e(it, "host.loadFailed") }.getOrDefault(emptyList())

    private fun persist(hosts: List<HostProfile>): Result<Unit> = runCatching {
        val array = JSONArray()
        hosts.forEach { host ->
            array.put(
                JSONObject()
                    .put("id", host.id)
                    .put("name", host.name)
                    .put("hostname", host.hostname)
                    .put("username", host.username)
                    .put("port", host.port)
                    .put("lan", host.lan),
            )
        }
        file.writeTextAtomically(array.toString())
    }

    private companion object {
        const val FILE_NAME = "hosts.json"
    }
}
