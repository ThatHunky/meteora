package dev.thathunky.meteora.lyrics

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.BuildConfig
import dev.thathunky.meteora.domain.Lyrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import javax.inject.Singleton

/** One place lyrics can come from. Null: it has none for the track. Throws when it cannot be asked. */
fun interface LyricsSource {
    suspend fun find(track: Track): Lyrics?
}

/** YouTube's own lyrics: the lyrics id from `next`, then the lyrics browse. */
class YouTubeLyrics @Inject constructor(private val ytm: YtmClient) : LyricsSource {
    override suspend fun find(track: Track): Lyrics? = ytm.lyricsId(track.videoId)?.let { ytm.lyrics(it) }
}

/**
 * Lyrics for a track, asked only when the lyrics page wants them. YouTube first; LRCLIB only when
 * [lrclibEnabled] and YouTube has no timed lyrics, and its answer is taken only when timed.
 * Each source's answers, "none" included, are kept for the last [CACHE_SIZE] tracks so a track is
 * not asked twice, and turning LRCLIB on still asks it for a track YouTube already answered.
 * A failure is not kept, so an offline open is tried again next time.
 */
class LyricsRepository(
    private val youtube: LyricsSource,
    private val lrclib: LyricsSource? = null,
    private val lrclibEnabled: () -> Boolean = { false },
) {
    private class Entry(val lyrics: Lyrics?)

    private class Lru : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CACHE_SIZE
    }

    private val fromYoutube = Lru()
    private val fromLrclib = Lru()

    /** Changes when the answer for [track] may change: another track, or LRCLIB turned on or off. */
    fun answerKey(track: Track): String = "${track.videoId}:${lrclib != null && lrclibEnabled()}"

    /** Null: the track has no lyrics. Throws when YouTube could not be asked. */
    suspend fun lyrics(track: Track): Lyrics? {
        val yt = cached(fromYoutube, track) { youtube.find(track) }
        if (yt?.synced == true || lrclib == null || !lrclibEnabled()) return yt
        val other = try {
            cached(fromLrclib, track) { lrclib.find(track) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // LRCLIB is extra: YouTube's answer stands, and LRCLIB is asked again next time.
        }
        return other?.takeIf { it.synced } ?: yt
    }

    private suspend fun cached(cache: Lru, track: Track, fetch: suspend () -> Lyrics?): Lyrics? {
        val key = track.videoId
        synchronized(cache) { cache[key] }?.let { return it.lyrics }
        val found = fetch()
        synchronized(cache) { cache[key] = Entry(found) }
        return found
    }

    companion object {
        const val CACHE_SIZE = 30
    }
}

/** One repository for the app, so the cache outlives a lyrics page. */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {
    @Provides @Singleton
    fun lyricsRepository(youtube: YouTubeLyrics, okHttp: OkHttpClient, store: LrcLibStore): LyricsRepository {
        // Its own TLS-only client: no YouTube auth provider, no cookies.
        val https = okHttp.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val lrclib = LrcLibClient(HttpClient(OkHttp.create { preconfigured = https }) { expectSuccess = false }, "Meteora/${BuildConfig.VERSION_NAME}")
        return LyricsRepository(youtube, lrclib) { store.enabled.value }
    }
}
