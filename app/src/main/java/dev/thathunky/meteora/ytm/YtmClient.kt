package dev.thathunky.meteora.ytm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import dev.thathunky.meteora.domain.Lyrics
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class YtmException(val status: Int, message: String) : Exception(message) {
    val isAuthProblem get() = status == 401 || status == 403
}

/** Extra headers for a signed-in request (cookie + SAPISIDHASH). Null = signed out. */
fun interface YtmAuthProvider {
    suspend fun headers(): Map<String, String>?
}

/**
 * Unofficial YouTube Music InnerTube client (WEB_REMIX). HTTPS only. Works signed-out for
 * search / album / artist / playlist pages; Home and Library need [auth] (M4b).
 */
class YtmClient(
    private val http: HttpClient,
    private val auth: YtmAuthProvider = YtmAuthProvider { null },
) {
    @Volatile private var clientVersion: String = FALLBACK_CLIENT_VERSION
    @Volatile private var versionChecked = false
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.All): SearchResults =
        YtmParser.search(post("search", buildJsonObject {
            put("query", query)
            filter.params?.let { put("params", it) }
        }))

    suspend fun suggestions(input: String): List<String> =
        YtmParser.suggestions(post("music/get_search_suggestions", buildJsonObject { put("input", input) }))

    suspend fun album(browseId: String): CollectionPage = YtmParser.collection(browse(browseId))

    suspend fun playlist(playlistId: String): CollectionPage =
        YtmParser.collection(browse(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"))

    suspend fun artist(browseId: String): ArtistPage = YtmParser.artist(browse(browseId))

    suspend fun home(): List<Shelf> = YtmParser.shelves(browse("FEmusic_home"))

    suspend fun likedSongs(): CollectionPage = YtmParser.collection(browse("VLLM"))

    suspend fun libraryPlaylists(): List<MusicItem> =
        YtmParser.shelves(browse("FEmusic_liked_playlists")).flatMap { it.items }
            .ifEmpty { browse("FEmusic_liked_playlists").findAll("musicTwoRowItemRenderer").mapNotNull(YtmParser::twoRowItem) }

    /** What the YTM player would queue for this video and/or playlist (radio when only a video). */
    suspend fun watchQueue(videoId: String? = null, playlistId: String? = null, params: String? = null): WatchQueue =
        YtmParser.watchQueue(nextRaw(videoId, playlistId, params))

    /** The raw `next` response behind [watchQueue]. [params]: from a `watchPlaylistEndpoint` (automix). */
    internal suspend fun nextRaw(videoId: String? = null, playlistId: String? = null, params: String? = null): JsonElement {
        require(videoId != null || playlistId != null)
        return post("next", buildJsonObject {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId ?: "RDAMVM$videoId")
            params?.let { put("params", it) }
            put("enablePersistentPlaylistPanel", true)
            put("isAudioOnly", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        })
    }

    /** More radio tracks after [WatchQueue.continuation]. */
    suspend fun watchQueueContinuation(continuation: String): WatchQueue =
        YtmParser.watchQueue(post("next", buildJsonObject {
            put("continuation", continuation)
            put("enablePersistentPlaylistPanel", true)
            put("isAudioOnly", true)
        }))

    /** Like / dislike / clear for the signed-in account. */
    suspend fun rate(videoId: String, status: dev.thathunky.meteora.domain.LikeStatus) {
        val endpoint = when (status) {
            dev.thathunky.meteora.domain.LikeStatus.Like -> "like/like"
            dev.thathunky.meteora.domain.LikeStatus.Dislike -> "like/dislike"
            else -> "like/removelike"
        }
        post(endpoint, buildJsonObject { putJsonObject("target") { put("videoId", videoId) } })
    }

    /**
     * Sends one of an item's [ItemFeedback] tokens ("Not interested", "Don't recommend artist"):
     * it changes the whole account's recommendations. True only when YouTube answers that it
     * processed it. False, never a throw, when signed out (nothing is sent), on an HTTP error or
     * any failure. The token and the answer's body are never logged: only the outcome.
     */
    suspend fun feedback(token: String): Boolean {
        if (auth.headers() == null) return false
        val ok = try {
            YtmParser.feedbackProcessed(post("feedback", buildJsonObject { putJsonArray("feedbackTokens") { add(token) } }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.i(FEEDBACK_TAG, "feedback failed: ${e.javaClass.simpleName}${(e as? YtmException)?.let { " HTTP ${it.status}" }.orEmpty()}") }
            return false
        }
        runCatching { android.util.Log.i(FEEDBACK_TAG, "feedback isProcessed=$ok") }
        return ok
    }

    suspend fun browse(browseId: String): JsonElement = post("browse", buildJsonObject { put("browseId", browseId) })

    /** The lyrics tab's browse id for [videoId] (one `next` call), or null when the response has none. */
    suspend fun lyricsId(videoId: String): String? = YtmParser.lyricsId(nextRaw(videoId))

    /**
     * Lyrics behind a [lyricsId]. Timed ones only come to the Android music client, asked signed
     * out (docs/api-notes.md › Lyrics); if that call fails, the web client's plain text, marked
     * [Lyrics.stopgap] so it is not taken as the track's answer. Null: the track has no lyrics.
     * Throws when neither call gets an answer.
     */
    suspend fun lyrics(browseId: String): Lyrics? {
        val body = buildJsonObject { put("browseId", browseId) }
        val android = try {
            post("browse", body, android = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (android != null) return YtmParser.lyrics(android)
        return YtmParser.lyrics(post("browse", body))?.copy(stopgap = true)
    }

    private suspend fun post(endpoint: String, body: JsonObject, android: Boolean = false): JsonElement {
        if (!android) ensureClientVersion()
        val locale = Locale.getDefault()
        val version = if (android) ANDROID_MUSIC_VERSION else clientVersion
        val full = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    if (android) {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", version)
                        put("androidSdkVersion", 30)
                        put("osName", "Android")
                        put("osVersion", "11")
                    } else {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", version)
                    }
                    put("hl", if (locale.language == "uk") "uk" else "en")
                    put("gl", locale.country.ifBlank { "UA" })
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        // The Android client is always asked signed out: never with the account's cookies.
        val authHeaders = if (android) null else auth.headers()
        val res = http.post("$BASE/$endpoint?prettyPrint=false") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, ORIGIN)
            if (android) {
                header(HttpHeaders.UserAgent, ANDROID_MUSIC_USER_AGENT)
                header("X-YouTube-Client-Name", "21")
                header("X-YouTube-Client-Version", version)
            } else {
                header(HttpHeaders.Referrer, "$ORIGIN/")
                header(HttpHeaders.UserAgent, USER_AGENT)
                header("X-Goog-Api-Format-Version", "1")
            }
            authHeaders?.forEach { (k, v) -> header(k, v) }
            setBody(full.toString())
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) throw YtmException(res.status.value, "InnerTube $endpoint: HTTP ${res.status.value}")
        return json.parseToJsonElement(text)
    }

    /** The web client version drifts; read the current one from the page once per process. */
    private suspend fun ensureClientVersion() {
        if (versionChecked) return
        versionChecked = true
        runCatching {
            val page = http.get(ORIGIN) { header(HttpHeaders.UserAgent, USER_AGENT) }.bodyAsText()
            Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"").find(page)?.groupValues?.get(1)
        }.getOrNull()?.let { clientVersion = it }
    }

    enum class SearchFilter(val params: String?) {
        All(null),
        // Filter params, each verified live on 2026-11-21 (see docs/api-notes.md).
        Songs("EgWKAQIIAWoMEAMQBBAJEA4QChAF"),
        Albums("EgWKAQIYAWoMEAMQBBAJEA4QChAF"),
        Artists("EgWKAQIgAWoMEAMQBBAJEA4QChAF"),
        Playlists("EgeKAQQoAEABagoQAxAEEAoQCRAF"),
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        /** Logcat tag for the feedback call's outcome (never its token). */
        const val FEEDBACK_TAG = "YtmFeedback"
        private const val BASE = "$ORIGIN/youtubei/v1"
        const val FALLBACK_CLIENT_VERSION = "1.20261117.06.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        /** Probed 2026-11-22; version 5.01 got HTTP 400, so an old version is refused. */
        const val ANDROID_MUSIC_VERSION = "7.27.52"
        private const val ANDROID_MUSIC_USER_AGENT = "com.google.android.apps.youtube.music/$ANDROID_MUSIC_VERSION (Linux; U; Android 11) gzip"
    }
}
