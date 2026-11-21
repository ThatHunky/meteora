package dev.thathunky.meteora.stream

import android.os.SystemClock
import android.util.Log
import dev.thathunky.meteora.ytm.YtmAuthProvider
import dev.thathunky.meteora.ytm.YtmClient
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Premium path: `/player` as the signed-in web music client, which offers 141 (AAC 256k)
 * and 774 (Opus 256k) to Premium accounts. Its URLs need the player's JavaScript run on them
 * (signature and `n`), done by [solver]; no PO token is needed for Premium (yt-dlp 2026.10.26).
 * Cookies go to music.youtube.com only, never to the stream host.
 */
class WebMusicStreams(
    http: OkHttpClient,
    private val auth: YtmAuthProvider,
    private val solver: JsSolver,
    /** Where base.js is kept between runs; null keeps it in memory only. */
    private val cacheDir: File? = null,
) {
    // A redirect would carry the hand-set Cookie header to wherever it points (OkHttp only drops Authorization).
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val playerLock = Mutex()
    @Volatile private var player: PlayerInfo? = null
    @Volatile private var playerAt = 0L

    /** False for the rest of the process once YouTube asks this account for a bot check. */
    @Volatile var usable = true
        private set

    private class PlayerInfo(val id: String, val clientVersion: String, val sts: Int, val js: String)

    suspend fun resolve(videoId: String, quality: StreamQuality): AudioStream = resolvePicked(videoId, quality).first

    /** [resolve], plus the picked format for the probe's log. */
    internal suspend fun resolvePicked(videoId: String, quality: StreamQuality): Pair<AudioStream, InnerTubeStreams.Picked> = withContext(Dispatchers.IO) {
        if (!usable) throw StreamException("web music path is off")
        val headers = auth.headers() ?: throw StreamException("not signed in")
        val t = SystemClock.elapsedRealtime()
        val (info, from) = playerInfo(headers)
        try {
            val root = player(videoId, info, headers)
            val status = InnerTubeStreams.status(root)
            if (status == "LOGIN_REQUIRED" || InnerTubeStreams.reason(root)?.contains("bot", ignoreCase = true) == true) {
                usable = false
                Log.w(TAG, "web music: $status for $videoId, not using this path again until restart")
                throw StreamException("web music: $status")
            }
            val picked = InnerTubeStreams.pickAudio(root, quality, allowCipher = true)
                ?: throw StreamException("web music player: $status for $videoId")
            val url = try {
                decipher(picked, info.id, { info.js }, solver)
            } catch (e: StreamException) {
                // A bad copy on disk would otherwise be read again until YouTube ships a new player.
                playerFile(info.id)?.delete()
                throw e
            }
            Log.i(TAG, "resolved $videoId in ${SystemClock.elapsedRealtime() - t} ms: $quality → itag ${picked.itag}, ${picked.bitrate / 1000} kbps, player from $from")
            AudioStream(
                videoId = videoId,
                url = url,
                headers = mapOf("User-Agent" to YtmClient.USER_AGENT, "Origin" to YtmClient.ORIGIN, "Referer" to "${YtmClient.ORIGIN}/"),
                staleAtMs = SystemClock.elapsedRealtime() + (picked.expiresInSec * 1000 - 10 * 60_000).coerceAtLeast(60_000),
                source = SOURCE,
                cacheKey = "$videoId/${picked.itag}",
                quality = quality,
                contentLength = picked.contentLength,
            ) to picked
        } catch (e: StreamException) {
            // The player may have changed under us: read it again next time.
            player = null
            throw e
        }
    }

    /** Load the player and have the solver preprocess it, so the first High track does not wait for that. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val headers = auth.headers() ?: return@withContext
        if (!usable) return@withContext
        val (info, _) = playerInfo(headers)
        solver.solve(info.id, { info.js }, emptyList(), emptyList())
    }

    /** The current player, read from the page at most every 6 hours. Second value: where base.js came from, for the log. */
    private suspend fun playerInfo(headers: Map<String, String>): Pair<PlayerInfo, String> = playerLock.withLock {
        player?.takeIf { SystemClock.elapsedRealtime() - playerAt < PLAYER_REFRESH_MS }?.let { return@withLock it to "memory" }
        val page = get("${YtmClient.ORIGIN}/", headers)
        val p = parsePlayerPage(page) ?: throw StreamException("no player on the music.youtube.com page")
        val file = playerFile(p.playerId)
        val cached = file?.takeIf { it.isFile && it.length() > 0 }?.let { runCatching { it.readText() }.getOrNull() }
        // base.js needs no cookies.
        val js = cached ?: get("${YtmClient.ORIGIN}${p.jsUrl}", emptyMap()).also { text ->
            if (file != null) runCatching {
                val dir = file.parentFile!!
                dir.mkdirs()
                dir.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
                // Written whole, then renamed, so a cut-off write never looks like a player.
                val tmp = File(dir, "${file.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
        val sts = parseSignatureTimestamp(js) ?: run {
            file?.delete()
            throw StreamException("no signature timestamp in player ${p.playerId}")
        }
        val info = PlayerInfo(p.playerId, p.clientVersion, sts, js)
        player = info
        playerAt = SystemClock.elapsedRealtime()
        info to (if (cached != null) "disk" else "network")
    }

    private fun playerFile(playerId: String): File? = cacheDir?.let { File(File(it, "player"), "$playerId.js") }

    private fun get(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", YtmClient.USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw StreamException("web music GET HTTP ${it.code}")
            it.body.string()
        }
    }

    private fun player(videoId: String, info: PlayerInfo, headers: Map<String, String>): JsonElement {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", info.clientVersion)
                    put("hl", "en")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") { put("signatureTimestamp", info.sts) }
            }
        }
        val req = Request.Builder()
            .url("${YtmClient.ORIGIN}/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", YtmClient.USER_AGENT)
            .header("Origin", YtmClient.ORIGIN)
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", info.clientVersion)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use {
            if (!it.isSuccessful) throw StreamException("web music player HTTP ${it.code}")
            it.body.string()
        }
        return runCatching { json.parseToJsonElement(text) }.getOrElse { throw StreamException("web music player: not JSON") }
    }

    data class PlayerPage(val jsUrl: String, val playerId: String, val clientVersion: String)

    /** A `signatureCipher` query string: [s] scrambled signature, [sp] the parameter it goes in, [url] the base URL. */
    data class Cipher(val s: String, val sp: String, val url: String)

    companion object {
        private const val TAG = "WebMusicStreams"
        const val SOURCE = "web_music"
        private const val CLIENT_ID = "67"
        private const val PLAYER_REFRESH_MS = 6 * 60 * 60_000L

        private val JS_URL = Regex("\"jsUrl\":\"(/s/player/[^\"]+?/base\\.js)\"")
        private val CLIENT_VERSION = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"")
        private val STS = Regex("(?:signatureTimestamp|sts)\\s*:\\s*(\\d{5})")
        // Also the file name on disk, so nothing that could leave the folder.
        private val PLAYER_ID = Regex("[A-Za-z0-9_-]+")

        fun parsePlayerPage(html: String): PlayerPage? {
            val jsUrl = JS_URL.find(html)?.groupValues?.get(1) ?: return null
            val id = jsUrl.removePrefix("/s/player/").substringBefore('/').takeIf { PLAYER_ID.matches(it) } ?: return null
            val version = CLIENT_VERSION.find(html)?.groupValues?.get(1) ?: YtmClient.FALLBACK_CLIENT_VERSION
            return PlayerPage(jsUrl, id, version)
        }

        fun parseSignatureTimestamp(js: String): Int? = STS.find(js)?.groupValues?.get(1)?.toIntOrNull()

        fun parseCipher(cipher: String): Cipher? {
            val q = parseQuery(cipher)
            val s = q["s"] ?: return null
            val url = q["url"] ?: return null
            return Cipher(s, q["sp"]?.ifEmpty { null } ?: "signature", url)
        }

        /**
         * The playable URL for [picked]: the deciphered signature added (when ciphered), and the
         * `n` parameter replaced by its solved value. Both are asked of [solver] in one call.
         * Error messages never contain the URL or the challenge values.
         */
        suspend fun decipher(picked: InnerTubeStreams.Picked, playerId: String, playerJs: suspend () -> String, solver: JsSolver): String {
            val cipher = picked.cipher?.let { parseCipher(it) ?: throw StreamException("bad signatureCipher for itag ${picked.itag}") }
            val base = cipher?.url ?: picked.url.ifEmpty { throw StreamException("no URL for itag ${picked.itag}") }
            val n = queryParam(base, "n")
            val sig = listOfNotNull(cipher?.s)
            if (sig.isEmpty() && n == null) return base
            val solved = solver.solve(playerId, playerJs, sig, listOfNotNull(n))
            var url = base
            if (cipher != null) {
                val s = solved.sig[cipher.s] ?: throw StreamException("signature not solved")
                url += (if ('?' in url) "&" else "?") + "${cipher.sp}=${encode(s)}"
            }
            if (n != null) url = replaceQueryParam(url, "n", solved.n[n] ?: throw StreamException("n not solved"))
            return url
        }

        fun queryParam(url: String, name: String): String? = parseQuery(url.substringAfter('?', ""))[name]

        fun replaceQueryParam(url: String, name: String, value: String): String {
            if ('?' !in url) return url
            val query = url.substringAfter('?').split('&').joinToString("&") { part ->
                if (decode(part.substringBefore('=')) == name) "$name=${encode(value)}" else part
            }
            return "${url.substringBefore('?')}?$query"
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }

        // The String-charset overloads: the Charset ones need API 33.
        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
