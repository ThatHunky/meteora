package dev.thathunky.meteora.companion

import io.socket.client.IO
import io.socket.client.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.OkHttpClient

sealed interface RealtimeEvent {
    data object Connected : RealtimeEvent
    data class Disconnected(val reason: String) : RealtimeEvent
    /** Token rejected by the server middleware: re-pair. */
    data object Unauthorized : RealtimeEvent
    data class ConnectError(val message: String) : RealtimeEvent
    data class State(val state: StateDto) : RealtimeEvent
}

/**
 * Socket.IO v4 on `/api/v1/realtime`. The default namespace disconnects everyone, so we connect
 * straight to the realtime namespace, websocket only. The server never sends state on connect:
 * callers seed with `GET /state` after [RealtimeEvent.Connected].
 *
 * State updates are ~50 KB at 4–15/s, so raw JSON is conflated *before* parsing and parsed
 * off the main thread.
 */
class RealtimeClient(private val okHttp: OkHttpClient) {

    fun connect(endpoint: Endpoint, token: String): Flow<RealtimeEvent> {
        if (!HostPolicy.isAllowed(endpoint.host)) throw HostNotAllowedException(endpoint.host)
        return callbackFlow<RawEvent> {
            val opts = IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .setReconnectionDelay(1_000)
                .setReconnectionDelayMax(10_000)
                .setForceNew(true)
                .build()
            opts.callFactory = okHttp
            opts.webSocketFactory = okHttp
            val socket = IO.socket("${endpoint.baseUrl}/api/v1/realtime", opts)

            socket.on(Socket.EVENT_CONNECT) { trySend(RawEvent.Connected) }
            socket.on(Socket.EVENT_DISCONNECT) { args -> trySend(RawEvent.Disconnected(args.firstOrNull()?.toString() ?: "")) }
            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.firstOrNull()?.let { (it as? Exception)?.message ?: it.toString() } ?: ""
                trySend(RawEvent.ConnectError(msg))
            }
            // Conflate only state: keep the newest raw JSON and signal once until it's taken.
            val pending = AtomicReference<String?>(null)
            socket.on("state-update") { args ->
                val json = args.firstOrNull()?.toString() ?: return@on
                if (pending.getAndSet(json) == null) trySend(RawEvent.StateReady(pending))
            }
            socket.connect()
            awaitClose {
                socket.off()
                socket.close()
            }
        }
            .buffer(Channel.UNLIMITED)
            .mapNotNull { raw ->
                when (raw) {
                    RawEvent.Connected -> RealtimeEvent.Connected
                    is RawEvent.Disconnected -> RealtimeEvent.Disconnected(raw.reason)
                    is RawEvent.ConnectError ->
                        if (raw.message.contains("auth", ignoreCase = true) ||
                            raw.message.contains("UNAUTHENTICATED")
                        ) RealtimeEvent.Unauthorized else RealtimeEvent.ConnectError(raw.message)
                    is RawEvent.StateReady -> raw.slot.getAndSet(null)?.let { json ->
                        runCatching { CompanionJson.decodeFromString<StateDto>(json) }.getOrNull()?.let(RealtimeEvent::State)
                    }
                }
            }
            .flowOn(Dispatchers.Default)
    }

    private sealed interface RawEvent {
        data object Connected : RawEvent
        data class Disconnected(val reason: String) : RawEvent
        data class ConnectError(val message: String) : RawEvent
        class StateReady(val slot: AtomicReference<String?>) : RawEvent
    }
}
