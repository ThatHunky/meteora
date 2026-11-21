package dev.thathunky.meteora.companion.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.companion.CompanionClient
import dev.thathunky.meteora.companion.Endpoint
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fallback discovery: probe `/metadata` on every host of the phone's Wi-Fi /24.
 *
 * Needed because YTMDesktop's mDNS responder can answer on the wrong adapter on multi-NIC
 * Windows PCs (WSL / Hyper-V / Tailscale): on V's PC it logs every query from the phone but no
 * reply ever arrives. A /24 sweep with a short timeout takes ~1–2 s.
 */
@Singleton
class LanScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: CompanionClient,
) {
    suspend fun scan(port: Int = Endpoint.DEFAULT_PORT): List<String> = withContext(Dispatchers.IO) {
        val own = wifiIpv4() ?: return@withContext emptyList()
        val base = own.substringBeforeLast('.')
        val gate = Semaphore(64)
        coroutineScope {
            (1..254).map { "$base.$it" }.filter { it != own }.map { host ->
                async {
                    gate.withPermit {
                        host.takeIf { runCatching { client.metadata(Endpoint(host, port), timeoutMs = 700) }.isSuccess }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun wifiIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.let { c ->
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()?.hostAddress
    }
}
