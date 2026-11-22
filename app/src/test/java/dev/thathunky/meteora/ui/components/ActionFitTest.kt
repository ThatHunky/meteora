package dev.thathunky.meteora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Widths are dp at density 1, estimated as in the layout sweep: JetBrains Mono at the Terminal
 * label size (13 sp) is about 7.8 dp a character, a bracketed label adds four characters, and a
 * Terminal text button pads 6 dp a side.
 */
class ActionFitTest {
    // A playlist header: Play (filled, weight 1.4), Shuffle (tonal, weight 1), Download, Share.
    private fun header(play: Int, shuffle: Int, download: Int, share: Int) = listOf(
        FitItem(play, compact = 56, priority = 0, weight = 1.4f),
        FitItem(shuffle, compact = 56, priority = 1, weight = 1f),
        FitItem(download, compact = 48, priority = 2),
        FitItem(share, compact = 56, priority = 3),
    )

    @Test fun `a row that fits as a weighted Row lays out exactly as that Row`() {
        val fit = fitActions(header(100, 90, 48, 56), available = 379, gap = 8, wrap = true)
        assertEquals(listOf(false, false, false, false), fit.compact)
        assertEquals(listOf(listOf(0, 1, 2, 3)), fit.lines)
        // Row: (379 - 24 - 104) = 251 split 1.4 : 1.
        assertEquals(listOf(146, 105, 48, 56), fit.widths)
    }

    @Test fun `the reported Terminal header at 411 dp fits and Shuffle keeps its natural width`() {
        // [ play ] 74, [ shuffle ] 98, [ download ] 106, [ share ] 82: 384 of 387 dp.
        val fit = fitActions(header(74, 98, 106, 82), available = 387, gap = 8, wrap = true)
        assertEquals(listOf(false, false, false, false), fit.compact)
        // Row's weights would give Shuffle 72 dp and cut it; natural widths plus the spare instead.
        assertEquals(listOf(75, 100, 106, 82), fit.widths)
        assertWidthsAtLeastNatural(fit, listOf(74, 98, 106, 82))
    }

    @Test fun `Terminal at 360 dp drops Share and Download to icons, least important first`() {
        val fit = fitActions(header(74, 98, 106, 82), available = 336, gap = 8, wrap = true)
        assertEquals(listOf(false, false, true, true), fit.compact)
        assertEquals(listOf(listOf(0, 1, 2, 3)), fit.lines)
        assertEquals(336 - 24, fit.widths.sum())
    }

    @Test fun `Terminal in Ukrainian at 411 dp keeps Play and Shuffle as text`() {
        // [ відтворити ] 121, [ перемішати ] 121, [ завантажити ] 129, [ поділитися ] 121.
        val fit = fitActions(header(121, 121, 129, 121), available = 387, gap = 8, wrap = true)
        assertEquals(listOf(false, false, true, true), fit.compact)
        assertEquals(1, fit.lines.size)
    }

    @Test fun `the primary action stays full and the row wraps when icons are not enough`() {
        // Retro capitals at font scale 1.3 on a 360 dp phone.
        val items = listOf(
            FitItem(191, compact = 51, priority = 0, weight = 1.4f),
            FitItem(191, compact = 51, priority = 1, weight = 1f),
            FitItem(48, priority = 2),
            FitItem(51, priority = 3),
        )
        val fit = fitActions(items, available = 328, gap = 8, wrap = true)
        assertEquals(listOf(false, true, false, false), fit.compact)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), fit.lines)
        // The first line is filled by its weighted items.
        assertEquals(328 - 16, fit.lines[0].sumOf { fit.widths[it] })
    }

    @Test fun `items of one priority go compact together`() {
        // Now Playing in Terminal at 360 dp: shuffle, [ previous ], [ ▶ ], [ next ], repeat.
        val items = listOf(
            FitItem(48, priority = 2),
            FitItem(106, compact = 48, priority = 1),
            FitItem(51, compact = 48, priority = 0),
            FitItem(74, compact = 48, priority = 1),
            FitItem(48, priority = 2),
        )
        val fit = fitActions(items, available = 320, gap = 0, wrap = false)
        assertEquals(listOf(false, true, false, true, false), fit.compact)
        assertEquals(listOf(48, 48, 51, 48, 48), fit.widths)
    }

    @Test fun `without wrapping the primary goes compact before anything shrinks`() {
        val items = listOf(FitItem(200, compact = 48, priority = 0), FitItem(150, compact = 48, priority = 1))
        val fit = fitActions(items, available = 220, gap = 0, wrap = false)
        assertEquals(listOf(true, true), fit.compact)
        assertEquals(listOf(48, 48), fit.widths)
    }

    @Test fun `as a last resort a single line shrinks in proportion and stays inside the width`() {
        val fit = fitActions(listOf(FitItem(200), FitItem(200)), available = 300, gap = 0, wrap = false)
        assertEquals(listOf(150, 150), fit.widths)
        val odd = fitActions(listOf(FitItem(100), FitItem(100), FitItem(100)), available = 200, gap = 4, wrap = false)
        assertEquals(200 - 8, odd.widths.sum())
    }

    @Test fun `a filler takes what the buttons leave`() {
        // Now Playing's top row in Terminal at 360 dp: collapse, the target chip (at least 120),
        // [ lyrics ], [ queue ], more.
        val items = listOf(
            FitItem(48),
            FitItem(120, weight = 1f),
            FitItem(90, compact = 48, priority = 2),
            FitItem(82, compact = 48, priority = 1),
            FitItem(48),
        )
        val fit = fitActions(items, available = 320, gap = 0, wrap = false)
        assertEquals(listOf(false, false, true, true, false), fit.compact)
        assertEquals(128, fit.widths[1])
        // At 411 dp only Lyrics goes.
        val wide = fitActions(items, available = 371, gap = 0, wrap = false)
        assertEquals(listOf(false, false, true, false, false), wide.compact)
        assertEquals(371 - 48 - 48 - 82 - 48, wide.widths[1])
    }

    @Test fun `an empty row and a single item`() {
        assertEquals(emptyList<Int>(), fitActions(emptyList(), 100, 8, true).widths)
        val one = fitActions(listOf(FitItem(500, priority = 0)), available = 300, gap = 8, wrap = true)
        assertEquals(listOf(listOf(0)), one.lines)
        assertEquals(listOf(300), one.widths)
    }

    private fun assertWidthsAtLeastNatural(fit: ActionFit, natural: List<Int>) {
        fit.widths.zip(natural).forEach { (w, n) -> assertTrue("$w < $n", w >= n) }
    }
}
