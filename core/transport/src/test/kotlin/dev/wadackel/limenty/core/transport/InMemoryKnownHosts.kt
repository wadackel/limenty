package dev.wadackel.limenty.core.transport

class InMemoryKnownHosts : KnownHostsStore {
    private val entries = mutableMapOf<String, MutableList<HostKeyInfo>>()

    override suspend fun lookup(host: String, port: Int): List<HostKeyInfo> = entries["$host:$port"].orEmpty()

    override suspend fun remember(host: String, port: Int, key: HostKeyInfo): Result<Unit> {
        entries.getOrPut("$host:$port") { mutableListOf() }.add(key)
        return Result.success(Unit)
    }

    override suspend fun forget(host: String, port: Int): Result<Unit> {
        entries.remove("$host:$port")
        return Result.success(Unit)
    }
}

class RecordingHostKeyVerifier(
    private val decision: HostKeyVerifier.Decision = HostKeyVerifier.Decision.Accept,
    /** Stands in for the time a person spends reading a fingerprint before answering. */
    private val deliberationMillis: Long = 0,
) : HostKeyVerifier {
    var asked: HostKeyInfo? = null
        private set

    var askCount: Int = 0
        private set

    override suspend fun verify(host: String, port: Int, key: HostKeyInfo): HostKeyVerifier.Decision {
        asked = key
        askCount++
        if (deliberationMillis > 0) Thread.sleep(deliberationMillis)
        return decision
    }
}
