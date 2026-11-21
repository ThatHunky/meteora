package dev.thathunky.meteora.stream

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The fast path: one InnerTube `/player` call, the way YouTube's own apps ask for streams.
 * Uses the visionOS client, which (per yt-dlp 2026.10.26, which lists it as its only JS-less
 * default) returns plain googlevideo URLs: no signature / n-parameter cipher and no PO token.
 * Needs a visitor id, else YouTube answers "Sign in to confirm you're not a bot".
 * Verified 2026-11-21: ~0.3 s per track, ranged reads and mid-file seeks return 206.
 */
class InnerTubeStreams(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val visitorLock = Mutex()
    @Volatile private var visitorData: String? = null

    suspend fun resolve(videoId: String, quality: StreamQuality = StreamQuality.High): AudioStream = withContext(Dispatchers.IO) {
        val t = SystemClock.elapsedRealtime()
        var root = player(videoId, visitor())
        if (status(root) == "LOGIN_REQUIRED") {
            // Visitor id may have aged out: get a fresh one once.
            visitorData = null
            root = player(videoId, visitor())
        }
        val picked = pickAudio(root, quality) ?: throw StreamException("player: ${status(root)} ${reason(root).orEmpty()} for $videoId")
        Log.i(TAG, "resolved $videoId in ${SystemClock.elapsedRealtime() - t} ms: $quality → itag ${picked.itag}, ${picked.bitrate / 1000} kbps")
        AudioStream(
            videoId = videoId,
            url = picked.url,
            headers = mapOf("User-Agent" to USER_AGENT),
            staleAtMs = SystemClock.elapsedRealtime() + (picked.expiresInSec * 1000 - 10 * 60_000).coerceAtLeast(60_000),
            source = "innertube",
            cacheKey = "$videoId/${picked.itag}",
            quality = quality,
            contentLength = picked.contentLength,
        )
    }

    /** Fetch the visitor id (and open the connection to youtube.com) before the first track needs it. */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { visitor() }
    }

    private suspend fun visitor(): String = visitorLock.withLock {
        visitorData ?: fetchVisitorData().also { visitorData = it }
    }

    /** `sw.js_data` is the small bootstrap blob YouTube's service worker reads; it carries the visitor id. */
    private fun fetchVisitorData(): String {
        val body = http.newCall(Request.Builder().url("https://www.youtube.com/sw.js_data").header("User-Agent", USER_AGENT).build())
            .execute().use { if (!it.isSuccessful) throw StreamException("sw.js_data HTTP ${it.code}") else it.body.string() }
        return parseVisitorData(body) ?: throw StreamException("no visitor data")
    }

    private fun player(videoId: String, visitor: String): JsonElement {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("hl", "en")
                    put("visitorData", visitor)
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("X-Goog-Visitor-Id", visitor)
            .header("Origin", "https://www.youtube.com")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use {
            if (!it.isSuccessful) throw StreamException("player HTTP ${it.code}")
            it.body.string()
        }
        return json.parseToJsonElement(text)
    }

    /** [url] is empty when the format only has a [cipher] (`signatureCipher` query string). */
    data class Picked(val itag: Int, val url: String, val expiresInSec: Long, val bitrate: Int, val contentLength: Long? = null, val cipher: String? = null)

    companion object {
        private const val TAG = "InnerTubeStreams"
        const val CLIENT_NAME = "VISIONOS"
        const val CLIENT_ID = "101"
        const val CLIENT_VERSION = "1.02"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        /** Opus sounds better per bit, so it wins near-ties; a much higher-bitrate AAC still wins. */
        private const val OPUS_WEIGHT = 1.3

        fun status(root: JsonElement): String? = root.obj("playabilityStatus")?.string("status")
        fun reason(root: JsonElement): String? = root.obj("playabilityStatus")?.string("reason")

        private fun ceiling(quality: StreamQuality): Int = when (quality) {
            StreamQuality.Low -> 56_000
            StreamQuality.Normal -> 160_000
            StreamQuality.High -> Int.MAX_VALUE
        }

        /**
         * [allowCipher]: also take formats that only have a `signatureCipher` (the web music
         * client); the caller then has to decipher [Picked.cipher] into a URL.
         */
        fun pickAudio(root: JsonElement, quality: StreamQuality = StreamQuality.High, allowCipher: Boolean = false): Picked? {
            if (status(root) != "OK") return null
            val sd = root.obj("streamingData") ?: return null
            val all = (sd["adaptiveFormats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.string("mimeType")?.startsWith("audio/") == true }
                .filter { it.string("url") != null || (allowCipher && it.string("signatureCipher") != null) }
            // Loudness-compressed and dubbed tracks only when nothing else is there.
            val formats = all.filter { it.string("isDrc") != "true" && it.obj("audioTrack")?.string("audioIsDefault") != "false" }
                .ifEmpty { all }
            fun bitrate(f: JsonObject) = f.string("averageBitrate")?.toIntOrNull() ?: f.string("bitrate")?.toIntOrNull() ?: 0
            fun score(f: JsonObject) = bitrate(f) * (if (f.string("mimeType")?.contains("opus") == true) OPUS_WEIGHT else 1.0)
            val max = ceiling(quality)
            val best = formats.filter { bitrate(it) <= max }.maxByOrNull(::score)
                ?: formats.minByOrNull(::bitrate)
                ?: return null
            val url = best.string("url")
            return Picked(
                itag = best.string("itag")?.toIntOrNull() ?: 0,
                url = url.orEmpty(),
                cipher = if (url == null) best.string("signatureCipher") else null,
                expiresInSec = sd.string("expiresInSeconds")?.toLongOrNull() ?: 6 * 3600,
                bitrate = bitrate(best),
                contentLength = best.string("contentLength")?.toLongOrNull()?.takeIf { it > 0 },
            )
        }

        /** `)]}'` + JSON array; the visitor id is the first `Cg…` string in it. */
        fun parseVisitorData(body: String): String? {
            val start = body.indexOf('[')
            if (start < 0) return null
            val root = runCatching { Json.parseToJsonElement(body.substring(start)) }.getOrNull() ?: return null
            runCatching {
                root.jsonArray[0].jsonArray[2].jsonArray[0].jsonArray[0].jsonArray[13].let { (it as JsonPrimitive).contentOrNull }
            }.getOrNull()?.let { return it }
            fun walk(e: JsonElement): String? = when (e) {
                is JsonPrimitive -> e.contentOrNull?.takeIf { it.startsWith("Cg") && it.length in 20..64 }
                is JsonArray -> e.firstNotNullOfOrNull(::walk)
                is JsonObject -> e.values.firstNotNullOfOrNull(::walk)
            }
            return walk(root)
        }

        private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
