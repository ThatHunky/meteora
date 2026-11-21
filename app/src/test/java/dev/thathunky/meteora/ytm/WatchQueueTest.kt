package dev.thathunky.meteora.ytm

import dev.thathunky.meteora.domain.LikeStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun signedOutNothingIsLiked() {
        // Signed out, every item still has the liked-songs toggle, in its "add" state.
        val q = YtmParser.watchQueue(root)
        assertEquals(q.songs.map { it.videoId }.toSet(), q.likes.keys)
        assertTrue(q.likes.values.all { it == LikeStatus.Indifferent })
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
