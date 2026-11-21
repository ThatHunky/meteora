package dev.thathunky.meteora.ytm

import dev.thathunky.meteora.domain.LikeStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `next` for videoId O7gRpIKfSCM + RDAMVMO7gRpIKfSCM, recorded signed-out 2026-11-21. */
class WatchQueueTest {
    private val root = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/next-radio.json")).readText())

    @Test fun radio() {
        val q = YtmParser.watchQueue(root)
        assertEquals(50, q.songs.size)
        assertEquals(0, q.startIndex)
        val first = q.songs.first()
        assertEquals("O7gRpIKfSCM", first.videoId)
        assertEquals("Closer", first.title)
        assertEquals(listOf("Nine Inch Nails"), first.artists.map { it.name })
        assertEquals("The Downward Spiral (Deluxe Edition)", first.album?.name)
        assertEquals("6:14", first.duration)
        assertNotNull(first.thumbnail)
        assertNotNull("radio continues", q.continuation)
        q.songs.forEach { assertEquals(11, it.videoId.length); assertTrue(it.title.isNotBlank()) }
    }

    @Test fun radioHasNoAutomix() = assertNull(YtmParser.watchQueue(root).automix)

    /** `next` for playlistId OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU (the 5-track Radiohead release of album.json), signed out, 2026-11-21. */
    @Test fun albumAndItsAutomix() {
        val album = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/next-album.json")).readText())
        val q = YtmParser.watchQueue(album)
        assertEquals(listOf("3M_Gg1xAHE4", "vh2ED26hMNU", "9-8zVH35qc4", "d9psS8I7yYc", "_1KDA806Klg"), q.songs.map { it.videoId })
        assertEquals("Pyramid Song", q.songs.first().title)
        assertEquals(0, q.startIndex)
        assertNull("an album queue ends", q.continuation)
        assertEquals(AutomixRef("RDAMPLOLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU", "wAEB8gECeAE%3D"), q.automix)
        assertEquals(collectionRadioId("OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU"), q.automix?.playlistId)
    }

    @Test fun radioIds() {
        assertEquals("RDAMPLOLAK5uy_x", collectionRadioId("OLAK5uy_x"))
        assertEquals("RDAMPLPL123", collectionRadioId("VLPL123"))
        assertEquals("RDAMPLRDCLAK5uy_x", collectionRadioId("RDCLAK5uy_x"))
        assertEquals("RDAMPLPL123", collectionRadioId("RDAMPLPL123"))
        assertEquals("RDAMVMO7gRpIKfSCM", collectionRadioId("RDAMVMO7gRpIKfSCM"))
    }

    @Test fun signedOutNothingIsLiked() {
        // Signed out, every item still has the liked-songs toggle, in its "add" state.
        val q = YtmParser.watchQueue(root)
        assertEquals(q.songs.map { it.videoId }.toSet(), q.likes.keys)
        assertTrue(q.likes.values.all { it == LikeStatus.Indifferent })
    }

    /** A song with a music video comes wrapped with the video as `counterpart`: one queue entry, not two. */
    @Test fun counterpartIsNotQueued() {
        fun r(id: String, type: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"T"}]},
            "navigationEndpoint":{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}}}"""
        val q = YtmParser.watchQueue(
            Json.parseToJsonElement(
                """{"contents":[
                  {"playlistPanelVideoWrapperRenderer":{"primaryRenderer":${r("aaaaaaaaaaa", "MUSIC_VIDEO_TYPE_ATV")},
                    "counterpart":[{"counterpartRenderer":${r("vvvvvvvvvvv", "MUSIC_VIDEO_TYPE_OMV")}}]}},
                  ${r("bbbbbbbbbbb", "MUSIC_VIDEO_TYPE_ATV")}]}""",
            ),
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), q.songs.map { it.videoId })
        assertTrue(q.songs.none { it.isVideo })
        // The counterpart is kept on the song it belongs to, not queued.
        assertEquals("vvvvvvvvvvv", q.songs[0].videoCounterpartId)
        assertTrue(q.songs[0].segments.isEmpty())
        assertNull(q.songs[1].videoCounterpartId)
    }

    @Test fun counterpartKeepsItsSegmentMap() {
        fun r(id: String, type: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"T"}]},
            "navigationEndpoint":{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}}}"""
        val q = YtmParser.watchQueue(
            Json.parseToJsonElement(
                """{"contents":[
                  {"playlistPanelVideoWrapperRenderer":{"primaryRenderer":${r("aaaaaaaaaaa", "MUSIC_VIDEO_TYPE_ATV")},
                    "counterpart":[{"counterpartRenderer":${r("vvvvvvvvvvv", "MUSIC_VIDEO_TYPE_OMV")},
                      "segmentMap":{"segment":[
                        {"primaryVideoStartTimeMilliseconds":"0","counterpartVideoStartTimeMilliseconds":"4000","durationMilliseconds":"200000"},
                        {"primaryVideoStartTimeMilliseconds":"200000","counterpartVideoStartTimeMilliseconds":"204000","durationMilliseconds":"30000"}]}}]}}]}""",
            ),
        )
        val song = q.songs.single()
        assertEquals("vvvvvvvvvvv", song.videoCounterpartId)
        assertEquals(listOf(VideoSegment(0, 4000, 200000), VideoSegment(200000, 204000, 30000)), song.segments)
    }

    @Test fun unreadableSegmentMapIsDropped() {
        val q = YtmParser.watchQueue(
            Json.parseToJsonElement(
                """{"contents":[{"playlistPanelVideoWrapperRenderer":{
                  "primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"T"}]}}},
                  "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"vvvvvvvvvvv","title":{"runs":[{"text":"T"}]}}},
                    "segmentMap":{"segment":[
                      {"primaryVideoStartTimeMilliseconds":"0","counterpartVideoStartTimeMilliseconds":"0","durationMilliseconds":"1000"},
                      {"primaryVideoStartTimeMilliseconds":"1000","durationMilliseconds":"bad"}]}}]}}]}""",
            ),
        )
        assertEquals("vvvvvvvvvvv", q.songs.single().videoCounterpartId)
        assertTrue(q.songs.single().segments.isEmpty())
    }

    /** The shape seen signed in on 2026-11-21: a liked-songs toggle per item, the overlay for the current one. */
    @Test fun likesFromTogglesAndOverlay() {
        fun item(id: String, defaultIcon: String, toggledIcon: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"T"}]},
            "menu":{"menuRenderer":{"items":[
              {"toggleMenuServiceItemRenderer":{"defaultIcon":{"iconType":"BOOKMARK_BORDER"},"toggledIcon":{"iconType":"BOOKMARK"}}},
              {"toggleMenuServiceItemRenderer":{"defaultIcon":{"iconType":"$defaultIcon"},"toggledIcon":{"iconType":"$toggledIcon"}}}]}}}}"""
        val q = YtmParser.watchQueue(
            Json.parseToJsonElement(
                """{"contents":[${item("aaaaaaaaaaa", "UNFAVORITE", "FAVORITE")},${item("bbbbbbbbbbb", "FAVORITE", "UNFAVORITE")},${item("ccccccccccc", "FAVORITE", "UNFAVORITE")}],
                "playerOverlays":{"playerOverlayRenderer":{"actions":[{"likeButtonRenderer":{"target":{"videoId":"ccccccccccc"},"likeStatus":"DISLIKE"}}]}}}""",
            ),
        )
        assertEquals(
            mapOf("aaaaaaaaaaa" to LikeStatus.Like, "bbbbbbbbbbb" to LikeStatus.Indifferent, "ccccccccccc" to LikeStatus.Dislike),
            q.likes,
        )
    }
}
