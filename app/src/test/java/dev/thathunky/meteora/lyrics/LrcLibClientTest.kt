package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.LyricsProvider
import dev.thathunky.meteora.domain.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response shape as probed on 2026-11-22 (`/api/get`); the lyric text here is made up. */
class LrcLibClientTest {

    private fun body(duration: Double, synced: String? = "[00:01.00]one\n[00:04.50]two", instrumental: Boolean = false) =
        """{"id":1,"trackName":"T","artistName":"A","albumName":"B","duration":$duration,"instrumental":$instrumental,""" +
            """"plainLyrics":"one\ntwo","syncedLyrics":${synced?.let { "\"" + it.replace("\n", "\\n") + "\"" } ?: "null"}}"""

    @Test fun `duration within 2 s is accepted`() {
        val l = requireNotNull(LrcLibClient.parse(body(200.0), 201.9))
        assertTrue(l.synced)
        assertEquals(LyricsProvider.LrcLib, l.provider)
        assertEquals(listOf(1_000L, 4_500L), l.lines.map { it.startMs })
        assertNotNull(LrcLibClient.parse(body(200.0), 198.0))
    }

    @Test fun `duration further off is not found`() {
        assertNull(LrcLibClient.parse(body(200.0), 202.5))
        assertNull(LrcLibClient.parse(body(210.0), 200.0))
    }

    @Test fun `no synced lyrics, instrumental or junk is not found`() {
        assertNull(LrcLibClient.parse(body(200.0, synced = null), 200.0))
        assertNull(LrcLibClient.parse(body(200.0, instrumental = true), 200.0))
        assertNull(LrcLibClient.parse("not json", 200.0))
    }

    private val requests = mutableListOf<HttpRequestData>()
    private fun client(status: HttpStatusCode, text: String) = LrcLibClient(
        HttpClient(MockEngine { req ->
            requests += req
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false },
        "Meteora/test",
    )

    private val track = Track("abcdefghijk", "Song", "Artist", "Album", "MPREb_x", "UCx", 200.4, "https://art", LikeStatus.Like, false)

    @Test fun `sends title, artist, album and length only`() = runTest {
        val l = client(HttpStatusCode.OK, body(200.0)).find(track)
        assertNotNull(l)
        val req = requests.single()
        assertEquals("lrclib.net", req.url.host)
        assertEquals("/api/get", req.url.encodedPath)
        assertEquals(
            mapOf("track_name" to "Song", "artist_name" to "Artist", "album_name" to "Album", "duration" to "200"),
            req.url.parameters.entries().associate { it.key to it.value.single() },
        )
        assertNull(req.headers[HttpHeaders.Cookie])
        assertNull(req.headers[HttpHeaders.Authorization])
        assertEquals("Meteora/test", req.headers[HttpHeaders.UserAgent])
    }

    @Test fun `404 is not found and an unknown length is not asked`() = runTest {
        assertNull(client(HttpStatusCode.NotFound, """{"statusCode":404}""").find(track))
        assertNull(client(HttpStatusCode.OK, body(0.0)).find(track.copy(durationSec = 0.0)))
        assertEquals(1, requests.size)
    }

    @Test fun `no album leaves the parameter out`() = runTest {
        client(HttpStatusCode.OK, body(200.0)).find(track.copy(album = null))
        assertNull(requests.single().url.parameters["album_name"])
    }
}
