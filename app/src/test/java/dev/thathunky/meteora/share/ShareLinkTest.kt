package dev.thathunky.meteora.share

import dev.thathunky.meteora.ytm.AlbumRef
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {
    @Test fun `music share text`() = assertEquals(
        ShareTarget.Video("O7gRpIKfSCM", null),
        ShareLink.parse("Listen to Nude by Radiohead on YouTube Music: https://music.youtube.com/watch?v=O7gRpIKfSCM&si=abc123"),
    )

    @Test fun `watch with list`() = assertEquals(
        ShareTarget.Video("vh2ED26hMNU", "OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU"),
        ShareLink.parse("https://music.youtube.com/watch?v=vh2ED26hMNU&list=OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU"),
    )

    @Test fun `short and regular youtube links`() {
        assertEquals(ShareTarget.Video("dQw4w9WgXcQ", null), ShareLink.parse("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals(ShareTarget.Video("dQw4w9WgXcQ", null), ShareLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(ShareTarget.Video("dQw4w9WgXcQ", null), ShareLink.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun `playlist album artist`() {
        assertEquals(ShareTarget.Playlist("PL123"), ShareLink.parse("https://music.youtube.com/playlist?list=PL123&si=z"))
        assertEquals(ShareTarget.Album("MPREb_fJhu6dGJXTo"), ShareLink.parse("https://music.youtube.com/browse/MPREb_fJhu6dGJXTo"))
        assertEquals(ShareTarget.Artist("UCr_iyUANcn9OX_yy9piYoLw"), ShareLink.parse("https://music.youtube.com/channel/UCr_iyUANcn9OX_yy9piYoLw"))
    }

    private val song = MusicItem.Song(
        "O7gRpIKfSCM", "Closer", listOf(ArtistRef("Nine Inch Nails", "UCxyz")), AlbumRef("The Downward Spiral", "MPREb_x"), "6:14", null, false,
    )
    private val albumWithPlaylist = MusicItem.Album("MPREb_fJhu6dGJXTo", "Amnesiac", "Album • Radiohead • 2001", null, "OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU")
    private val albumAlone = albumWithPlaylist.copy(playlistId = null)
    private val playlist = MusicItem.Playlist("VLPLukmsaXDPJicQviy4d2oN2YaczCcMytt7", "Mix", "Playlist • Someone", null, "PLukmsaXDPJicQviy4d2oN2YaczCcMytt7")
    private val artist = MusicItem.Artist("UCr_iyUANcn9OX_yy9piYoLw", "Radiohead", "Artist", null)

    @Test fun `share urls`() {
        assertEquals("https://music.youtube.com/watch?v=O7gRpIKfSCM", shareUrl(song))
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU", shareUrl(albumWithPlaylist))
        assertEquals("https://music.youtube.com/browse/MPREb_fJhu6dGJXTo", shareUrl(albumAlone))
        assertEquals("https://music.youtube.com/playlist?list=PLukmsaXDPJicQviy4d2oN2YaczCcMytt7", shareUrl(playlist))
        assertEquals("https://music.youtube.com/channel/UCr_iyUANcn9OX_yy9piYoLw", shareUrl(artist))
    }

    @Test fun `playlist link has no VL prefix even if the id carries one`() =
        assertEquals("https://music.youtube.com/playlist?list=PL1", shareUrl(playlist.copy(playlistId = "VLPL1")))

    @Test fun `every shared url parses back to its target`() {
        listOf(song, albumWithPlaylist, albumAlone, playlist, artist).forEach { item ->
            assertEquals(item.toString(), item.shareTarget(), ShareLink.parse(shareUrl(item)))
        }
        // An album with its OLAK5uy_ id known shares as that playlist, which plays the album.
        assertEquals(ShareTarget.Playlist("OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU"), ShareLink.parse(shareUrl(albumWithPlaylist)))
        val withList = ShareTarget.Video("vh2ED26hMNU", "OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU")
        assertEquals(withList, ShareLink.parse(ShareLink.url(withList)))
    }

    @Test fun `share titles`() {
        assertEquals("Closer – Nine Inch Nails", song.shareTitle())
        assertEquals("Amnesiac – Radiohead", albumWithPlaylist.shareTitle())
        assertEquals("Amnesiac – Radiohead", albumWithPlaylist.copy(subtitle = "Radiohead • 2001").shareTitle())
        assertEquals("Amnesiac", albumWithPlaylist.copy(subtitle = "").shareTitle())
        assertEquals("Mix", playlist.shareTitle())
        assertEquals("Radiohead", artist.shareTitle())
        assertEquals("Closer", shareTitle("Closer", " "))
    }

    @Test fun `junk`() {
        assertNull(ShareLink.parse("no links here"))
        assertNull(ShareLink.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ShareLink.parse("https://youtu.be/short"))
    }
}
