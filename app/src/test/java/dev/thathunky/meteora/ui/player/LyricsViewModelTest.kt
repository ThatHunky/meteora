package dev.thathunky.meteora.ui.player

import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.LyricLine
import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.lyrics.LyricsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LyricsViewModelTest {

    private fun track(id: String) = Track(id, "Title", "Artist", "Album", null, null, 200.0, null, LikeStatus.Unknown, false)
    private fun lyrics(text: String, stopgap: Boolean = false) = Lyrics(listOf(LyricLine(null, text)), synced = false, source = null, stopgap = stopgap)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun `a late answer for the previous track does not replace the new one`() = runTest {
        val slowA = CompletableDeferred<Lyrics>()
        // A source that cannot be cancelled in time, like a response already on its way.
        val vm = LyricsViewModel(LyricsRepository({ t ->
            if (t.videoId == "aaaaaaaaaaa") withContext(NonCancellable) { slowA.await() } else lyrics("b")
        }))
        vm.load(track("aaaaaaaaaaa"))
        vm.load(track("bbbbbbbbbbb"))
        assertEquals("bbbbbbbbbbb" to LyricsUi.Found(lyrics("b")), vm.ui.value)
        slowA.complete(lyrics("a"))
        assertEquals("bbbbbbbbbbb" to LyricsUi.Found(lyrics("b")), vm.ui.value)
    }

    @Test fun `a stopgap answer is asked again when the page opens again`() = runTest {
        var calls = 0
        val vm = LyricsViewModel(LyricsRepository({ calls++; if (calls == 1) lyrics("plain", stopgap = true) else lyrics("timed") }))
        vm.load(track("aaaaaaaaaaa"))
        vm.load(track("aaaaaaaaaaa"))
        assertEquals(2, calls)
        assertEquals("aaaaaaaaaaa" to LyricsUi.Found(lyrics("timed")), vm.ui.value)
        vm.load(track("aaaaaaaaaaa"))
        assertEquals(2, calls)
    }
}
