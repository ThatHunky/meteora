package dev.thathunky.meteora.ui.browse

import dev.thathunky.meteora.ui.browse.ItemAction.AddToQueue
import dev.thathunky.meteora.ui.browse.ItemAction.Download
import dev.thathunky.meteora.ui.browse.ItemAction.GoToAlbum
import dev.thathunky.meteora.ui.browse.ItemAction.GoToArtist
import dev.thathunky.meteora.ui.browse.ItemAction.Play
import dev.thathunky.meteora.ui.browse.ItemAction.PlayNext
import dev.thathunky.meteora.ui.browse.ItemAction.Radio
import dev.thathunky.meteora.ui.browse.ItemAction.RemoveDownload
import dev.thathunky.meteora.ui.browse.ItemAction.Share
import dev.thathunky.meteora.ui.browse.ItemAction.Shuffle
import dev.thathunky.meteora.ytm.AlbumRef
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemActionsTest {
    private val song = MusicItem.Song("O7gRpIKfSCM", "Closer", listOf(ArtistRef("NIN", "UCx")), AlbumRef("TDS", "MPREb_x"), "6:14", null, false)

    @Test fun `song with album and artist`() =
        assertEquals(listOf(PlayNext, AddToQueue, Radio, Download, GoToAlbum, GoToArtist, Share), itemActions(song))

    @Test fun `song without links has no go-to entries`() =
        assertEquals(
            listOf(PlayNext, AddToQueue, Radio, Download, Share),
            itemActions(song.copy(artists = listOf(ArtistRef("NIN", null)), album = AlbumRef("TDS", null))),
        )

    @Test fun `album and playlist`() {
        val collection = listOf(Play, Shuffle, PlayNext, AddToQueue, Radio, Download, Share)
        assertEquals(collection, itemActions(MusicItem.Album("MPREb_x", "TDS", "", null, null)))
        assertEquals(collection, itemActions(MusicItem.Playlist("VLPL1", "Mix", "", null, "PL1")))
    }

    @Test fun `artist`() =
        assertEquals(listOf(Shuffle, Radio, Share), itemActions(MusicItem.Artist("UCx", "NIN", "", null)))

    @Test fun `downloaded items offer removal instead`() {
        assertEquals(listOf(PlayNext, AddToQueue, Radio, RemoveDownload, GoToAlbum, GoToArtist, Share), itemActions(song, downloaded = true))
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, Radio, RemoveDownload, Share), itemActions(MusicItem.Album("MPREb_x", "TDS", "", null, null), downloaded = true))
    }
}
