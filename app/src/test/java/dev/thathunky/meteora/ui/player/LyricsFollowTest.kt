package dev.thathunky.meteora.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFollowTest {

    @Test fun `before the first line the list goes back to the first line`() {
        // A seek back to 0:00 gives index -1; the list used to stay at the old line.
        assertEquals(0, followItem(-1))
        assertEquals(0, followItem(0))
        assertEquals(7, followItem(7))
    }

    @Test fun `the next line slides, a seek jumps`() {
        assertTrue(followSlides(4, 5))
        assertTrue(followSlides(5, 4))
        assertFalse(followSlides(2, 40))
        assertFalse(followSlides(40, 0))
    }
}
