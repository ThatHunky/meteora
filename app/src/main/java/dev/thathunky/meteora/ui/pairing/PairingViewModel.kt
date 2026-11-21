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
    /** [attempt] > 0 means the previous window on the PC timed out and this is a fresh code. */
    data class ShowCode(val name: String, val code: String, val attempt: Int = 0) : PairingStep
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
            // The PC's Allow window closes after 30 s and reports a timeout exactly like a
            // denial, so on failure we simply ask again with a fresh code a few times.
            var token: String? = null
            var attempt = 0
            while (token == null) {
                // requestcode is limited to 5/min: poll the toggle every 13 s.
                var code: String? = null
                while (code == null) {
                    try {
                        code = client.requestCode(endpoint, BuildConfig.VERSION_NAME)
                    } catch (e: CompanionException) {
                        when {
                            e.isAuthorizationDisabled -> {
                                _step.value = PairingStep.WaitingForAuthToggle(name)
                                delay(13_000)
                            }
                            e.isRateLimited -> delay(15_000)
                            else -> {
                                _step.value = PairingStep.Failed(name, PairingStep.Reason.Other)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        _step.value = PairingStep.Failed(name, PairingStep.Reason.Unreachable)
                        return@launch
                    }
                }
                _step.value = PairingStep.ShowCode(name, code, attempt)
                token = try {
                    client.requestToken(endpoint, code)
                } catch (e: CompanionException) {
                    when {
                        e.code == "AUTHORIZATION_TOO_MANY" -> {
                            _step.value = PairingStep.Failed(name, PairingStep.Reason.TooMany)
                            return@launch
                        }
                        e.isAuthorizationDenied && ++attempt < MAX_ATTEMPTS -> {
                            delay(13_000) // stay inside the requestcode rate limit
                            null
                        }
                        else -> {
                            _step.value = PairingStep.Failed(name, PairingStep.Reason.Denied)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    _step.value = PairingStep.Failed(name, PairingStep.Reason.Other)
                    return@launch
                }
            }
            store.save(PairedPc(name = name, addresses = reachable + addresses, port = port, token = token, lastGood = endpoint.host))
            player.reconnect()
            _step.value = PairingStep.Done(name)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }

    /** Every address that answers `/metadata`, in preferred order (LAN, Tailscale, rest). */
    private suspend fun probe(addresses: List<String>, port: Int): List<String> = coroutineScope {
        val ok = addresses.map { a -> async { runCatching { client.metadata(Endpoint(a, port)) }.isSuccess } }.awaitAll()
        PairedPc("", addresses.filterIndexed { i, _ -> ok[i] }, port, "").preferredOrder()
    }
}
