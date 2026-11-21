package dev.thathunky.meteora.ytm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics responses recorded signed out on 2026-11-22, cut to a few lines (docs/api-notes.md › Lyrics). */
class LyricsParserTest {

    private fun load(name: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/$name")) { name }.readText())

    @Test fun `timed lyrics from the android client`() {
        val l = requireNotNull(YtmParser.lyrics(load("lyrics-timed.json")))
        assertTrue(l.synced)
        assertEquals("Source: Musixmatch", l.source)
        assertEquals(listOf(0L, 13_520L, 16_240L, 27_330L), l.lines.map { it.startMs })
        assertEquals("♪", l.lines[0].text)
        assertEquals("Yeah", l.lines[1].text)
    }

    @Test fun `unsynced lyrics in the timed shape`() {
        val l = requireNotNull(YtmParser.lyrics(load("lyrics-plain.json")))
        assertFalse(l.synced)
        assertEquals(3, l.lines.size)
        assertTrue(l.lines.all { it.startMs == null })
        assertEquals("We're no strangers to love", l.lines[0].text)
        assertEquals("Source: Musixmatch", l.source)
    }

    @Test fun `plain lyrics from the web client`() {
        val l = requireNotNull(YtmParser.lyrics(load("lyrics-plain-web.json")))
        assertFalse(l.synced)
        assertEquals(listOf("Yeah", "", "I've been tryna call"), l.lines.map { it.text })
        assertEquals("Source: Musixmatch", l.source)
    }

    @Test fun `no lyrics`() {
        assertNull(YtmParser.lyrics(load("lyrics-none.json")))
    }

    @Test fun `lyrics id from a next response`() {
        val id = YtmParser.lyricsId(load("next-radio.json"))
        assertTrue(id.toString(), id!!.startsWith("MPLY"))
        assertNull(YtmParser.lyricsId(load("lyrics-none.json")))
    }
}
