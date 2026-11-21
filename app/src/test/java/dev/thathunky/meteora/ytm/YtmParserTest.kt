package dev.thathunky.meteora.ytm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded public (signed-out) WEB_REMIX responses, 2026-11-21. */
class YtmParserTest {

    private fun load(name: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/$name")) { name }.readText())

    @Test fun search() {
        val r = YtmParser.search(load("search-top.json"))
        val top = r.top as MusicItem.Album
        assertEquals("Meteora 20th Anniversary Edition", top.title)
        assertTrue(top.browseId.startsWith("MPREb_"))
        assertTrue("expected results", r.items.size >= 10)
        assertTrue(r.items.any { it is MusicItem.Artist && it.title == "Linkin Park" })
        val songs = r.items.filterIsInstance<MusicItem.Song>()
        assertTrue("expected songs", songs.isNotEmpty())
        songs.forEach {
            assertTrue(it.videoId.length == 11)
            assertTrue(it.title.isNotBlank())
        }
        r.items.forEach { assertNotNull(it.title, it.thumbnail) }
    }

    @Test fun suggestions() {
        val s = YtmParser.suggestions(load("suggestions.json"))
        assertTrue(s.toString(), s.isNotEmpty() && s.all { it.startsWith("meteo", ignoreCase = true) || it.contains("meteo", true) })
    }

    @Test fun album() {
        val a = YtmParser.collection(load("album.json"))
        assertEquals("Pyramid Song", a.title)
        assertEquals("OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU", a.playlistId)
        assertEquals(listOf("3M_Gg1xAHE4", "vh2ED26hMNU", "9-8zVH35qc4", "d9psS8I7yYc", "_1KDA806Klg"), a.tracks.map { it.videoId })
        assertEquals("Pyramid Song", a.tracks.first().title)
        assertNotNull(a.thumbnail)
    }

    @Test fun artist() {
        val a = YtmParser.artist(load("artist.json"))
        assertEquals("Radiohead", a.name)
        assertTrue("shelves: ${a.shelves.map { it.title }}", a.shelves.size >= 3)
        assertTrue(a.shelves.flatMap { it.items }.any { it is MusicItem.Album })
        assertTrue(a.shelves.first().items.any { it is MusicItem.Song })
    }
}
