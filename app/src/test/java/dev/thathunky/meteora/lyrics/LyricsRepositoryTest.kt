package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.LyricLine
import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.Track
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private fun track(id: String = "abcdefghijk", durationSec: Double = 200.0) =
        Track(id, "Title", "Artist", "Album", null, null, durationSec, null, LikeStatus.Unknown, false)

    private val timed = Lyrics(listOf(LyricLine(0, "a"), LyricLine(1_000, "b")), synced = true, source = "Source: X")
    private val plain = Lyrics(listOf(LyricLine(null, "a")), synced = false, source = "Source: X")

    @Test fun `youtube answers are kept, none included`() = runTest {
        var calls = 0
        val repo = LyricsRepository({ t -> calls++; if (t.videoId == "none0000000") null else timed })
        assertEquals(timed, repo.lyrics(track()))
        assertEquals(timed, repo.lyrics(track()))
        assertNull(repo.lyrics(track("none0000000")))
        assertNull(repo.lyrics(track("none0000000")))
        assertEquals(2, calls)
    }

    @Test fun `a failure is not kept`() = runTest {
        var fail = true
        val repo = LyricsRepository({ if (fail) throw IOException("offline") else plain })
        assertTrue(runCatching { repo.lyrics(track()) }.exceptionOrNull() is IOException)
        fail = false
        assertEquals(plain, repo.lyrics(track()))
    }

    private val lrcTimed = Lyrics(listOf(LyricLine(0, "x")), synced = true, source = null, provider = dev.thathunky.meteora.domain.LyricsProvider.LrcLib)

    @Test fun `youtube timed wins and lrclib is not asked`() = runTest {
        var asked = 0
        val repo = LyricsRepository({ timed }, { asked++; lrcTimed }, { true })
        assertEquals(timed, repo.lyrics(track()))
        assertEquals(0, asked)
    }

    @Test fun `lrclib off keeps youtube plain and is never asked`() = runTest {
        var asked = 0
        val repo = LyricsRepository({ plain }, { asked++; lrcTimed }, { false })
        assertEquals(plain, repo.lyrics(track()))
        assertEquals(0, asked)
    }

    @Test fun `lrclib timed replaces youtube plain or none`() = runTest {
        assertEquals(lrcTimed, LyricsRepository({ plain }, { lrcTimed }, { true }).lyrics(track()))
        assertEquals(lrcTimed, LyricsRepository({ null }, { lrcTimed }, { true }).lyrics(track()))
    }

    @Test fun `lrclib miss or failure keeps youtube`() = runTest {
        assertEquals(plain, LyricsRepository({ plain }, { null }, { true }).lyrics(track()))
        assertNull(LyricsRepository({ null }, { null }, { true }).lyrics(track()))
        var fail = true
        var asked = 0
        val repo = LyricsRepository({ plain }, { asked++; if (fail) throw IOException("down") else lrcTimed }, { true })
        assertEquals(plain, repo.lyrics(track()))
        fail = false
        assertEquals(lrcTimed, repo.lyrics(track())) // the failure was not kept
        assertEquals(2, asked)
    }

    @Test fun `turning lrclib on asks it for a track youtube already answered`() = runTest {
        var on = false
        var ytCalls = 0
        val repo = LyricsRepository({ ytCalls++; plain }, { lrcTimed }, { on })
        val before = repo.answerKey(track())
        assertEquals(plain, repo.lyrics(track()))
        on = true
        assertTrue(before != repo.answerKey(track()))
        assertEquals(lrcTimed, repo.lyrics(track()))
        assertEquals(1, ytCalls)
    }

    @Test fun `an unknown length does not ask lrclib and is not kept as none`() = runTest {
        var asked = 0
        val repo = LyricsRepository({ plain }, { asked++; lrcTimed }, { true })
        val early = track(durationSec = 0.0)
        assertEquals(plain, repo.lyrics(early))
        assertEquals(0, asked)
        assertTrue(repo.answerKey(early) != repo.answerKey(track()))
        assertEquals(lrcTimed, repo.lyrics(track()))
        assertEquals(1, asked)
    }

    @Test fun `the cache keeps the last tracks only`() = runTest {
        var calls = 0
        val repo = LyricsRepository({ calls++; plain })
        repeat(LyricsRepository.CACHE_SIZE + 1) { repo.lyrics(track("t$it")) }
        assertEquals(LyricsRepository.CACHE_SIZE + 1, calls)
        repo.lyrics(track("t${LyricsRepository.CACHE_SIZE}")) // newest: still there
        assertEquals(LyricsRepository.CACHE_SIZE + 1, calls)
        repo.lyrics(track("t0")) // oldest: dropped
        assertEquals(LyricsRepository.CACHE_SIZE + 2, calls)
    }
}
