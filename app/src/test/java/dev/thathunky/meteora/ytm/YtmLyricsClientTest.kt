package dev.thathunky.meteora.ytm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyrics browse goes out signed out as ANDROID_MUSIC, and falls back to WEB_REMIX when that fails. */
class YtmLyricsClientTest {

    private val signedIn = YtmAuthProvider { mapOf(HttpHeaders.Cookie to "SID=test", HttpHeaders.Authorization to "SAPISIDHASH test") }
    private val requests = mutableListOf<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/ytm/$name")).readText()
    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
    private fun HttpRequestData.isAndroid() = bodyText().contains("\"clientName\":\"ANDROID_MUSIC\"")

    private fun client(android: (HttpRequestData) -> Pair<HttpStatusCode, String>) = YtmClient(
        HttpClient(MockEngine { req ->
            val (status, text) = when {
                req.method == HttpMethod.Get -> HttpStatusCode.OK to "" // the web client's version lookup
                req.isAndroid() -> { requests += req; android(req) }
                else -> { requests += req; HttpStatusCode.OK to fixture("lyrics-plain-web.json") }
            }
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false },
        signedIn,
    )

    @Test fun `android client is asked without the account's headers`() = runTest {
        val l = client { HttpStatusCode.OK to fixture("lyrics-timed.json") }.lyrics("MPLYt_test")
        assertTrue(l!!.synced)
        val req = requests.single()
        assertTrue(req.isAndroid())
        assertTrue(req.bodyText().contains("\"browseId\":\"MPLYt_test\""))
        assertNull(req.headers[HttpHeaders.Cookie])
        assertNull(req.headers[HttpHeaders.Authorization])
        assertEquals("21", req.headers["X-YouTube-Client-Name"])
    }

    @Test fun `a refused android call falls back to the web client's plain text`() = runTest {
        val l = client { HttpStatusCode.BadRequest to "{}" }.lyrics("MPLYt_test")
        assertFalse(l!!.synced)
        assertEquals(2, requests.size)
        assertFalse(requests[1].isAndroid())
        assertEquals("SID=test", requests[1].headers[HttpHeaders.Cookie]) // the web client is the account's
    }

    @Test fun `no lyrics on the android client is an answer, not a failure`() = runTest {
        assertNull(client { HttpStatusCode.OK to fixture("lyrics-none.json") }.lyrics("MPLYt_test"))
        assertEquals(1, requests.size)
    }
}
