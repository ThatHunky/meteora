package dev.thathunky.meteora.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One shared budget for every command (server: 2 per second per token, verified in S1).
 *
 * - Discrete commands (play/pause, next, queue ops…) are FIFO and always go before continuous ones.
 * - Continuous commands (seek, volume) keep only their latest value per kind: a slider drag
 *   collapses to whatever the user ends on.
 * - A 429 backs off one window and retries; other failures are reported and dropped.
 */
class CommandThrottler(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPerWindow: Int = 2,
    private val windowMs: Long = 1_000,
    private val onError: (Command, Throwable) -> Unit = { _, _ -> },
    private val send: suspend (Command) -> Unit,
) {
    private val lock = Mutex()
    private val discrete = ArrayDeque<Command>()
    private val latest = LinkedHashMap<Command.Kind, Command>()
    private val sentAt = ArrayDeque<Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    fun submit(command: Command) {
        scope.launch {
            lock.withLock {
                if (command.kind == Command.Kind.Discrete) discrete.addLast(command) else latest[command.kind] = command
            }
            wake.trySend(Unit)
        }
        ensureWorker()
    }

    /** Drops everything still waiting (e.g. on disconnect). */
    fun clear() {
        scope.launch { lock.withLock { discrete.clear(); latest.clear() } }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { loop() }
    }

    private suspend fun loop() {
        while (true) {
            wake.receive()
            while (true) {
                waitForBudget()
                val next = lock.withLock { takeNext() } ?: break
                sentAt.addLast(now())
                try {
                    send(next)
                } catch (e: CompanionException) {
                    if (e.isRateLimited) {
                        lock.withLock { requeue(next) }
                        delay(windowMs)
                    } else {
                        onError(next, e)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(next, e)
                }
            }
        }
    }

    private suspend fun waitForBudget() {
        while (true) {
            val t = now()
            while (sentAt.isNotEmpty() && t - sentAt.first() >= windowMs) sentAt.removeFirst()
            if (sentAt.size < maxPerWindow) return
            delay(windowMs - (t - sentAt.first()) + 1)
        }
    }

    private fun takeNext(): Command? {
        discrete.removeFirstOrNull()?.let { return it }
        val key = latest.keys.firstOrNull() ?: return null
        return latest.remove(key)
    }

    private fun requeue(c: Command) {
        if (c.kind == Command.Kind.Discrete) discrete.addFirst(c) else latest.putIfAbsent(c.kind, c)
    }
}
