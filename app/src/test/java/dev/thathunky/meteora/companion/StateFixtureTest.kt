package dev.thathunky.meteora.companion

import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.TrackState
import dev.thathunky.meteora.domain.toDomain
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses real captures from YTMDesktop 2.1.0 (spike S1). */
class StateFixtureTest {

    @Serializable
    private data class Frame(val dtMs: Long, val state: StateDto)

    private fun read(name: String) =
        requireNotNull(javaClass.getResource("/fixtures/companion/$name")) { name }.readText()

    private fun frames(name: String) = CompanionJson.decodeFromString<List<Frame>>(read("realtime-$name.json"))

    @Test fun `snapshot parses`() {
        val s = CompanionJson.decodeFromString<StateDto>(read("state-paused-unknown.json")).toDomain(0)
        assertEquals(TrackState.Unknown, s.trackState)
        assertEquals(23, s.volume)
        assertEquals(50, s.queue.size)
        assertEquals("vh2ED26hMNU", s.track?.videoId)
        assertEquals("Radiohead", s.track?.artist)
        assertEquals(LikeStatus.Indifferent, s.track?.like)
        assertEquals(RepeatMode.None, s.repeat)
        assertTrue(s.track!!.artworkUrl!!.contains("=w1080-h1080"))
    }

    @Test fun `every captured frame parses`() {
        listOf("next", "album", "queueAdd-next", "queueMove", "volume", "shuffle").forEach { name ->
            val f = frames(name)
            assertTrue(name, f.isNotEmpty())
            f.forEach { it.state.toDomain(0) }
        }
    }

    @Test fun `next changes track and index`() {
        val f = frames("next").map { it.state.toDomain(0) }
        assertEquals(0, f.first().selectedIndex)
        val last = f.last()
        assertEquals(1, last.selectedIndex)
        assertEquals(TrackState.Playing, last.trackState)
        assertEquals("6i8zwVIFxQg", last.track?.videoId)
    }

    @Test fun `album load has automix and transient frames`() {
        val f = frames("album").map { it.state.toDomain(0) }
        assertTrue("expected a transient mid-change frame", f.any { it.isTransient })
        val last = f.last()
        assertFalse(last.isTransient)
        assertEquals("OLAK5uy_m7ToVnaKcPCN19ONmKOHl1F29TlHzkMQU", last.playlistId)
        assertEquals(5, last.queue.size)
        assertEquals(50, f.maxOf { it.automix.size })
    }

    @Test fun `volume and shuffle transitions`() {
        assertTrue(frames("volume").map { it.state.player.volume }.containsAll(listOf(20, 30, 23)))
        assertTrue(frames("shuffle").any { it.state.player.queue?.shuffleEnabled == true })
        assertNotNull(frames("queueMove").last().state.player.queue)
    }
}
