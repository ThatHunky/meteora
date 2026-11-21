package dev.thathunky.meteora.playback

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class ChunkedDataSourceTest {
    private val mib = 1024L * 1024

    /** The requests made for a range, as (start, length), the way the data source walks it. */
    private fun requests(start: Long, length: Long): List<Pair<Long, Long>> {
        val end = start + length
        var p = start
        val out = ArrayList<Pair<Long, Long>>()
        while (p < end) {
            val n = ChunkedDataSource.nextChunkLength(p, end, ChunkedDataSource.CHUNK_BYTES)
            out += p to n
            p += n
        }
        return out
    }

    @Test fun longRangeIsSplitIntoTenMiBRequests() {
        assertEquals(listOf(0L to 10 * mib, 10 * mib to 10 * mib, 20 * mib to 5 * mib), requests(0, 25 * mib))
    }

    @Test fun midFileRangeKeepsItsStart() {
        assertEquals(listOf(3 * mib to 10 * mib, 13 * mib to 2 * mib), requests(3 * mib, 12 * mib))
    }

    @Test fun exactlyOneChunk() {
        assertEquals(listOf(0L to 10 * mib), requests(0, 10 * mib))
        assertEquals(listOf(5L to 100L), requests(5, 100))
    }
}
