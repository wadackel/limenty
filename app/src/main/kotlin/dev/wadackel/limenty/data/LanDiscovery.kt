package dev.wadackel.limenty.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** An SSH server announcing itself on the local network, as Bonjour names it. */
data class LanService(val name: String, val address: String, val port: Int)

/**
 * The SSH servers on the Wi-Fi the device is on, found through Bonjour (`_ssh._tcp`).
 *
 * A Mac with Remote Login on announces itself this way, which is what lets a machine without
 * Tailscale be saved by its name and reached at whatever address DHCP gave it today.
 */
class LanDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    /** Looks a service up by name; NSD unless a test stands in for it. */
    lookup: (suspend (String) -> LanService?)? = null,
) {
    // Counts and error codes only, never a service's name or address: on a shared network those
    // are other people's machines, and a crash writes the records to disk.
    private val log = TermLog.logger(LogTags.DISCOVERY)
    private val appContext = context.applicationContext
    private val nsd by lazy { appContext.getSystemService(NsdManager::class.java) }
    private val connectivity by lazy { appContext.getSystemService(ConnectivityManager::class.java) }
    private val lookup: suspend (String) -> LanService? = lookup ?: ::lookUpWithNsd

    private val _services = MutableStateFlow<List<LanService>>(emptyList())
    val services: StateFlow<List<LanService>> = _services.asStateFlow()

    private val _settled = MutableStateFlow(false)

    /**
     * Whether discovery has been looking long enough on this network for a missing service to
     * mean it is not here. Every saved LAN host would read as absent for the first moments after
     * the app comes forward — exactly when the list is being looked at — without it.
     */
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    private var settling: Job? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var wifi: ConnectivityManager.NetworkCallback? = null

    /**
     * Lookups wait their turn: below API 34 a second resolve while one is running fails outright.
     * A connection's lookup goes to the front, because someone is waiting on it.
     */
    private val queue = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class Pending(val name: String, val result: CompletableDeferred<LanService?> = CompletableDeferred())

    init {
        scope.launch {
            while (true) {
                wake.receive()
                while (true) {
                    val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    val service = withTimeoutOrNull(RESOLVE_MILLIS) { this@LanDiscovery.lookup(next.name) }
                    if (service == null) log.w("lan.resolveFailed")
                    next.result.complete(service)
                }
            }
        }
    }

    /**
     * Starts looking, and keeps looking afresh each time the device joins a Wi-Fi network.
     *
     * The Wi-Fi network is watched rather than the default one: with Tailscale up the default is
     * the VPN, which stays the same network when the Wi-Fi under it changes and changes when only
     * Tailscale is switched.
     */
    @Synchronized
    fun start() {
        if (wifi != null) return
        restartSettling()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.i("wifi.available")
                networkChanged()
            }

            override fun onLost(network: Network) {
                log.i("wifi.lost")
                networkChanged()
            }
        }
        wifi = callback
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { log.e(it, "wifi.watchFailed") }
    }

    @Synchronized
    fun stop() {
        val callback = wifi ?: return
        wifi = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        stopDiscovery()
        settling?.cancel()
        _settled.value = false
        _services.value = emptyList()
    }

    /**
     * The address [name] has right now, waiting up to [timeoutMillis] for it to appear.
     *
     * A service already listed is looked up again rather than trusted: its address can change
     * without Bonjour announcing it gone, and dialling the old one reaches nothing, or another
     * machine whose key then reads as a changed one.
     */
    suspend fun resolve(name: String, timeoutMillis: Long): LanService? {
        val listed = _services.value.firstOrNull { it.name == name }
            ?: return withTimeoutOrNull(timeoutMillis) {
                _services.first { list -> list.any { it.name == name } }.first { it.name == name }
            }
        // Running out of time is not the same as not being found: a listed host falls back to the
        // address it was listed with. Bounding the whole call let lookups queued ahead of this
        // one use up the wait, and a host the list was showing failed as not found.
        val fresh = withTimeoutOrNull(timeoutMillis) { enqueue(name, urgent = true).await() }
        if (fresh != null) found(fresh)
        return fresh ?: listed
    }

    internal fun found(service: LanService) {
        _services.update { list -> list.filterNot { it.name == service.name } + service }
    }

    internal fun lost(name: String) {
        _services.update { list -> list.filterNot { it.name == name } }
    }

    /** What the previous network had is not on this one; look again from nothing. */
    @Synchronized
    internal fun networkChanged() {
        // Restarted on a loss as well as a gain: moving between two Wi-Fi networks can report the
        // new one before the old one is lost, and stopping there would leave the new one unwatched.
        _services.value = emptyList()
        restartSettling()
        if (wifi == null) return
        stopDiscovery()
        startDiscovery()
    }

    private fun restartSettling() {
        settling?.cancel()
        _settled.value = false
        settling = scope.launch {
            delay(SETTLE_MILLIS)
            _settled.value = true
        }
    }

    private fun enqueue(name: String, urgent: Boolean): CompletableDeferred<LanService?> {
        val pending = Pending(name)
        synchronized(queue) { if (urgent) queue.addFirst(pending) else queue.addLast(pending) }
        wake.trySend(Unit)
        return pending.result
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log.i("discovery.started")
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = log.w("discovery.startFailed", "code" to errorCode)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                scope.launch {
                    val service = enqueue(name, urgent = false).await() ?: return@launch
                    found(service)
                    log.i("lan.found", "listed" to _services.value.size)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                lost(info.serviceName)
                log.i("lan.lost", "listed" to _services.value.size)
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { log.e(it, "discovery.startFailed") }
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION")
    private suspend fun lookUpWithNsd(name: String): LanService? = suspendCancellableCoroutine { continuation ->
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                log.w("lan.resolveRefused", "code" to errorCode)
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                // IPv4 only: an IPv6 link-local address needs a scope the socket is not given.
                val v4 = if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses.firstOrNull { it is Inet4Address }
                } else {
                    info.host as? Inet4Address
                }
                val service = v4?.hostAddress?.let { LanService(info.serviceName, it, info.port) }
                if (continuation.isActive) continuation.resume(service)
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure {
            log.e(it, "lan.resolveFailed")
            if (continuation.isActive) continuation.resume(null)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            continuation.invokeOnCancellation { runCatching { nsd.stopServiceResolution(listener) } }
        }
    }

    companion object {
        /** How long discovery looks on a network before a missing service counts as absent. */
        const val SETTLE_MILLIS = 3_000L

        /** One lookup at a time, so one that never answers must not hold up the rest. */
        private const val RESOLVE_MILLIS = 2_000L

        private const val SERVICE_TYPE = "_ssh._tcp"
    }
}

/**
 * The services not yet saved as LAN hosts. Matched on the LAN flag as well as the name: a host
 * saved by address is reached some other way, and the service is still a way in worth offering.
 */
fun List<LanService>.notSaved(hosts: List<HostProfile>): List<LanService> = filter { service ->
    hosts.none { it.lan && it.hostname == service.name }
}
