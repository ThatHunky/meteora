package dev.thathunky.meteora.stream

import android.os.SystemClock
import android.util.Log
import dev.thathunky.meteora.core.MeteoraCore
import dev.thathunky.meteora.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
 * The response is read by the Rust core ([MeteoraCore]); this class only does the HTTP.
 */
class InnerTubeStreams(private val http: OkHttpClient) {

    private val visitorLock = Mutex()
    @Volatile private var visitorData: String? = null

    /** [container]: `mp4` for AAC only (downloads); empty for any. */
    suspend fun resolve(videoId: String, quality: StreamQuality = StreamQuality.High, container: String = ""): AudioStream = withContext(Dispatchers.IO) {
        val t = SystemClock.elapsedRealtime()
        var answer = pickAudio(player(videoId, visitor()), quality, container = container)
        if (answer.status == "LOGIN_REQUIRED") {
            // Visitor id may have aged out: get a fresh one once.
            visitorData = null
            answer = pickAudio(player(videoId, visitor()), quality, container = container)
        }
        val picked = answer.picked ?: throw StreamException("player: ${answer.status} ${answer.reason.orEmpty()} for $videoId")
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

    /**
     * The video-only format for [videoId] up to [quality]'s height, in the first of [codecs] that
     * has that height. Same call as for audio: visionOS video URLs need no solving either
     * (docs/api-notes.md, "Video formats").
     */
    suspend fun resolveVideo(videoId: String, quality: VideoQuality, codecs: String): VideoStream = withContext(Dispatchers.IO) {
        val t = SystemClock.elapsedRealtime()
        var json = player(videoId, visitor())
        var r = MeteoraCore.call { MeteoraCore.pickVideo(json, quality.maxHeight, codecs, false) }
        if (r.text("status") == "LOGIN_REQUIRED") {
            visitorData = null
            json = player(videoId, visitor())
            r = MeteoraCore.call { MeteoraCore.pickVideo(json, quality.maxHeight, codecs, false) }
        }
        val f = r["format"] as? JsonObject
            ?: throw StreamException("player: no video for $videoId (${r.text("status")} ${r.text("reason").orEmpty()})")
        val itag = f.text("itag")?.toIntOrNull() ?: 0
        val expires = f.text("expires_in_sec")?.toLongOrNull() ?: 6 * 3600
        val stream = VideoStream(
            videoId = videoId,
            url = f.text("url") ?: throw StreamException("no video URL for $videoId"),
            headers = mapOf("User-Agent" to USER_AGENT),
            staleAtMs = SystemClock.elapsedRealtime() + (expires * 1000 - 10 * 60_000).coerceAtLeast(60_000),
            itag = itag,
            height = f.text("height")?.toIntOrNull() ?: 0,
            codec = f.text("codec").orEmpty(),
            cacheKey = "$videoId/$itag",
            quality = quality,
            contentLength = f.text("content_length")?.toLongOrNull(),
        )
        Log.i(TAG, "video $videoId in ${SystemClock.elapsedRealtime() - t} ms: $quality → itag $itag, ${stream.codec} ${stream.height}p, ${(f.text("bitrate")?.toLongOrNull() ?: 0) / 1000} kbps")
        stream
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
        return MeteoraCore.call { parseVisitorData(body) }.text("visitor_data") ?: throw StreamException("no visitor data")
    }

    /** The `/player` response text. */
    private fun player(videoId: String, visitor: String): String {
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
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw StreamException("player HTTP ${it.code}")
            it.body.string()
        }
    }

    /** [url] is empty when the format only has a [cipher] (`signatureCipher` query string). */
    data class Picked(val itag: Int, val url: String, val expiresInSec: Long, val bitrate: Int, val contentLength: Long? = null, val cipher: String? = null) {
        /** The form the core takes it back in. */
        fun toCoreJson(): String = buildJsonObject {
            put("itag", itag)
            put("url", url)
            put("cipher", cipher)
        }.toString()
    }

    /** What `/player` said: [status] and [reason] from `playabilityStatus`, and the picked format, null when nothing is playable. */
    class PlayerAnswer(val status: String?, val reason: String?, val picked: Picked?)

    companion object {
        private const val TAG = "InnerTubeStreams"
        const val CLIENT_NAME = "VISIONOS"
        const val CLIENT_ID = "101"
        const val CLIENT_VERSION = "1.02"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        /**
         * The tier's format from a `/player` response, picked by the core (rules and tests in
         * rust/meteora-core). [allowCipher]: also take formats that only have a
         * `signatureCipher` (the web music client); the caller then has to decipher [Picked.cipher].
         */
        fun pickAudio(playerJson: String, quality: StreamQuality, allowCipher: Boolean = false, container: String = ""): PlayerAnswer {
            val r = MeteoraCore.call {
                if (container.isEmpty()) MeteoraCore.pickAudio(playerJson, quality.name, allowCipher)
                else MeteoraCore.pickAudioIn(playerJson, quality.name, allowCipher, container)
            }
            val f = r["format"] as? JsonObject
            return PlayerAnswer(
                status = r.text("status"),
                reason = r.text("reason"),
                picked = f?.let {
                    Picked(
                        itag = it.text("itag")?.toIntOrNull() ?: 0,
                        url = it.text("url").orEmpty(),
                        cipher = it.text("cipher"),
                        expiresInSec = it.text("expires_in_sec")?.toLongOrNull() ?: 6 * 3600,
                        bitrate = it.text("bitrate")?.toIntOrNull() ?: 0,
                        contentLength = it.text("content_length")?.toLongOrNull(),
                    )
                },
            )
        }
    }
}
