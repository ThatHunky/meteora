package dev.thathunky.meteora.filter

import dev.thathunky.meteora.ytm.ArtistRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures: real anonymous answers from 2026-11-22, trimmed to the fields kept (fixtures/musicbrainz). */
class MusicBrainzTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/fixtures/musicbrainz/$name")) { name }.readText()

    private fun country(file: String, name: String) = MusicBrainzMatch.country(MusicBrainzMatch.parse(fixture(file)), name)

    @Test fun `the name or a Latin alias finds the country`() {
        assertEquals("UA", country("artist-okean-elzy.json", "Океан Ельзи"))
        assertEquals("UA", country("artist-okean-elzy.json", "Okean Elzy"))
        assertEquals("UA", country("artist-okean-elzy.json", "okean elzy"))
        assertEquals("RU", country("artist-kino.json", "Кино"))
        assertEquals("RU", country("artist-kino.json", "Kino"))
    }

    @Test fun `a lower-scored namesake does not count`() {
        // The UK Nirvana scores 81, under the 95 needed.
        assertEquals("US", country("artist-nirvana.json", "Nirvana"))
    }

    @Test fun `no equal name is unknown`() {
        assertNull(country("artist-kino.json", "Кино ФРГ 2"))
        assertNull(country("artist-nirvana.json", "Nirvana Tribute"))
        assertNull(MusicBrainzMatch.country(MusicBrainzMatch.parse("""{"count":0,"artists":[]}"""), "X"))
        assertNull(MusicBrainzMatch.country(MusicBrainzMatch.parse("not json"), "X"))
    }

    @Test fun `equal names with different countries are unknown`() {
        val two = listOf(MbCandidate("Nirvana", emptyList(), 100, "US"), MbCandidate("Nirvana", emptyList(), 97, "GB"))
        assertNull(MusicBrainzMatch.country(two, "Nirvana"))
        val same = listOf(MbCandidate("X", emptyList(), 100, "UA"), MbCandidate("x", emptyList(), 96, "UA"))
        assertEquals("UA", MusicBrainzMatch.country(same, "X"))
        assertNull(MusicBrainzMatch.country(listOf(MbCandidate("X", emptyList(), 100, null)), "X"))
    }

    @Test fun `the parser reads the fields kept`() {
        val c = MusicBrainzMatch.parse(fixture("artist-nirvana.json"))
        assertEquals(5, c.size)
        assertEquals(MbCandidate("Nirvana", listOf("ニルヴァーナ", "Nirvana", "Nirvana US"), 100, "US"), c.first())
    }

    @Test fun `the query is a quoted phrase with specials escaped`() {
        assertEquals("\"AC\\/DC\"", MusicBrainzMatch.query("AC/DC"))
        assertEquals("\"\\!\\!\\!\"", MusicBrainzMatch.query("!!!"))
        assertEquals("\"Say \\\"Hi\\\"\"", MusicBrainzMatch.query(" Say \"Hi\" "))
    }

    private val requests = mutableListOf<HttpRequestData>()
    private fun client(status: HttpStatusCode, text: String) = MusicBrainzClient(
        HttpClient(MockEngine { req ->
            requests += req
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false },
        "Meteora/test (Android music player)",
    )

    @Test fun `sends the name only, over TLS, with the plain User-Agent`() = runTest {
        assertEquals("UA", client(HttpStatusCode.OK, fixture("artist-okean-elzy.json")).country("Okean Elzy"))
        val req = requests.single()
        assertEquals("https", req.url.protocol.name)
        assertEquals("musicbrainz.org", req.url.host)
        assertEquals("/ws/2/artist", req.url.encodedPath)
        assertEquals(
            mapOf("query" to "\"Okean Elzy\"", "fmt" to "json", "limit" to "5"),
            req.url.parameters.entries().associate { it.key to it.value.single() },
        )
        assertNull(req.headers[HttpHeaders.Cookie])
        assertNull(req.headers[HttpHeaders.Authorization])
        assertEquals("Meteora/test (Android music player)", req.headers[HttpHeaders.UserAgent])
    }

    @Test fun `a busy answer throws instead of meaning unknown`() = runTest {
        try {
            client(HttpStatusCode.ServiceUnavailable, """{"error": "The MusicBrainz web server is currently busy."}""").country("Kino")
            fail("expected an exception")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    // ---- the cache ----

    @Test fun `answers are kept 90 days, unknown included`() {
        var now = 1_000L
        val cache = CountryCache { now }
        cache.put("UC1", "UA")
        cache.put("name:x", null)
        assertEquals("UA", cache.lookup("UC1")?.country)
        assertTrue(cache.lookup("name:x") != null && cache.lookup("name:x")!!.country == null)
        assertNull(cache.lookup("UC2"))
        now += CountryCache.TTL_MS
        assertNull(cache.lookup("UC1"))
    }

    @Test fun `the cache survives a save and drops what expired`() {
        var now = 0L
        val a = CountryCache { now }
        a.put("UC1", "RU")
        now = CountryCache.TTL_MS - 10
        a.put("UC2", "UA")
        now = CountryCache.TTL_MS + 1
        val b = CountryCache { now }
        b.load(a.encode())
        assertNull(b.lookup("UC1"))
        assertEquals("UA", b.lookup("UC2")?.country)
        b.load("garbage")
        assertEquals("UA", b.lookup("UC2")?.country)
    }

    @Test fun `an answer is found again by id or by name`() {
        val cache = CountryCache { 0L }
        cache.putFor(ArtistRef("Кино", "UC1"), "RU")
        assertEquals("RU", cache.find(ArtistRef("Кино", "UC1"))?.country)
        // The Test field types a name and has no id.
        assertEquals("RU", cache.find(ArtistRef("кино", null))?.country)
        assertNull(cache.find(ArtistRef("Сплин", null)))
    }

    @Test fun `keys are the browse id, else the name`() {
        assertEquals("UC1", CountryCache.key(ArtistRef("Kino", "UC1")))
        assertEquals("name:кино", CountryCache.key(ArtistRef(" Кино ", null)))
    }
}
