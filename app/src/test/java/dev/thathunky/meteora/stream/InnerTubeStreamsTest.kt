package dev.thathunky.meteora.stream

import dev.thathunky.meteora.playback.parseDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Format picking and visitor data moved to the Rust core; their tests are in rust/meteora-core/tests/streams.rs. */
class InnerTubeStreamsTest {
    @Test fun oldTierNamesStillRead() {
        assertEquals(StreamQuality.Low, StreamQuality.fromStored("DataSaver"))
        assertEquals(StreamQuality.Normal, StreamQuality.fromStored("Balanced"))
        assertEquals(StreamQuality.High, StreamQuality.fromStored("High"))
        assertEquals(StreamQuality.Normal, StreamQuality.fromStored("Normal"))
        assertNull(StreamQuality.fromStored("Ultra"))
        assertNull(StreamQuality.fromStored(null))
    }

    @Test fun durations() {
        assertEquals(374.0, parseDuration("6:14"), 0.0)
        assertEquals(3723.0, parseDuration("1:02:03"), 0.0)
        assertEquals(0.0, parseDuration(null), 0.0)
    }
}
