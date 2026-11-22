package dev.thathunky.meteora.ui.downloads

import dev.thathunky.meteora.downloads.DownloadEntry
import dev.thathunky.meteora.downloads.DownloadSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsUiTest {

    private fun song(id: String, title: String, collections: Set<String> = emptySet()) =
        DownloadEntry(id, title, "A", null, null, null, null, null, 1.0, single = true, collections = collections)

    @Test fun `songs sort in the language's alphabet, not by code point`() {
        // By lowercase() code points "Іній" (і, U+0456) came after "Яр" (я, U+044F).
        val sorted = sortedSongs(listOf(song("1", "Яр"), song("2", "Іній"), song("3", "арка"), song("4", "Гуси")), Locale.forLanguageTag("uk"))
        assertEquals(listOf("арка", "Гуси", "Іній", "Яр"), sorted.map { it.title })
    }

    @Test fun `case does not split the list and equal titles keep a fixed order`() {
        val sorted = sortedSongs(listOf(song("b", "song"), song("a", "Song"), song("c", "Abc")), Locale.ENGLISH)
        assertEquals(listOf("c", "a", "b"), sorted.map { it.videoId })
    }

    @Test fun `a collection track offers no delete that would do nothing`() {
        assertTrue(removableFromList(song("1", "x")))
        assertFalse(removableFromList(song("1", "x", setOf("OLAK5uy_album"))))
    }

    @Test fun `the smart size slider stops on tens and never at zero`() {
        val values = (0..SMART_STEPS + 1).map { SMART_RANGE.start + it * (SMART_RANGE.endInclusive - SMART_RANGE.start) / (SMART_STEPS + 1) }
        assertEquals(10f, values.first())
        assertEquals(DownloadSettings.SMART_MAX.toFloat(), values.last())
        assertTrue(values.all { it.toInt() % 10 == 0 })
    }
}
