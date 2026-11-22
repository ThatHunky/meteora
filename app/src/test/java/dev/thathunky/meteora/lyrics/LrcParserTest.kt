package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test fun `times with two and three fraction digits and none`() {
        val l = LrcParser.parse("[00:12.34]a\n[01:02.345]b\n[02:03]c")
        assertEquals(listOf(LyricLine(12_340, "a"), LyricLine(62_345, "b"), LyricLine(123_000, "c")), l)
    }

    @Test fun `metadata tags and junk are skipped`() {
        val l = LrcParser.parse("[ar:Someone]\n[ti:Song]\n[length: 03:20]\n[offset:+0]\nnot a line\n\n[00:01.00]first")
        assertEquals(listOf(LyricLine(1_000, "first")), l)
    }

    @Test fun `several tags on one line, sorted into time order`() {
        val l = LrcParser.parse("[00:10.00][00:30.00]chorus\n[00:20.00]verse")
        assertEquals(listOf(LyricLine(10_000, "chorus"), LyricLine(20_000, "verse"), LyricLine(30_000, "chorus")), l)
    }

    @Test fun `empty lines are gaps and word tags are dropped`() {
        val l = LrcParser.parse("[00:05.00]\n[00:06.00]<00:06.00>hel <00:06.50>lo\r\n")
        assertEquals(listOf(LyricLine(5_000, ""), LyricLine(6_000, "hel lo")), l)
    }

    @Test fun `nothing timed`() {
        assertTrue(LrcParser.parse("just text\nmore").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test fun `spaces between time tags still read as tags`() {
        val l = LrcParser.parse("[00:10.00] [00:30.00] chorus")
        assertEquals(listOf(LyricLine(10_000, "chorus"), LyricLine(30_000, "chorus")), l)
    }

    @Test fun `the offset tag moves every line, positive is sooner`() {
        // Never before zero.
        assertEquals(listOf(LyricLine(0, "b"), LyricLine(9_500, "a")), LrcParser.parse("[offset:+500]\n[00:10.00]a\n[00:00.20]b"))
        assertEquals(listOf(LyricLine(10_250, "a")), LrcParser.parse("[00:10.00]a\n[offset:-250]"))
    }

    @Test fun `a byte order mark does not hide the first line`() {
        assertEquals(listOf(LyricLine(1_000, "first")), LrcParser.parse("\uFEFF[00:01.00]first"))
    }
}
