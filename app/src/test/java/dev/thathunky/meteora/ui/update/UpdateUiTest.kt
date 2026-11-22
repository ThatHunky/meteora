package dev.thathunky.meteora.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateUiTest {
    @Test fun `notes lose their Markdown`() {
        val md = "## Changes\r\n\r\n**What works**\n- Remote for `YTMDesktop`\n  * nested\nSee [the page](https://example.com).\n"
        assertEquals("Changes\n\nWhat works\n• Remote for YTMDesktop\n  • nested\nSee the page.", plainNotes(md))
    }

    @Test fun `plain text stays`() {
        assertEquals("First alpha. Expect bugs.", plainNotes("First alpha. Expect bugs.\n"))
    }
}
