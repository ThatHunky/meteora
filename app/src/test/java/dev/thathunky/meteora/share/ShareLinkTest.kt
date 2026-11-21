package dev.thathunky.meteora.share

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

    @Test fun `junk`() {
        assertNull(ShareLink.parse("no links here"))
        assertNull(ShareLink.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ShareLink.parse("https://youtu.be/short"))
    }
}
