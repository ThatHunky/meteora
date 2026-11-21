package dev.thathunky.meteora.stream

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.ytm.YtmAuthProvider
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * videoId → playable audio URL. On High when signed in, tries the web music client first (the
 * 256 kbps Premium formats). Then the direct InnerTube call (~0.3 s), and yt-dlp (~15 s) when
 * YouTube changes something. Results are cached until the URL nears expiry, and concurrent
 * requests for one video share a single lookup.
 */
@Singleton
class StreamResolver @Inject constructor(
    @ApplicationContext context: Context,
    okHttp: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
    val quality: StreamQualityStore,
    private val auth: YtmAuthProvider,
    solver: JsSolver,
) {
    private val youtube = okHttp.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val innerTube = InnerTubeStreams(youtube)
    val webMusic = WebMusicStreams(youtube, auth, solver, context.cacheDir)
    val ytDlp = YtDlpStreams(context)

    // Keyed by video and tier: one video can be resolved for two tiers (Wi-Fi, then mobile data).
    private val cache = ConcurrentHashMap<String, AudioStream>()
    private val inFlight = ConcurrentHashMap<String, Deferred<AudioStream>>()
    /** Videos whose web music URL failed at playback (no Premium, or a token wanted): anonymous path from now on. */
    private val avoidWebMusic: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(videoId: String, quality: StreamQuality) = "$videoId/${quality.name}"

    fun cached(videoId: String, quality: StreamQuality = this.quality.current()): AudioStream? =
        cache[key(videoId, quality)]?.takeIf { SystemClock.elapsedRealtime() < it.staleAtMs }

    /** [failedSource]: the source of the stream that failed; a web music failure keeps that video off the web path. */
    fun invalidate(videoId: String, failedSource: String? = null) {
        if (failedSource == WebMusicStreams.SOURCE) avoidWebMusic.add(videoId)
        StreamQuality.entries.forEach { cache.remove(key(videoId, it)) }
    }

    private suspend fun signedIn() = runCatching { auth.headers() }.getOrNull() != null

    /**
     * The lookup runs in the app scope, not the caller's: a caller that gets cancelled (the user
     * tapped another song) must not fail everyone else waiting on the same video.
     */
    suspend fun resolve(videoId: String, quality: StreamQuality = this.quality.current()): AudioStream {
        cached(videoId, quality)?.let { return it }
        // Lazy, so the body's `finally` can only run after the entry is in the map.
        val lookup = inFlight.computeIfAbsent(key(videoId, quality)) { k ->
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    lookup(videoId, quality).also { cache[k] = it }
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return lookup.await()
    }

    private val videoCache = ConcurrentHashMap<String, VideoStream>()
    private val videoInFlight = ConcurrentHashMap<String, Deferred<VideoStream>>()

    private fun videoKey(videoId: String, quality: VideoQuality) = "$videoId/v/${quality.name}"

    fun cachedVideo(videoId: String, quality: VideoQuality): VideoStream? = videoCache[videoKey(videoId, quality)]

    fun invalidateVideo(videoId: String) {
        VideoQuality.entries.forEach { videoCache.remove(videoKey(videoId, it)) }
    }

    /**
     * The picture for [videoId], cached and shared like [resolve]. InnerTube only: yt-dlp takes
     * about 15 s, too long to wait for a picture, so a failure here means the track plays as audio.
     */
    suspend fun resolveVideo(videoId: String, quality: VideoQuality = this.quality.currentVideo()): VideoStream {
        val k = videoKey(videoId, quality)
        videoCache[k]?.takeIf { SystemClock.elapsedRealtime() < it.staleAtMs }?.let { return it }
        val lookup = videoInFlight.computeIfAbsent(k) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    innerTube.resolveVideo(videoId, quality, VideoCodecs.preferred).also { videoCache[k] = it }
                } finally {
                    videoInFlight.remove(k)
                }
            }
        }
        return lookup.await()
    }

    /** Start resolving [videoId] now so the URL is ready when the player asks for it. */
    fun warm(videoId: String) {
        val q = quality.current()
        if (cached(videoId, q) != null) return
        scope.launch { runCatching { resolve(videoId, q) } }
    }

    /** Do the one-off setup (visitor id, TLS connection) ahead of the first track. */
    fun prewarm() {
        scope.launch { runCatching { innerTube.warmUp() }.onFailure { Log.d(TAG, "prewarm failed: ${it.message}") } }
        // Reading and preprocessing the player takes a few seconds: not on the first High track.
        if (quality.wifi.value == StreamQuality.High || quality.mobile.value == StreamQuality.High) scope.launch {
            if (!signedIn()) return@launch
            runCatching { webMusic.warmUp() }.onFailure { Log.d(TAG, "web music prewarm failed: ${it.message}") }
        }
    }

    private suspend fun lookup(videoId: String, quality: StreamQuality): AudioStream {
        // Only High gains from the web path; Low and Normal stay on the anonymous, faster one.
        if (quality == StreamQuality.High && videoId !in avoidWebMusic && webMusic.usable && signedIn()) {
            try {
                return webMusic.resolve(videoId, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // Most likely the solver no longer handles YouTube's player; Normal quality keeps playing.
                Log.w(TAG, "web music failed for $videoId, falling back to visionOS: ${e.message}")
            }
        }
        return anonymous(videoId, quality)
    }

    private suspend fun anonymous(videoId: String, quality: StreamQuality): AudioStream =
        try {
            innerTube.resolve(videoId, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // No network: yt-dlp would only fail the same way, 15 s later.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube failed for $videoId, falling back to yt-dlp: ${e.message}")
            try {
                ytDlp.resolve(videoId, quality)
            } catch (e2: Exception) {
                e2.addSuppressed(e)
                throw e2
            }
        }

    private companion object {
        const val TAG = "StreamResolver"
    }
}
