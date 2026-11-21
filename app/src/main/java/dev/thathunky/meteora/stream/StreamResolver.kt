package dev.thathunky.meteora.stream

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * videoId → playable audio URL. Tries the direct InnerTube call first (~0.3 s) and falls back to
 * yt-dlp (~15 s) when YouTube changes something. Results are cached until the URL nears expiry,
 * and concurrent requests for one video share a single lookup.
 */
@Singleton
class StreamResolver @Inject constructor(
    @ApplicationContext context: Context,
    okHttp: OkHttpClient,
) {
    private val innerTube = InnerTubeStreams(
        okHttp.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
    )
    val ytDlp = YtDlpStreams(context)

    private val cache = ConcurrentHashMap<String, AudioStream>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<AudioStream>>()

    fun cached(videoId: String): AudioStream? =
        cache[videoId]?.takeIf { SystemClock.elapsedRealtime() < it.staleAtMs }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolve(videoId: String): AudioStream {
        cached(videoId)?.let { return it }
        val mine = CompletableDeferred<AudioStream>()
        inFlight.putIfAbsent(videoId, mine)?.let { return it.await() }
        try {
            val s = lookup(videoId)
            cache[videoId] = s
            mine.complete(s)
            return s
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(videoId)
        }
    }

    private suspend fun lookup(videoId: String): AudioStream =
        try {
            innerTube.resolve(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube failed for $videoId, falling back to yt-dlp: ${e.message}")
            try {
                ytDlp.resolve(videoId)
            } catch (e2: Exception) {
                e2.addSuppressed(e)
                throw e2
            }
        }

    private companion object {
        const val TAG = "StreamResolver"
    }
}
