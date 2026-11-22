package dev.thathunky.meteora.filter

import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.ItemFeedback
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFeedbackTest {
    private val artist = ArtistRef("Throwaway Band", "UCt")
    private val hidden = mutableListOf<ArtistRef>()
    private val sent = mutableListOf<String>()
    private val hide: (ArtistRef) -> Unit = { hidden += it }
    private fun sender(answer: Boolean): suspend (String) -> Boolean = { sent += it; answer }

    @Test fun `with a token and the switch on, YouTube is told`() = runTest {
        assertEquals(HideOutcome.Told, YouTubeFeedback.hideArtist(artist, "T", tellYouTube = true, hide, sender(true)))
        assertEquals(listOf(artist), hidden)
        assertEquals(listOf("T"), sent)
    }

    @Test fun `without a token it hides locally and sends nothing`() = runTest {
        assertEquals(HideOutcome.Local, YouTubeFeedback.hideArtist(artist, null, tellYouTube = true, hide, sender(true)))
        assertEquals(listOf(artist), hidden)
        assertTrue(sent.isEmpty())
    }

    @Test fun `with the switch off it hides locally and sends nothing`() = runTest {
        assertEquals(HideOutcome.Local, YouTubeFeedback.hideArtist(artist, "T", tellYouTube = false, hide, sender(true)))
        assertEquals(listOf(artist), hidden)
        assertTrue(sent.isEmpty())
    }

    @Test fun `a failed, refused or stuck call still hides locally`() = runTest {
        val boom: suspend (String) -> Boolean = { throw java.io.IOException("offline") }
        assertEquals(HideOutcome.NotTold, YouTubeFeedback.hideArtist(artist, "T", true, hide, boom))
        assertEquals(HideOutcome.NotTold, YouTubeFeedback.hideArtist(artist, "T", true, hide, sender(false)))
        assertEquals(HideOutcome.NotTold, YouTubeFeedback.hideArtist(artist, "T", true, hide, { awaitCancellation() }, timeoutMs = 1_000))
        assertEquals(3, hidden.size)
    }

    @Test fun `the hide happens before the call`() = runTest {
        var hiddenWhenSent = false
        YouTubeFeedback.hideArtist(artist, "T", true, hide, { hiddenWhenSent = hidden.isNotEmpty(); true })
        assertTrue(hiddenWhenSent)
    }

    @Test fun `the token goes only with a single artist`() {
        assertEquals("T", YouTubeFeedback.tokenFor(listOf(artist), "T"))
        assertNull(YouTubeFeedback.tokenFor(listOf(artist, ArtistRef("Other", null)), "T"))
        assertNull(YouTubeFeedback.tokenFor(listOf(artist), null))
    }

    @Test fun `Not interested removes first, then reports the call`() = runTest {
        val dismissed = mutableListOf<String>()
        assertTrue(YouTubeFeedback.notInterested("N", { dismissed += it }, sender(true)))
        assertFalse(YouTubeFeedback.notInterested("M", { dismissed += it }, { throw IllegalStateException() }))
        assertEquals(listOf("N", "M"), dismissed)
    }

    @Test fun `the setting is on by default and for settings saved before it`() {
        assertTrue(FilterSettings().tellYouTube)
        assertTrue(FilterSettings.decode("""{"artists":[],"musicBrainz":false}""").tellYouTube)
        assertFalse(FilterSettings.decode(FilterSettings(tellYouTube = false).encode()).tellYouTube)
    }

    private fun song(id: String, notInterested: String?) =
        MusicItem.Song(id, id, listOf(artist), null, null, null, isVideo = false, feedback = notInterested?.let { ItemFeedback(it, null) })

    @Test fun `dismissed items leave every shelf of this load, and a shelf goes only when empty`() {
        val a = song("a", "Na")
        val shelves = listOf(
            Shelf("one", listOf(a, song("b", "Nb"), song("c", null))),
            Shelf("two", listOf(song("a", "Na2"))), // the same song again, with its own token
            Shelf("three", listOf(song("d", "Nd"))),
        )
        val out = withoutDismissed(shelves, setOf("Na"))
        assertEquals(listOf("one", "three"), out.map { it.title })
        assertEquals(listOf("b", "c"), out[0].items.map { (it as MusicItem.Song).videoId })
        assertTrue(withoutDismissed(shelves, emptySet()) === shelves)
        // The next load has new tokens: nothing is dismissed there.
        assertTrue(withoutDismissed(shelves, setOf("old")) === shelves)
    }
}
