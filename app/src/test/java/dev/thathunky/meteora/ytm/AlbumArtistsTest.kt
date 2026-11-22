package dev.thathunky.meteora.ytm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Albums carry the artists their subtitle links to, for the content filter's Hide artist. */
class AlbumArtistsTest {
    private fun load(name: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/$name")) { name }.readText())

    @Test fun `search albums name their linked artist`() {
        val r = YtmParser.search(load("search-top.json"))
        val albums = (listOfNotNull(r.top) + r.items).filterIsInstance<MusicItem.Album>()
        assertTrue(albums.isNotEmpty())
        val withArtist = albums.filter { it.artists.isNotEmpty() }
        assertTrue("no album named its artist", withArtist.isNotEmpty())
        withArtist.flatMap { it.artists }.forEach { a ->
            assertTrue(a.name.isNotBlank())
            assertTrue(a.browseId!!.startsWith("UC"))
        }
        assertEquals("Linkin Park", withArtist.first().artists.first().name)
    }
}
