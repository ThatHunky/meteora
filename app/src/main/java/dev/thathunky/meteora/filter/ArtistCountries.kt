package dev.thathunky.meteora.filter

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.BuildConfig
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.ytm.ArtistRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/** One cached answer: [country] null is "unknown". [at] is when it was asked, in epoch ms. */
@Serializable
data class CountryEntry(val country: String? = null, val at: Long)

/**
 * Artist → country, kept [TTL_MS] (90 days). Keyed by browse id, or by the lowercased name for
 * an artist YouTube gave no id. Pure, so its rules are tested on the JVM.
 */
class CountryCache(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = ConcurrentHashMap<String, CountryEntry>()

    /** The entry when there is a fresh one: its country may be null (unknown). */
    fun lookup(key: String): CountryEntry? = entries[key]?.takeIf { now() - it.at < TTL_MS }

    fun put(key: String, country: String?) {
        entries[key] = CountryEntry(country, now())
    }

    fun encode(): String = json.encodeToString(serializer, entries.filterValues { now() - it.at < TTL_MS })

    fun load(text: String) {
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()?.let { entries.putAll(it) }
    }

    companion object {
        val TTL_MS = TimeUnit.DAYS.toMillis(90)
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), CountryEntry.serializer())

        fun key(artist: ArtistRef): String = artist.browseId ?: ("name:" + artist.name.trim().lowercase(Locale.ROOT))
    }
}

/**
 * Artists' countries from MusicBrainz, for the country rules. Never blocks: [countryOf] answers
 * from the cache and queues the artist when it has no answer yet, so the artist shows now and
 * the answer applies from the next load. Lookups happen only while a country rule exists and the
 * switch is on, one at a time, at most one a second; a refused answer backs off and is not kept.
 */
@Singleton
class ArtistCountries @Inject constructor(
    @ApplicationContext context: Context,
    private val store: FilterStore,
    okHttp: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "artist-countries.json")
    private val cache = CountryCache()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val queue = Channel<ArtistRef>(Channel.UNLIMITED)

    // Its own TLS-only client: none of YouTube's headers, no cookies (OkHttp keeps none by default).
    private val client = MusicBrainzClient(
        HttpClient(OkHttp.create {
            preconfigured = okHttp.newBuilder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }) { expectSuccess = false },
        "Meteora/${BuildConfig.VERSION_NAME} (Android music player)",
    )

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { if (file.exists()) cache.load(file.readText()) }
            work()
        }
    }

    /** The country when known; null when unknown or not asked yet (then it is queued). */
    fun countryOf(artist: ArtistRef): String? {
        if (!store.settings.value.wantsCountries || artist.name.isBlank()) return null
        val key = CountryCache.key(artist)
        cache.lookup(key)?.let { return it.country }
        if (pending.size < MAX_PENDING && pending.add(key)) queue.trySend(artist)
        return null
    }

    private suspend fun work() {
        var backoff = 0L
        for (artist in queue) {
            val key = CountryCache.key(artist)
            try {
                if (!store.settings.value.wantsCountries || cache.lookup(key) != null) continue
                val started = System.currentTimeMillis()
                try {
                    val country = client.country(artist.name)
                    cache.put(key, country)
                    save()
                    backoff = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not answered: nothing is kept, and a later sighting asks again.
                    backoff = (if (backoff == 0L) FIRST_BACKOFF_MS else backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    Log.w(TAG, "lookup failed, waiting ${backoff / 1000} s: ${e.message}")
                    delay(backoff)
                }
                delay((MIN_INTERVAL_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun save() {
        runCatching { file.writeText(cache.encode()) }.onFailure { Log.w(TAG, "could not save the cache", it) }
    }

    private companion object {
        const val TAG = "ArtistCountries"
        /** MusicBrainz asks for at most one request a second; a little more, to be safe. */
        const val MIN_INTERVAL_MS = 1_100L
        const val FIRST_BACKOFF_MS = 10_000L
        const val MAX_BACKOFF_MS = 120_000L
        const val MAX_PENDING = 200
    }
}
