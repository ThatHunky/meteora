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
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    suspend fun browse(browseId: String): JsonElement = post("browse", buildJsonObject { put("browseId", browseId) })

    private suspend fun post(endpoint: String, body: JsonObject): JsonElement {
        ensureClientVersion()
        val locale = Locale.getDefault()
        val full = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", clientVersion)
                    put("hl", if (locale.language == "uk") "uk" else "en")
                    put("gl", locale.country.ifBlank { "UA" })
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val authHeaders = auth.headers()
        val res = http.post("$BASE/$endpoint?prettyPrint=false") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Referrer, "$ORIGIN/")
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-Goog-Api-Format-Version", "1")
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
        private const val BASE = "$ORIGIN/youtubei/v1"
        const val FALLBACK_CLIENT_VERSION = "1.20261117.06.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
