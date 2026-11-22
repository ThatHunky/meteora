package dev.thathunky.meteora.filter

import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import dev.thathunky.meteora.ytm.WatchQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterApplyTest {
    private fun song(id: String, artist: String = "A") =
        MusicItem.Song(id, "T$id", listOf(ArtistRef(artist, null)), null, null, null, isVideo = false)

    private val noB: (MusicItem) -> Boolean = { (it as? MusicItem.Song)?.artists?.first()?.name != "B" }

    @Test fun `a thinned shelf keeps what is allowed`() {
        val shelf = Shelf("Mix", listOf(song("1"), song("2", "B"), song("3"), song("4")))
        assertEquals(listOf("1", "3", "4"), FilterApply.shelves(listOf(shelf), noB).single().items.map { (it as MusicItem.Song).videoId })
    }

    @Test fun `a shelf left with fewer than three is dropped`() {
        val shelf = Shelf("Mix", listOf(song("1"), song("2", "B"), song("3")))
        assertEquals(emptyList<Shelf>(), FilterApply.shelves(listOf(shelf), noB))
    }

    @Test fun `a short shelf that loses nothing stays`() {
        val shelf = Shelf("Two", listOf(song("1"), song("2")))
        assertSame(shelf, FilterApply.shelves(listOf(shelf), noB).single())
    }

    @Test fun `shelves not picked are left whole`() {
        val own = Shelf("Top songs", listOf(song("1", "B"), song("2", "B"), song("3", "B")))
        val related = Shelf("Fans might also like", listOf(song("4", "B"), song("5"), song("6"), song("7")))
        val out = FilterApply.shelves(listOf(own, related), noB) { it.title != "Top songs" }
        assertSame(own, out[0])
        assertEquals(3, out[1].items.size)
    }

    @Test fun `a queue keeps the tapped song and its start`() {
        val q = WatchQueue(listOf(song("0", "B"), song("1", "B"), song("2"), song("3", "B"), song("4")), startIndex = 1, continuation = "c")
        val out = FilterApply.queue(q, keepId = "1") { noB(it) }
        assertEquals(listOf("1", "2", "4"), out.songs.map { it.videoId })
        assertEquals(0, out.startIndex)
        assertEquals("c", out.continuation)
    }

    @Test fun `a removed start moves to the next kept song`() {
        val q = WatchQueue(listOf(song("0"), song("1", "B"), song("2")), startIndex = 1, continuation = null)
        val out = FilterApply.queue(q, keepId = null) { noB(it) }
        assertEquals(listOf("0", "2"), out.songs.map { it.videoId })
        assertEquals("2", out.songs[out.startIndex].videoId)
    }

    @Test fun `an untouched queue is the same object`() {
        val q = WatchQueue(listOf(song("0"), song("1")), 0, null)
        assertSame(q, FilterApply.queue(q, null) { true })
    }

    @Test fun `an emptied queue has no songs`() {
        val q = WatchQueue(listOf(song("0", "B")), 0, null)
        assertTrue(FilterApply.queue(q, null) { noB(it) }.songs.isEmpty())
    }

    @Test fun `which intents are radios`() {
        assertTrue(FilterApply.isRadio(PlayIntent.SongRadio("v")))
        assertTrue(FilterApply.isRadio(PlayIntent.Collection("RDAMPLOLAK5uy_x")))
        assertTrue(FilterApply.isRadio(PlayIntent.Collection("RDEMabc")))
        assertTrue(FilterApply.isRadio(PlayIntent.Collection("RDATgXvO")))
        // Albums, playlists, curated YouTube playlists, the artist's own shuffle, liked songs.
        assertFalse(FilterApply.isRadio(PlayIntent.Collection("OLAK5uy_x", "v")))
        assertFalse(FilterApply.isRadio(PlayIntent.Collection("PL123")))
        assertFalse(FilterApply.isRadio(PlayIntent.Collection("RDCLAK5uy_x")))
        assertFalse(FilterApply.isRadio(PlayIntent.Collection("RDAOabc")))
        assertFalse(FilterApply.isRadio(PlayIntent.LikedSongs))
    }

    @Test fun `the tapped song of an intent`() {
        assertEquals("v", FilterApply.tapped(PlayIntent.SongRadio("v")))
        assertEquals("w", FilterApply.tapped(PlayIntent.Collection("RDAMPLx", "w")))
        assertNull(FilterApply.tapped(PlayIntent.Collection("RDAMPLx")))
    }
}
