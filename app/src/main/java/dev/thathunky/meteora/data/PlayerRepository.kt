package dev.thathunky.meteora.data

import android.os.SystemClock
import android.util.Log
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.CommandThrottler
import dev.thathunky.meteora.companion.CompanionClient
import dev.thathunky.meteora.companion.CompanionException
import dev.thathunky.meteora.companion.Endpoint
import dev.thathunky.meteora.companion.RealtimeClient
import dev.thathunky.meteora.companion.RealtimeEvent
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.toDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnectionState {
    data object Unpaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val pcName: String, val endpoint: Endpoint) : ConnectionState
    /** PC unreachable on every known address; retrying with backoff. */
    data class Offline(val pcName: String, val reason: String) : ConnectionState
    /** Token revoked on the PC: user must pair again. */
    data object Unauthorized : ConnectionState
    data object Idle : ConnectionState
    /** Playing on this phone: the PC connection doesn't matter. */
    data object Local : ConnectionState
}

/**
 * Single source of truth for the PC's playback. Ref-counted: the UI and the media session
 * service [acquire] while they need live state; the socket closes when nobody does.
 */
@Singleton
class PlayerRepository @Inject constructor(
    private val client: CompanionClient,
    private val realtime: RealtimeClient,
    private val store: PairedPcStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors: SharedFlow<Throwable> = _errors

    private val lock = Mutex()
    private var holders = 0
    private var session: Job? = null
    private var current: Pair<Endpoint, String>? = null
    private var lastSeedAt = 0L

    private val throttler = CommandThrottler(
        scope = scope,
        onError = { _, e -> handleError(e) },
        send = { cmd ->
            val (ep, token) = current ?: throw IllegalStateException("not connected")
            client.send(ep, token, cmd)
        },
    )

    fun acquire() {
        scope.launch {
            lock.withLock {
                holders++
                if (session?.isActive != true) session = scope.launch { runSession() }
            }
        }
    }

    fun release() {
        scope.launch {
            lock.withLock {
                holders = (holders - 1).coerceAtLeast(0)
                if (holders == 0) {
                    // Grace period so a configuration change / screen hop doesn't drop the socket.
                    val s = session
                    scope.launch {
                        delay(RELEASE_GRACE_MS)
                        lock.withLock {
                            if (holders == 0 && session === s) {
                                s?.cancel()
                                session = null
                                current = null
                                throttler.clear()
                                _connection.value = ConnectionState.Idle
                            }
                        }
                    }
                }
            }
        }
    }

    /** Restart the session, e.g. after pairing. */
    fun reconnect() {
        scope.launch {
            lock.withLock {
                session?.cancel()
                session = if (holders > 0) scope.launch { runSession() } else null
            }
        }
    }

    fun send(command: Command) = throttler.submit(command)

    fun play(intent: PlayIntent) {
        val s = _state.value
        val cmd = when (intent) {
            is PlayIntent.SongRadio ->
                // changeVideo with the already-loaded video is a no-op (S1): just play it.
                if (s?.track?.videoId == intent.videoId) Command.Play
                else Command.ChangeVideo(intent.videoId, "RDAMVM${intent.videoId}")
            is PlayIntent.Collection -> Command.ChangeVideo(intent.startVideoId, intent.playlistId)
            PlayIntent.LikedSongs -> Command.ChangeVideo(playlistId = "LM")
        }
        send(cmd)
    }

    fun playNext(videoId: String? = null, playlistId: String? = null) {
        val s = _state.value ?: return
        send(Command.QueueAdd(videoId, playlistId, (s.selectedIndex + 1).coerceIn(0, s.queue.size)))
    }

    fun addToQueue(videoId: String? = null, playlistId: String? = null) {
        val s = _state.value ?: return
        send(Command.QueueAdd(videoId, playlistId, s.queue.size))
    }

    /** Index-based queue ops race with automix: re-check the id at [index] first. */
    fun queueCommandFor(videoId: String, index: Int, build: (Int) -> Command) {
        val q = _state.value?.queue ?: return
        val i = if (q.getOrNull(index)?.videoId == videoId) index else q.indexOfFirst { it.videoId == videoId }
        if (i >= 0) send(build(i)) else _errors.tryEmit(IllegalStateException("Queue changed"))
    }

    private suspend fun runSession() {
        var backoff = 1_000L
        while (true) {
            val pc = store.pc.first()
            if (pc == null) {
                _connection.value = ConnectionState.Unpaired
                return
            }
            _connection.value = ConnectionState.Connecting
            val endpoint = resolve(pc)
            if (endpoint == null) {
                _connection.value = ConnectionState.Offline(pc.name, "unreachable")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
                continue
            }
            backoff = 1_000L
            if (pc.lastGood != endpoint.host) store.save(pc.copy(lastGood = endpoint.host))
            current = endpoint to pc.token
            val outcome = followRealtime(pc, endpoint)
            current = null
            when (outcome) {
                Outcome.Unauthorized -> {
                    _connection.value = ConnectionState.Unauthorized
                    _state.value = null
                    return
                }
                Outcome.Lost -> {
                    _connection.value = ConnectionState.Offline(pc.name, "connection lost")
                    delay(1_000)
                }
            }
        }
    }

    private enum class Outcome { Unauthorized, Lost }

    private suspend fun followRealtime(pc: PairedPc, endpoint: Endpoint): Outcome {
        var connectErrors = 0
        var outcome = Outcome.Lost
        realtime.connect(endpoint, pc.token).takeWhile { ev ->
            when (ev) {
                RealtimeEvent.Connected -> {
                    connectErrors = 0
                    _connection.value = ConnectionState.Connected(pc.name, endpoint)
                    seed(endpoint, pc.token)
                    true
                }
                is RealtimeEvent.State -> {
                    publish(ev.state.toDomain(SystemClock.elapsedRealtime()))
                    true
                }
                RealtimeEvent.Unauthorized -> {
                    outcome = Outcome.Unauthorized
                    false
                }
                is RealtimeEvent.ConnectError -> {
                    Log.w(TAG, "realtime connect error: ${ev.message}")
                    // socket.io keeps retrying this address; after a few failures re-resolve
                    // (the phone may have left the LAN and needs the Tailscale route).
                    ++connectErrors < 3
                }
                is RealtimeEvent.Disconnected -> {
                    _connection.value = ConnectionState.Connecting
                    true
                }
            }
        }.collect {}
        return outcome
    }

    /** The server sends no state on connect; `/state` allows 1 call per 5 s. */
    private fun seed(endpoint: Endpoint, token: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeedAt < 5_100) return
        lastSeedAt = now
        scope.launch {
            try {
                val dto = client.state(endpoint, token)
                if (_state.value == null || SystemClock.elapsedRealtime() - (_state.value?.reportedAtMs ?: 0) > 1_000) {
                    publish(dto.toDomain(SystemClock.elapsedRealtime()))
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private var pendingTransient: Job? = null

    private fun publish(s: PlaybackState) {
        pendingTransient?.cancel()
        if (s.isTransient && _state.value?.queue?.isNotEmpty() == true) {
            // Hide YTM's mid-change frames unless they persist.
            pendingTransient = scope.launch {
                delay(TRANSIENT_HOLD_MS)
                _state.value = s
            }
        } else {
            _state.value = s
        }
    }

    private fun handleError(e: Throwable) {
        if (e is CompanionException && e.isUnauthorized) {
            _connection.value = ConnectionState.Unauthorized
            session?.cancel()
        }
        _errors.tryEmit(e)
    }

    /** Probe every known address at once; take the best-ranked one that answers. */
    private suspend fun resolve(pc: PairedPc): Endpoint? = coroutineScope {
        val ordered = pc.preferredOrder()
        val results = ordered.map { host ->
            async { runCatching { client.metadata(Endpoint(host, pc.port), timeoutMs = 1_500) }.isSuccess }
        }.awaitAll()
        ordered.zip(results).firstOrNull { it.second }?.let { Endpoint(it.first, pc.port) }
    }

    private companion object {
        const val TAG = "PlayerRepository"
        const val RELEASE_GRACE_MS = 5_000L
        const val TRANSIENT_HOLD_MS = 400L
    }
}
