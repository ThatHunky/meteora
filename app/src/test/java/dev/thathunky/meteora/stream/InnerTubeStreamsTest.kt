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

    @Test fun tiersPickFromFixture() {
        assertEquals(251, InnerTubeStreams.pickAudio(player, StreamQuality.High)!!.itag)
        assertEquals(251, InnerTubeStreams.pickAudio(player, StreamQuality.Normal)!!.itag)
        assertEquals(249, InnerTubeStreams.pickAudio(player, StreamQuality.Low)!!.itag)
    }

    @Test fun oldTierNamesStillRead() {
        assertEquals(StreamQuality.Low, StreamQuality.fromStored("DataSaver"))
        assertEquals(StreamQuality.Normal, StreamQuality.fromStored("Balanced"))
        assertEquals(StreamQuality.High, StreamQuality.fromStored("High"))
        assertEquals(StreamQuality.Normal, StreamQuality.fromStored("Normal"))
        assertNull(StreamQuality.fromStored("Ultra"))
        assertNull(StreamQuality.fromStored(null))
    }

    @Test fun cipheredPremiumFormatsWhenAllowed() {
        fun ciphered(itag: Int, codec: String, bitrate: Int) =
            """{"itag":$itag,"mimeType":"${if (codec == "opus") "audio/webm" else "audio/mp4"}; codecs=\"$codec\"","signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Fr%2Fv%3Fitag%3D$itag","averageBitrate":$bitrate}"""
        val r = response(format(251, "opus", 127_000), ciphered(141, "mp4a.40.2", 257_000), ciphered(774, "opus", 258_000))
        val p = InnerTubeStreams.pickAudio(r, StreamQuality.High, allowCipher = true)!!
        assertEquals(774, p.itag)
        assertEquals("", p.url)
        assertEquals(true, p.cipher!!.contains("itag%3D774"))
        // Without the cipher step only the plain-URL format is left.
        val plain = InnerTubeStreams.pickAudio(r, StreamQuality.High)!!
        assertEquals(251, plain.itag)
        assertNull(plain.cipher)
        assertNull(InnerTubeStreams.pickAudio(response(ciphered(774, "opus", 258_000)), StreamQuality.High))
    }

    private fun response(vararg formats: String) = Json.parseToJsonElement(
        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[${formats.joinToString(",")}]}}""",
    )

    private fun format(itag: Int, codec: String, bitrate: Int, extra: String = "") =
        """{"itag":$itag,"mimeType":"${if (codec == "opus") "audio/webm" else "audio/mp4"}; codecs=\"$codec\"","url":"https://a/b?itag=$itag&n=$bitrate","averageBitrate":$bitrate$extra}"""

    @Test fun premiumBitratesWin() {
        val opus251 = format(251, "opus", 127_000)
        val aac140 = format(140, "mp4a.40.2", 130_000)
        val aac141 = format(141, "mp4a.40.2", 256_000)
        val opus774 = format(774, "opus", 256_000)
        assertEquals(774, InnerTubeStreams.pickAudio(response(opus251, aac140, aac141, opus774), StreamQuality.High)!!.itag)
        assertEquals(141, InnerTubeStreams.pickAudio(response(opus251, aac140, aac141), StreamQuality.High)!!.itag)
    }

    @Test fun drcVariantIsSkipped() {
        val drc = """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://a/drc","averageBitrate":127000,"isDrc":true}"""
        val plain = """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"https://a/plain","averageBitrate":127000}"""
        assertEquals("https://a/plain", InnerTubeStreams.pickAudio(response(drc, plain), StreamQuality.High)!!.url)
    }

    @Test fun lowFallsBackToLowest() {
        val p = InnerTubeStreams.pickAudio(response(format(140, "mp4a.40.2", 130_000)), StreamQuality.Low)!!
        assertEquals(140, p.itag)
        assertEquals(130_000, p.bitrate)
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
