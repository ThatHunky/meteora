package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.LyricsProvider
import dev.thathunky.meteora.domain.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Synced lyrics from lrclib.net (`/api/get`, no key). Sends the track's title, artist, album and
 * length, and nothing else: no cookies, no account headers, a plain User-Agent. A 404 means not
 * found. Only timed lyrics are taken, and only when LRCLIB's length is within
 * [DURATION_TOLERANCE_SEC] of the track's: its own matching is looser (probed 2026-11-22).
 */
class LrcLibClient(private val http: HttpClient, private val userAgent: String) : LyricsSource {

    override suspend fun find(track: Track): Lyrics? {
        if (track.durationSec <= 0 || track.title.isBlank() || track.artist.isBlank()) return null
        val res = http.get(URL) {
            parameter("track_name", track.title)
            parameter("artist_name", track.artist)
            track.album?.takeIf { it.isNotBlank() }?.let { parameter("album_name", it) }
            parameter("duration", track.durationSec.roundToInt())
            header(HttpHeaders.UserAgent, userAgent)
        }
        if (res.status == HttpStatusCode.NotFound) return null
        if (!res.status.isSuccess()) throw IOException("LRCLIB: HTTP ${res.status.value}")
        return parse(res.bodyAsText(), track.durationSec)
    }

    companion object {
        const val URL = "https://lrclib.net/api/get"
        const val DURATION_TOLERANCE_SEC = 2.0
        private val json = Json { ignoreUnknownKeys = true }

        /** An `/api/get` answer to timed lyrics, or null when it has none or is for another length. */
        fun parse(body: String, trackDurationSec: Double): Lyrics? {
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if ((o["instrumental"] as? JsonPrimitive)?.booleanOrNull == true) return null
            val duration = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: return null
            if (abs(duration - trackDurationSec) > DURATION_TOLERANCE_SEC) return null
            val lrc = (o["syncedLyrics"] as? JsonPrimitive)?.contentOrNull ?: return null
            val lines = LrcParser.parse(lrc)
            if (lines.none { it.text.isNotBlank() }) return null
            return Lyrics(lines, synced = true, source = null, provider = LyricsProvider.LrcLib)
        }
    }
}
