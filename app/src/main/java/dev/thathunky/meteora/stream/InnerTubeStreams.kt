package dev.thathunky.meteora.stream

import android.os.SystemClock
import android.util.Log
import java.io.IOException
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

    suspend fun resolve(videoId: String): AudioStream = withContext(Dispatchers.IO) {
        val t = SystemClock.elapsedRealtime()
        var root = player(videoId, visitor())
        if (status(root) == "LOGIN_REQUIRED") {
            // Visitor id may have aged out: get a fresh one once.
            visitorData = null
            root = player(videoId, visitor())
        }
        val picked = pickAudio(root) ?: throw StreamException("player: ${status(root)} ${reason(root).orEmpty()} for $videoId")
        Log.i(TAG, "resolved $videoId in ${SystemClock.elapsedRealtime() - t} ms: itag ${picked.itag}")
        AudioStream(
            videoId = videoId,
            url = picked.url,
            headers = mapOf("User-Agent" to USER_AGENT),
            staleAtMs = SystemClock.elapsedRealtime() + (picked.expiresInSec * 1000 - 10 * 60_000).coerceAtLeast(60_000),
            source = "innertube",
        )
    }

    private suspend fun visitor(): String = visitorLock.withLock {
        visitorData ?: fetchVisitorData().also { visitorData = it }
    }

    /** `sw.js_data` is the small bootstrap blob YouTube's service worker reads; it carries the visitor id. */
    private fun fetchVisitorData(): String {
        val body = http.newCall(Request.Builder().url("https://www.youtube.com/sw.js_data").header("User-Agent", USER_AGENT).build())
            .execute().use { if (!it.isSuccessful) throw IOException("sw.js_data HTTP ${it.code}") else it.body.string() }
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

    data class Picked(val itag: Int, val url: String, val expiresInSec: Long)

    companion object {
        private const val TAG = "InnerTubeStreams"
        const val CLIENT_NAME = "VISIONOS"
        const val CLIENT_ID = "101"
        const val CLIENT_VERSION = "1.02"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        /** Opus 160k, then AAC 128k, then anything audio with a plain URL. */
        private val PREFERRED_ITAGS = listOf(251, 140, 250, 249, 139)

        fun status(root: JsonElement): String? = root.obj("playabilityStatus")?.string("status")
        private fun reason(root: JsonElement): String? = root.obj("playabilityStatus")?.string("reason")

        fun pickAudio(root: JsonElement): Picked? {
            if (status(root) != "OK") return null
            val sd = root.obj("streamingData") ?: return null
            val formats = (sd["adaptiveFormats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.string("mimeType")?.startsWith("audio/") == true && it.string("url") != null }
            val best = PREFERRED_ITAGS.firstNotNullOfOrNull { tag -> formats.firstOrNull { it.string("itag") == tag.toString() } }
                ?: formats.maxByOrNull { it.string("bitrate")?.toIntOrNull() ?: 0 }
                ?: return null
            return Picked(
                itag = best.string("itag")?.toIntOrNull() ?: 0,
                url = best.string("url")!!,
                expiresInSec = sd.string("expiresInSeconds")?.toLongOrNull() ?: 6 * 3600,
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
