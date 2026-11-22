package dev.thathunky.meteora.ui.filter

import dev.thathunky.meteora.filter.LanguageGuess
import dev.thathunky.meteora.ui.browse.ItemAction
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterUiTest {
    @Test fun `every detectable language has a name`() =
        assertEquals(LanguageGuess.LANGUAGE_IDS.toSet(), LANGUAGE_NAMES.keys)

    @Test fun `Hide artist is offered where there is an artist`() {
        val song = MusicItem.Song("v", "T", listOf(ArtistRef("A", "UCa")), null, null, null, isVideo = false)
        assertEquals(listOf(ItemAction.HideArtist), filterActions(song))
        assertEquals(emptyList<ItemAction>(), filterActions(song.copy(artists = emptyList())))
        assertEquals(listOf(ArtistRef("NIN", "UCn")), hideableArtists(MusicItem.Artist("UCn", "NIN", "", null)))
        assertEquals(emptyList<ItemAction>(), filterActions(MusicItem.Playlist("VLPL1", "Mix", "", null, "PL1")))
        assertEquals(emptyList<ItemAction>(), filterActions(MusicItem.Album("MPREb_1", "X", "Album • Y", null, null)))
        assertEquals(listOf(ItemAction.HideArtist), filterActions(MusicItem.Album("MPREb_1", "X", "Album • Y", null, null, listOf(ArtistRef("Y", "UCy")))))
    }

    @Test fun `Not interested is offered only when YouTube gave its token`() {
        val song = MusicItem.Song("v", "T", listOf(ArtistRef("A", "UCa")), null, null, null, isVideo = false)
        assertEquals(listOf(ItemAction.HideArtist), filterActions(song.copy(feedback = dev.thathunky.meteora.ytm.ItemFeedback(null, "D"))))
        assertEquals(listOf(ItemAction.HideArtist, ItemAction.NotInterested), filterActions(song.copy(feedback = dev.thathunky.meteora.ytm.ItemFeedback("N", "D"))))
        assertEquals(listOf(ItemAction.NotInterested), filterActions(MusicItem.Playlist("VLPL1", "Mix", "", null, "PL1", dev.thathunky.meteora.ytm.ItemFeedback("N", null))))
    }

    @Test fun `Now Playing's artist line is offered whole with the channel, then in pieces`() {
        assertEquals(
            listOf(ArtistRef("Simon & Garfunkel", "UCsg"), ArtistRef("Simon", null), ArtistRef("Garfunkel", null)),
            refsFrom("Simon & Garfunkel", "UCsg"),
        )
        assertEquals(listOf(ArtistRef("Kino", "UCk")), refsFrom("Kino", "UCk"))
    }

    @Test fun `the related-artists shelf is the one made of artists`() {
        assertTrue(isRelatedShelf(Shelf("Fans might also like", listOf(MusicItem.Artist("UC1", "X", "", null)))))
        assertFalse(isRelatedShelf(Shelf("Albums", listOf(MusicItem.Album("MPREb_1", "X", "", null, null)))))
        assertFalse(isRelatedShelf(Shelf("Empty", emptyList())))
    }

    @Test fun `countries come from the platform list with names`() {
        val all = allCountries(Locale.ENGLISH)
        assertTrue(all.size > 200)
        assertEquals("Ukraine", all.first { it.first == "UA" }.second)
    }
}
