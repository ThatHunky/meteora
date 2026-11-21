package dev.thathunky.meteora.stream

import android.os.SystemClock
import android.util.Log
import dev.thathunky.meteora.ytm.YtmAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Debug tool: asks `/player` as several clients, signed in, and logs which audio formats each
 * one offers (itag, quality label, bitrate, and whether the URL is plain or needs the signature
 * step). Then resolves High through [WebMusicStreams] and downloads the first MiB, to show a
 * deciphered URL really plays. Never logs URLs, cookies or tokens. Run with:
 * `adb shell am broadcast -a dev.thathunky.meteora.PROBE -p dev.thathunky.meteora --es v <videoId>`
 */
class StreamProbe(private val http: OkHttpClient, private val auth: YtmAuthProvider, private val webMusic: WebMusicStreams) {

    private data class Client(val label: String, val name: String, val id: String, val version: String, val userAgent: String, val extra: Map<String, String> = emptyMap())

    // Versions and user agents from the yt-dlp client table on the phone (2026.10.26).
    private val clients = listOf(
        Client("web_music", "WEB_REMIX", "67", "1.20260707.12.00", DESKTOP_UA),
        Client("tv", "TVHTML5", "7", "7.20260707.07.00", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"),
        Client("web", "WEB", "1", "2.20260708.00.00", DESKTOP_UA),
        Client(
            "ios", "IOS", "5", "21.26.4", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
        Client(
            "visionos", InnerTubeStreams.CLIENT_NAME, InnerTubeStreams.CLIENT_ID, InnerTubeStreams.CLIENT_VERSION, InnerTubeStreams.USER_AGENT,
            mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
        ),
    )

    suspend fun run(videoId: String) {
        val headers = auth.headers()
        Log.i(TAG, "probe $videoId, signed in: ${headers != null}")
        for (c in clients) {
            val line = runCatching { probe(c, videoId, headers.orEmpty()) }.getOrElse { "failed: $it" }
            Log.i(TAG, "${c.label}: $line")
        }
        val line = runCatching { probeWebMusic(videoId) }.getOrElse { "failed: $it" }
        Log.i(TAG, "web_music High, deciphered: $line")
    }

    /** The proof that a deciphered Premium URL downloads: resolve on High, then read the first MiB. */
    private suspend fun probeWebMusic(videoId: String): String {
        val (stream, picked) = webMusic.resolvePicked(videoId, StreamQuality.High)
        val t = SystemClock.elapsedRealtime()
        // Only the stream's own headers: googlevideo gets no cookies.
        val req = Request.Builder().url(stream.url)
            .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-1048575")
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                var bytes = 0L
                res.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        bytes += r
                    }
                }
                "itag ${picked.itag}, ${picked.bitrate / 1000} kbps, HTTP ${res.code}, $bytes bytes in ${SystemClock.elapsedRealtime() - t} ms"
            }
        }
    }

    private fun probe(c: Client, videoId: String, authHeaders: Map<String, String>): String {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name)
                    put("clientVersion", c.version)
                    put("hl", "en")
                    put("userAgent", c.userAgent)
                    c.extra.forEach { (k, v) -> put(k, v) }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.userAgent)
            .header("Origin", "https://music.youtube.com")
            .header("X-YouTube-Client-Name", c.id)
            .header("X-YouTube-Client-Version", c.version)
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { if (!it.isSuccessful) return "HTTP ${it.code}" else it.body.string() }
        val root = Json.parseToJsonElement(text) as JsonObject
        val status = (root["playabilityStatus"] as? JsonObject)?.str("status")
        val sd = root["streamingData"] as? JsonObject ?: return "status=$status, no streamingData"
        val audio = (sd["adaptiveFormats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("mimeType")?.startsWith("audio/") == true }
        val formats = audio.joinToString(" ") { f ->
            val how = when {
                f.str("url") != null -> "url"
                f.str("signatureCipher") != null || f.str("cipher") != null -> "cipher"
                else -> "none"
            }
            val codec = if (f.str("mimeType")?.contains("opus") == true) "opus" else "aac"
            val kbps = ((f.str("averageBitrate") ?: f.str("bitrate"))?.toIntOrNull() ?: 0) / 1000
            val drc = if (f.str("isDrc") == "true") "-drc" else ""
            "${f.str("itag")}$drc/$codec/${kbps}k/${f.str("audioQuality")?.removePrefix("AUDIO_QUALITY_")}/$how"
        }
        return "status=$status keys=${sd.keys.joinToString(",")} audio=[$formats]"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "StreamProbe"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
