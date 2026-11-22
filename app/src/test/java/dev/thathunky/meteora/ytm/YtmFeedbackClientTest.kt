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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feedback call: one signed-in WEB_REMIX POST with the token, true only on isProcessed. */
class YtmFeedbackClientTest {

    private val signedIn = YtmAuthProvider { mapOf(HttpHeaders.Cookie to "SID=test", HttpHeaders.Authorization to "SAPISIDHASH test") }
    private val requests = mutableListOf<HttpRequestData>()

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun client(auth: YtmAuthProvider = signedIn, answer: () -> Pair<HttpStatusCode, String>) = YtmClient(
        HttpClient(MockEngine { req ->
            val (status, text) = if (req.method == HttpMethod.Get) HttpStatusCode.OK to "" else { requests += req; answer() }
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false },
        auth,
    )

    @Test fun `the request is a signed-in WEB_REMIX feedback POST with only the token`() = runTest {
        assertTrue(client { HttpStatusCode.OK to """{"feedbackResponses":[{"isProcessed":true}]}""" }.feedback("FAKE_TOKEN"))
        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/youtubei/v1/feedback", req.url.encodedPath)
        assertEquals("SID=test", req.headers[HttpHeaders.Cookie])
        val body = Json.parseToJsonElement(req.bodyText()) as JsonObject
        assertEquals(setOf("context", "feedbackTokens"), body.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("FAKE_TOKEN"))), body["feedbackTokens"])
        assertEquals(JsonPrimitive("WEB_REMIX"), (body["context"] as JsonObject)["client"].let { (it as JsonObject)["clientName"] })
    }

    @Test fun `not processed, refused or broken answers are false, not a throw`() = runTest {
        assertFalse(client { HttpStatusCode.OK to """{"feedbackResponses":[{"isProcessed":false}]}""" }.feedback("T"))
        assertFalse(client { HttpStatusCode.Forbidden to "{}" }.feedback("T"))
        assertFalse(client { HttpStatusCode.OK to "not json" }.feedback("T"))
        assertEquals(3, requests.size)
    }

    @Test fun `signed out sends nothing`() = runTest {
        assertFalse(client(auth = { null }) { HttpStatusCode.OK to "{}" }.feedback("T"))
        assertTrue(requests.isEmpty())
    }
}
