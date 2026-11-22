package dev.thathunky.meteora.playback

import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionButtonsTest {
    @Test fun repeatCyclesOffAllOne() {
        assertEquals(RepeatMode.All, SessionButtons.nextRepeat(RepeatMode.None))
        assertEquals(RepeatMode.One, SessionButtons.nextRepeat(RepeatMode.All))
        assertEquals(RepeatMode.None, SessionButtons.nextRepeat(RepeatMode.One))
        // Not known yet (the PC has not reported): the first press turns repeat on.
        assertEquals(RepeatMode.All, SessionButtons.nextRepeat(RepeatMode.Unknown))
    }

    @Test fun actionsMapToCommands() {
        assertEquals(Command.ToggleLike, SessionButtons.commandFor(SessionButtons.LIKE, null))
        assertEquals(Command.ToggleDislike, SessionButtons.commandFor(SessionButtons.DISLIKE, null))
        assertEquals(Command.ToggleShuffle, SessionButtons.commandFor(SessionButtons.SHUFFLE, null))
        assertEquals(Command.SetRepeat(RepeatMode.All), SessionButtons.commandFor(SessionButtons.REPEAT, null))
        assertNull(SessionButtons.commandFor("something.else", null))
    }
}
