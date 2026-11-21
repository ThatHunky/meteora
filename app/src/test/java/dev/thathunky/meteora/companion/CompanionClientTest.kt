package dev.thathunky.meteora.companion

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionClientTest {

    private val ep = Endpoint("192.168.1.2")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        CompanionClient(
            CompanionClient.defaultHttpClient(
                MockEngine { req ->
                    requests += req
                    val (status, body) = handler(req)
                    respond(body, status, json)
                },
            ),
        )

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test fun `pairing flow`() = runTest {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/api/v1/auth/requestcode" -> HttpStatusCode.OK to """{"code":"3608"}"""
                "/api/v1/auth/request" -> HttpStatusCode.OK to """{"token":"tok"}"""
                else -> error(req.url)
            }
        }
        assertEquals("3608", c.requestCode(ep, "0.1.0"))
        assertEquals("tok", c.requestToken(ep, "3608"))
        assertEquals("""{"appId":"meteora","appName":"Meteora","appVersion":"0.1.0"}""", requests[0].bodyText())
        assertEquals("""{"appId":"meteora","code":"3608"}""", requests[1].bodyText())
        assertEquals("http://192.168.1.2:9863/api/v1/auth/requestcode", requests[0].url.toString())
    }

    @Test fun `command sends raw token and json body`() = runTest {
        val c = client { HttpStatusCode.NoContent to "" }
        c.send(ep, "tok", Command.ChangeVideo(playlistId = "LM"))
        val r = requests.single()
        assertEquals("tok", r.headers[HttpHeaders.Authorization]) // no "Bearer "
        assertEquals("""{"command":"changeVideo","data":{"playlistId":"LM"}}""", r.bodyText())
    }

    @Test fun `errors are mapped`() = runTest {
        val disabled = client {
            HttpStatusCode.Forbidden to """{"statusCode":403,"code":"AUTHORIZATION_DISABLED","error":"Forbidden","message":"Authorization requests are disabled"}"""
        }
        val e1 = runCatching { disabled.requestCode(ep, "0.1.0") }.exceptionOrNull() as CompanionException
        assertTrue(e1.isAuthorizationDisabled)

        val unauthorized = client { HttpStatusCode.Unauthorized to """{"error":"UNAUTHORIZED"}""" }
        val e2 = runCatching { unauthorized.state(ep, "bad") }.exceptionOrNull() as CompanionException
        assertTrue(e2.isUnauthorized)

        val limited = client { HttpStatusCode.TooManyRequests to """{"statusCode":429,"error":"Too Many Requests"}""" }
        val e3 = runCatching { limited.send(ep, "t", Command.Next) }.exceptionOrNull() as CompanionException
        assertTrue(e3.isRateLimited)
    }

    @Test fun `public hosts are refused before any request`() {
        val c = client { error("must not be called") }
        assertThrows(HostNotAllowedException::class.java) {
            kotlinx.coroutines.runBlocking { c.metadata(Endpoint("8.8.8.8")) }
        }
        assertTrue(requests.isEmpty())
    }
}
