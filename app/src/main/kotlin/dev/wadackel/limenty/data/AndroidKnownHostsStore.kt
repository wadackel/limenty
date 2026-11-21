package dev.wadackel.limenty.data

import android.content.Context
import android.util.Base64
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.transport.HostKeyInfo
import dev.wadackel.limenty.core.transport.KnownHostsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers host keys the user has trusted.
 *
 * Stored as the SSH wire encoding, so a later comparison is byte-exact rather than a fingerprint
 * match — the point of host key verification is that a changed key is detected, and a truncated
 * hash weakens exactly that.
 */
class AndroidKnownHostsStore(context: Context) : KnownHostsStore {
    private val log = TermLog.logger(LogTags.STORAGE)

    // Resolved on first use, for the same reason as the other stores: `filesDir` creates the
    // directory, so naming a file under it touches the disk.
    private val file by lazy { File(context.filesDir, FILE_NAME) }
    private val mutex = Mutex()

    override suspend fun lookup(host: String, port: Int): List<HostKeyInfo> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll()[key(host, port)].orEmpty() }
    }

    override suspend fun remember(host: String, port: Int, key: HostKeyInfo): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAll().toMutableMap()
            val entries = all.getOrPut(key(host, port)) { mutableListOf() }.toMutableList()
            entries.removeAll { it.algorithm == key.algorithm }
            entries += key
            all[key(host, port)] = entries
            writeAll(all).onSuccess {
                log.i("knownHost.remembered", "host" to host, "algorithm" to key.algorithm)
            }
        }
    }

    override suspend fun forget(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAll().toMutableMap()
            all.remove(key(host, port))
            writeAll(all).onSuccess { log.i("knownHost.forgotten", "host" to host) }
        }
    }

    private fun key(host: String, port: Int) = "$host:$port"

    private fun readAll(): Map<String, MutableList<HostKeyInfo>> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { entry ->
            val array = root.getJSONArray(entry)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                HostKeyInfo(
                    algorithm = item.getString("algorithm"),
                    fingerprint = item.getString("fingerprint"),
                    key = Base64.decode(item.getString("key"), Base64.NO_WRAP),
                )
            }.toMutableList()
        }
    }.onFailure { log.e(it, "knownHost.readFailed") }.getOrDefault(emptyMap())

    /**
     * The failure is returned rather than thrown, and the trust path deliberately ignores it: a
     * write that fails reaches this store from inside a handshake the user has just approved, and
     * turning a storage problem into a refused connection would be a worse answer than being
     * asked about the same key again next time. The delete path is the one that needs the answer,
     * because a key left on the disk outlives the host it belonged to.
     */
    private fun writeAll(all: Map<String, List<HostKeyInfo>>): Result<Unit> = runCatching {
        val root = JSONObject()
        all.forEach { (entry, keys) ->
            val array = JSONArray()
            keys.forEach { info ->
                array.put(
                    JSONObject()
                        .put("algorithm", info.algorithm)
                        .put("fingerprint", info.fingerprint)
                        .put("key", Base64.encodeToString(info.key, Base64.NO_WRAP)),
                )
            }
            root.put(entry, array)
        }
        file.writeTextAtomically(root.toString())
    }.onFailure { log.e(it, "knownHost.writeFailed") }

    private companion object {
        const val FILE_NAME = "known_hosts.json"
    }
}
