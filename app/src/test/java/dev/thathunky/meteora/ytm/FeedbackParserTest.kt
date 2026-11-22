package dev.thathunky.meteora.ytm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two feedback tokens of an item's menu, from a hand-made Home fixture with fake tokens. */
class FeedbackParserTest {

    private val home = YtmParser.shelves(
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/ytm/home-feedback.json")).readText()),
    ).single().items

    @Test fun `a song row carries both tokens, told apart by icon`() {
        val song = home[0] as MusicItem.Song
        assertEquals("aaaaaaaaaaa", song.videoId)
        assertEquals(listOf(ArtistRef("Throwaway Band", "UCthrowaway")), song.artists)
        assertEquals(ItemFeedback("FAKE_NOT_INTERESTED_1", "FAKE_DONT_RECOMMEND_1"), song.feedback)
    }

    @Test fun `library toggles are not taken for feedback entries`() {
        val album = home[1] as MusicItem.Album
        assertEquals(ItemFeedback("FAKE_NOT_INTERESTED_2", null), album.feedback)
        // Only toggles (library add, keep, bookmark): no feedback at all.
        assertNull((home[2] as MusicItem.Playlist).feedback)
    }

    @Test fun `tokens never show in toString`() {
        val all = home.joinToString() + Shelf("x", home).toString()
        assertFalse(all, all.contains("FAKE_"))
        assertTrue(home[0].toString().contains("notInterested=true"))
    }

    @Test fun `the answer counts only when YouTube says it was processed`() {
        fun processed(text: String) = YtmParser.feedbackProcessed(Json.parseToJsonElement(text))
        assertTrue(processed("""{"feedbackResponses":[{"isProcessed":true}]}"""))
        assertFalse(processed("""{"feedbackResponses":[{"isProcessed":false}]}"""))
        assertFalse(processed("""{"feedbackResponses":[]}"""))
        assertFalse(processed("""{}"""))
    }
}
