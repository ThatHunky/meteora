package dev.thathunky.meteora.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLineTest {

    private val lines = listOf(
        LyricLine(1_000, "one"),
        LyricLine(4_000, ""), // instrumental gap
        LyricLine(9_000, "two"),
        LyricLine(12_500, "three"),
    )

    @Test fun `before the first line`() {
        assertEquals(-1, currentLine(lines, 0))
        assertEquals(-1, currentLine(lines, 999))
    }

    @Test fun `exactly on a start`() {
        assertEquals(0, currentLine(lines, 1_000))
        assertEquals(2, currentLine(lines, 9_000))
    }

    @Test fun `between lines`() {
        assertEquals(0, currentLine(lines, 3_999))
        assertEquals(1, currentLine(lines, 5_000))
        assertEquals(2, currentLine(lines, 12_499))
    }

    @Test fun `after the last`() {
        assertEquals(3, currentLine(lines, 600_000))
    }

    @Test fun `no lines or no times`() {
        assertEquals(-1, currentLine(emptyList(), 5_000))
        assertEquals(-1, currentLine(listOf(LyricLine(null, "a"), LyricLine(null, "b")), 5_000))
    }

    @Test fun `one line`() {
        assertEquals(-1, currentLine(listOf(LyricLine(500, "a")), 0))
        assertEquals(0, currentLine(listOf(LyricLine(500, "a")), 500))
    }
}
