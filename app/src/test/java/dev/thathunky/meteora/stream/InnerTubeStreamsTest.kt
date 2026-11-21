package dev.thathunky.meteora.stream

import dev.thathunky.meteora.playback.parseDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InnerTubeStreamsTest {
    /** visionOS `/player` for HLrqNhgdiC0, 2026-11-21, URLs replaced. */
    private val player = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/player-visionos.json")).readText())

    @Test fun picksOpusFirst() {
        val p = InnerTubeStreams.pickAudio(player)!!
        assertEquals(251, p.itag)
        assertEquals(true, p.url.contains("itag=251"))
        assertEquals(true, p.expiresInSec > 3600)
    }

    @Test fun botCheckIsNotPlayable() {
        val r = Json.parseToJsonElement("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""")
        assertEquals("LOGIN_REQUIRED", InnerTubeStreams.status(r))
        assertNull(InnerTubeStreams.pickAudio(r))
    }

    @Test fun cipheredFormatsAreSkipped() {
        val r = Json.parseToJsonElement(
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              {"itag":251,"mimeType":"audio/webm","signatureCipher":"s=x&url=y"},
              {"itag":140,"mimeType":"audio/mp4","url":"https://a/b?itag=140","bitrate":1}]}}""",
        )
        assertEquals(140, InnerTubeStreams.pickAudio(r)!!.itag)
    }

    @Test fun visitorData() {
        val body = """)]}'
[[null,null,[[null,null,null,null,null,null,null,null,null,null,null,null,null,"CgtsSVBZV0JYaC0xQSjY3ofYBjIKCgJVQRIEGgAgOQ%3D%3D"]]]]"""
        assertEquals("CgtsSVBZV0JYaC0xQSjY3ofYBjIKCgJVQRIEGgAgOQ%3D%3D", InnerTubeStreams.parseVisitorData(body))
        assertNull(InnerTubeStreams.parseVisitorData("nope"))
    }

    @Test fun durations() {
        assertEquals(374.0, parseDuration("6:14"), 0.0)
        assertEquals(3723.0, parseDuration("1:02:03"), 0.0)
        assertEquals(0.0, parseDuration(null), 0.0)
    }
}
