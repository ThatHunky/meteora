package dev.thathunky.meteora.stream

import dev.thathunky.meteora.ytm.YtmClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebMusicStreamsTest {

    /** Reverses every challenge, and records what it was asked. */
    private class FakeSolver(private val drop: Boolean = false) : JsSolver {
        val calls = mutableListOf<Pair<List<String>, List<String>>>()
        override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
            calls += sig to n
            if (drop) return Solved(emptyMap(), emptyMap())
            return Solved(sig.associateWith { it.reversed() }, n.associateWith { it.reversed() })
        }
    }

    private fun picked(url: String = "", cipher: String? = null) =
        InnerTubeStreams.Picked(itag = 774, url = url, expiresInSec = 21_600, bitrate = 258_000, cipher = cipher)

    private suspend fun decipher(p: InnerTubeStreams.Picked, solver: JsSolver) =
        WebMusicStreams.decipher(p, "6b3d0364", { error("player not needed by the fake") }, solver)

    @Test fun cipherWithSp() = runTest {
        val solver = FakeSolver()
        val url = decipher(picked(cipher = "s=abc&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D774%26n%3Dxyz"), solver)
        assertEquals("https://r1.googlevideo.com/videoplayback?itag=774&n=zyx&sig=cba", url)
        // One call for both challenges.
        assertEquals(listOf(listOf("abc") to listOf("xyz")), solver.calls)
    }

    @Test fun cipherWithoutSpUsesSignature() = runTest {
        val url = decipher(picked(cipher = "url=https%3A%2F%2Fr%2Fv%3Fitag%3D141&s=abc"), FakeSolver())
        assertEquals("https://r/v?itag=141&signature=cba", url)
    }

    @Test fun valuesAreUrlEncoded() = runTest {
        // Decoded "a=b/c+" reversed is "+c/b=a", which must be encoded again in the URL.
        val url = decipher(picked(cipher = "s=a%3Db%2Fc%2B&sp=sig&url=https%3A%2F%2Fr%2Fv%3Fn%3Dp%252Bq%26x%3D1"), FakeSolver())
        assertEquals("https://r/v?n=q%2Bp&x=1&sig=%2Bc%2Fb%3Da", url)
    }

    @Test fun plainUrlStillGetsNSolved() = runTest {
        val solver = FakeSolver()
        assertEquals("https://r/v?itag=251&n=cba&k=v", decipher(picked(url = "https://r/v?itag=251&n=abc&k=v"), solver))
        assertEquals(listOf(emptyList<String>() to listOf("abc")), solver.calls)
    }

    @Test fun plainUrlWithoutNNeedsNoSolver() = runTest {
        val solver = FakeSolver()
        assertEquals("https://r/v?itag=251", decipher(picked(url = "https://r/v?itag=251"), solver))
        assertTrue(solver.calls.isEmpty())
    }

    @Test fun unsolvedChallengeFails() = runTest {
        try {
            decipher(picked(cipher = "s=abc&url=https%3A%2F%2Fr%2Fv%3Fn%3Dxyz"), FakeSolver(drop = true))
            fail("expected a StreamException")
        } catch (e: StreamException) {
            // The message never carries challenge values or the URL.
            val m = e.message!!
            assertTrue("abc" !in m && "xyz" !in m && "https" !in m)
        }
    }

    @Test fun badCipherFails() = runTest {
        try {
            decipher(picked(cipher = "sp=sig"), FakeSolver())
            fail("expected a StreamException")
        } catch (_: StreamException) {
        }
    }

    @Test fun playerPage() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"x","INNERTUBE_CLIENT_VERSION":"1.20261119.01.00","jsUrl":"/s/player/6b3d0364/player_es6.vflset/uk_UA/base.js","x":1});</script>"""
        val p = WebMusicStreams.parsePlayerPage(html)!!
        assertEquals("/s/player/6b3d0364/player_es6.vflset/uk_UA/base.js", p.jsUrl)
        assertEquals("6b3d0364", p.playerId)
        assertEquals("1.20261119.01.00", p.clientVersion)
    }

    @Test fun playerPageWithoutVersionUsesFallback() {
        val p = WebMusicStreams.parsePlayerPage(""""jsUrl":"/s/player/ab_C-9/base.js"""")!!
        assertEquals("ab_C-9", p.playerId)
        assertEquals(YtmClient.FALLBACK_CLIENT_VERSION, p.clientVersion)
        assertNull(WebMusicStreams.parsePlayerPage("<html>nothing here</html>"))
        // The id becomes a file name: nothing that could leave the folder.
        assertNull(WebMusicStreams.parsePlayerPage(""""jsUrl":"/s/player/../x/base.js""""))
    }

    @Test fun signatureTimestamp() {
        assertEquals(20774, WebMusicStreams.parseSignatureTimestamp("var a={signatureTimestamp:20774,b:1}"))
        assertEquals(20774, WebMusicStreams.parseSignatureTimestamp("x={sts:20774}"))
        assertEquals(20774, WebMusicStreams.parseSignatureTimestamp("x={sts : 20774}"))
        assertNull(WebMusicStreams.parseSignatureTimestamp("var nothing=1"))
    }

    @Test fun queryHelpers() {
        assertEquals("p+q", WebMusicStreams.queryParam("https://r/v?a=1&n=p%2Bq", "n"))
        assertNull(WebMusicStreams.queryParam("https://r/v?a=1", "n"))
        assertNull(WebMusicStreams.queryParam("https://r/v", "n"))
        assertEquals("https://r/v?a=1&n=z&nn=2", WebMusicStreams.replaceQueryParam("https://r/v?a=1&n=y&nn=2", "n", "z"))
    }

    // ---- the solver's result, as evaluateJavascript hands it over (a JSON string holding JSON) ----

    private fun raw(json: String) = Json.encodeToString(String.serializer(), json)

    @Test fun solverResultParsed() {
        val r = raw("""{"pre":"PLAYER","out":{"type":"result","responses":[{"type":"result","data":{"s1":"S1"}},{"type":"result","data":{"n1":"N1"}}]}}""")
        val (pre, solved) = WebViewJsSolver.parseResult(r, listOf("s1"), listOf("n1"))
        assertEquals("PLAYER", pre)
        assertEquals(mapOf("s1" to "S1"), solved.sig)
        assertEquals(mapOf("n1" to "N1"), solved.n)
    }

    @Test fun solverResultOnlyN() {
        val r = raw("""{"pre":null,"out":{"type":"result","responses":[{"type":"result","data":{"n1":"N1"}}]}}""")
        val (pre, solved) = WebViewJsSolver.parseResult(r, emptyList(), listOf("n1"))
        assertNull(pre)
        assertEquals(mapOf("n1" to "N1"), solved.n)
    }

    @Test fun solverErrorsThrowWithoutChallenges() {
        fun fails(r: String) = try {
            WebViewJsSolver.parseResult(r, listOf("secretS"), listOf("secretN"))
            fail("expected a StreamException"); ""
        } catch (e: StreamException) {
            e.message!!
        }
        val top = fails(raw("""{"stored":false,"out":{"type":"error","error":"cannot solve secretS\nstack"}}"""))
        assertTrue("secretS" !in top && "stack" !in top)
        val inner = fails(raw("""{"stored":true,"out":{"type":"result","responses":[{"type":"error","error":"bad secretN"},{"type":"result","data":{}}]}}"""))
        assertTrue("secretN" !in inner)
        fails(raw("""{"stored":true,"out":{"type":"result","responses":[{"type":"result","data":{}},{"type":"result","data":{"secretN":"x"}}]}}"""))
        fails("null")
        fails(raw("not json"))
    }
}
