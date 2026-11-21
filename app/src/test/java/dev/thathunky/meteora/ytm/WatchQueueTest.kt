package dev.thathunky.meteora.ytm

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
}
