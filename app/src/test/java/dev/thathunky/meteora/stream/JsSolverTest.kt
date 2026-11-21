package dev.thathunky.meteora.stream

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The solver chooser, and the WebView solver's result parsing (still used as the fallback).
 * Page parsing, ciphers and URL building moved to the Rust core: rust/meteora-core/tests/streams.rs.
 */
class JsSolverTest {

    /** Answers with [tag] on every challenge, or fails; records the players it was asked about. */
    private class FakeSolver(private val tag: String, private val fail: Boolean = false) : JsSolver {
        val calls = mutableListOf<String>()
        override suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved {
            calls += playerId
            if (fail) throw StreamException("broken")
            return Solved(sig.associateWith { tag }, n.associateWith { tag })
        }
    }

    private suspend fun JsSolver.ask(player: String) = solve(player, { "js" }, listOf("s"), listOf("n"))

    @Test fun nativeWorksSoTheWebViewIsNeverMade() = runTest {
        val native = FakeSolver("native")
        val chooser = ChoosingJsSolver(native, { true }, { error("WebView created") })
        assertEquals("native", chooser.ask("p1").sig["s"])
        assertEquals("native", chooser.ask("p1").n["n"])
        assertEquals(listOf("p1", "p1"), native.calls)
    }

    @Test fun nativeFailureRetriesOnTheWebView() = runTest {
        val native = FakeSolver("native", fail = true)
        val web = FakeSolver("web")
        var made = 0
        val chooser = ChoosingJsSolver(native, { true }, { made++; web })
        assertEquals("web", chooser.ask("p1").sig["s"])
        // That player stays on the WebView; a new one tries native again.
        assertEquals("web", chooser.ask("p1").sig["s"])
        assertEquals(listOf("p1"), native.calls)
        chooser.ask("p2")
        assertEquals(listOf("p1", "p2"), native.calls)
        assertEquals(listOf("p1", "p1", "p2"), web.calls)
        assertEquals(3, made)
    }

    @Test fun noNativeLibraryMeansWebView() = runTest {
        val native = FakeSolver("native")
        val chooser = ChoosingJsSolver(native, { false }, { FakeSolver("web") })
        assertEquals("web", chooser.ask("p1").n["n"])
        assertTrue(native.calls.isEmpty())
    }

    @Test fun webViewFailurePassesUp() = runTest {
        val chooser = ChoosingJsSolver(FakeSolver("native", fail = true), { true }, { FakeSolver("web", fail = true) })
        try {
            chooser.ask("p1")
            fail("expected a StreamException")
        } catch (_: StreamException) {
        }
    }

    // ---- the WebView solver's result, as evaluateJavascript hands it over (a JSON string holding JSON) ----

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

    @Test fun zipAppEntriesFound() {
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("a/lib.js")); z.write("LIB".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("other")); z.write("x".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val found = SolverScripts.readZipApp("#!/usr/bin/env python3\n".toByteArray() + zip, setOf("a/lib.js"))
        assertEquals(mapOf("a/lib.js" to "LIB"), found)
        assertTrue(SolverScripts.readZipApp("no zip here".toByteArray(), setOf("a")).isEmpty())
    }
}
