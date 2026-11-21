package dev.thathunky.meteora.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.BuildConfig
import dev.thathunky.meteora.companion.CompanionClient
import dev.thathunky.meteora.companion.CompanionException
import dev.thathunky.meteora.companion.Endpoint
import dev.thathunky.meteora.companion.HostPolicy
import dev.thathunky.meteora.companion.discovery.CompanionDiscovery
import dev.thathunky.meteora.companion.discovery.DiscoveredPc
import dev.thathunky.meteora.data.PairedPc
import dev.thathunky.meteora.data.PairedPcStore
import dev.thathunky.meteora.data.PlayerRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import dev.thathunky.meteora.companion.discovery.LanScanner
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PairingStep {
    data object Choose : PairingStep
    data class Probing(val name: String) : PairingStep
    /** "Enable companion authorization" is off on the PC; we keep polling. */
    data class WaitingForAuthToggle(val name: String) : PairingStep
    data class ShowCode(val name: String, val code: String) : PairingStep
    data class Failed(val name: String, val reason: Reason) : PairingStep
    data class Done(val name: String) : PairingStep

    enum class Reason { Unreachable, Denied, TooMany, Other }
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    discovery: CompanionDiscovery,
    scanner: LanScanner,
    private val client: CompanionClient,
    private val store: PairedPcStore,
    private val player: PlayerRepository,
) : ViewModel() {

    /** mDNS results, plus a subnet sweep if mDNS stays silent (see [LanScanner]). */
    val discovered: StateFlow<List<DiscoveredPc>> = channelFlow {
        val mdns = MutableStateFlow<List<DiscoveredPc>>(emptyList())
        val swept = MutableStateFlow<List<DiscoveredPc>>(emptyList())
        launch { discovery.discover().catch { emit(emptyList()) }.collect { mdns.value = it } }
        launch {
            delay(2_500)
            if (mdns.value.isEmpty()) {
                swept.value = scanner.scan().map { DiscoveredPc(name = it, addresses = listOf(it), port = Endpoint.DEFAULT_PORT) }
            }
        }
        combine(mdns, swept) { a, b ->
            val known = a.flatMap { it.addresses }.toSet()
            a + b.filter { it.addresses.none(known::contains) }
        }.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _step = MutableStateFlow<PairingStep>(PairingStep.Choose)
    val step: StateFlow<PairingStep> = _step.asStateFlow()

    private var job: Job? = null

    fun pair(pc: DiscoveredPc) = start(pc.name, pc.addresses, pc.port)

    fun pairManual(input: String) {
        val trimmed = input.trim()
        val host = trimmed.substringBeforeLast(':').takeIf { ':' in trimmed && trimmed.count { it == ':' } == 1 } ?: trimmed
        val port = trimmed.substringAfterLast(':', "").toIntOrNull()?.takeIf { ':' in trimmed && trimmed.count { it == ':' } == 1 }
            ?: Endpoint.DEFAULT_PORT
        start(host, listOf(host), port)
    }

    fun cancel() {
        job?.cancel()
        _step.value = PairingStep.Choose
    }

    private fun start(name: String, addresses: List<String>, port: Int) {
        job?.cancel()
        job = viewModelScope.launch {
            _step.value = PairingStep.Probing(name)
            val reachable = probe(addresses.filter(HostPolicy::isAllowed), port)
            val endpoint = reachable.firstOrNull()?.let { Endpoint(it, port) }
            if (endpoint == null) {
                _step.value = PairingStep.Failed(name, PairingStep.Reason.Unreachable)
                return@launch
            }
            // Request a code, polling while the PC's authorization toggle is off.
            var code: String? = null
            while (code == null) {
                try {
                    code = client.requestCode(endpoint, BuildConfig.VERSION_NAME)
                } catch (e: CompanionException) {
                    if (e.isAuthorizationDisabled) {
                        _step.value = PairingStep.WaitingForAuthToggle(name)
                        delay(3_000)
                    } else if (e.isRateLimited) {
                        delay(12_000)
                    } else {
                        _step.value = PairingStep.Failed(name, PairingStep.Reason.Other)
                        return@launch
                    }
                }
            }
            _step.value = PairingStep.ShowCode(name, code)
            val token = try {
                client.requestToken(endpoint, code)
            } catch (e: CompanionException) {
                _step.value = PairingStep.Failed(
                    name,
                    when {
                        e.isAuthorizationDenied -> PairingStep.Reason.Denied
                        e.code == "AUTHORIZATION_TOO_MANY" -> PairingStep.Reason.TooMany
                        else -> PairingStep.Reason.Other
                    },
                )
                return@launch
            } catch (e: Exception) {
                _step.value = PairingStep.Failed(name, PairingStep.Reason.Other)
                return@launch
            }
            store.save(PairedPc(name = name, addresses = reachable + addresses, port = port, token = token, lastGood = endpoint.host))
            player.reconnect()
            _step.value = PairingStep.Done(name)
        }
    }

    /** Every address that answers `/metadata`, in preferred order (LAN, Tailscale, rest). */
    private suspend fun probe(addresses: List<String>, port: Int): List<String> = coroutineScope {
        val ok = addresses.map { a -> async { runCatching { client.metadata(Endpoint(a, port)) }.isSuccess } }.awaitAll()
        PairedPc("", addresses.filterIndexed { i, _ -> ok[i] }, port, "").preferredOrder()
    }
}
