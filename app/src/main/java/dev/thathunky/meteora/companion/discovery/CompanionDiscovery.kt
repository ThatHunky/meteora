package dev.thathunky.meteora.companion.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A YTMDesktop instance found on the LAN. [addresses] are *all* its advertised IPv4s, unprobed. */
data class DiscoveredPc(val name: String, val addresses: List<String>, val port: Int)

/**
 * mDNS browse for `_ytmdesktop._tcp`. The PC advertises an A record per non-internal IPv4
 * (on V's PC: WSL, LAN and Tailscale), so callers must probe every address.
 */
@Singleton
class CompanionDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    private val nsd get() = context.getSystemService(NsdManager::class.java)

    /** Emits the full current set of PCs whenever it changes. Browses while collected. */
    fun discover(): Flow<List<DiscoveredPc>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredPc>()
        val executor = Executors.newSingleThreadExecutor()
        val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        fun publish() { trySend(found.values.toList()) }

        fun onResolved(info: NsdServiceInfo, addrs: List<InetAddress>) {
            val v4 = addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
            if (v4.isEmpty()) return
            synchronized(found) { found[info.serviceName] = DiscoveredPc(info.serviceName, v4, info.port) }
            publish()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    val cb = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceUpdated(updated: NsdServiceInfo) = onResolved(updated, updated.hostAddresses)
                        override fun onServiceLost() {
                            synchronized(found) { found.remove(info.serviceName) }
                            publish()
                        }
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    synchronized(infoCallbacks) { infoCallbacks[info.serviceName] = cb }
                    runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            onResolved(i, listOfNotNull(i.host))
                        }
                    })
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publish()
            }
        }

        publish()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (Build.VERSION.SDK_INT >= 34) {
                synchronized(infoCallbacks) { infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } } }
            }
            executor.shutdown()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_ytmdesktop._tcp"
    }
}
