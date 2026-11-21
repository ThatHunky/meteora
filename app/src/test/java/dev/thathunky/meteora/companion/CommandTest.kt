package dev.thathunky.meteora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandTest {

    private fun json(c: Command) = c.toJson().toString()

    @Test fun `simple commands have no data`() {
        assertEquals("""{"command":"playPause"}""", json(Command.PlayPause))
        assertEquals("""{"command":"shuffle"}""", json(Command.ToggleShuffle))
    }

    @Test fun `changeVideo omits absent ids instead of sending null`() {
        // null is coerced to "" by the server and wipes the queue (spike S1).
        assertEquals("""{"command":"changeVideo","data":{"playlistId":"LM"}}""", json(Command.ChangeVideo(playlistId = "LM")))
        assertEquals("""{"command":"changeVideo","data":{"videoId":"abc"}}""", json(Command.ChangeVideo(videoId = "abc")))
        assertEquals(
            """{"command":"changeVideo","data":{"videoId":"abc","playlistId":"RDAMVMabc"}}""",
            json(Command.ChangeVideo("abc", "RDAMVMabc")),
        )
    }

    @Test fun `changeVideo needs at least one id`() {
        assertThrows(IllegalArgumentException::class.java) { Command.ChangeVideo() }
        assertThrows(IllegalArgumentException::class.java) { Command.ChangeVideo("", "") }
    }

    @Test fun `queueAdd needs exactly one id`() {
        assertEquals(
            """{"command":"queueAdd","data":{"videoId":"v","index":3}}""",
            json(Command.QueueAdd(videoId = "v", index = 3)),
        )
        assertThrows(IllegalArgumentException::class.java) { Command.QueueAdd("v", "p", 0) }
        assertThrows(IllegalArgumentException::class.java) { Command.QueueAdd(index = 0) }
    }

    @Test fun `numeric payloads`() {
        assertEquals("""{"command":"setVolume","data":42}""", json(Command.SetVolume(42)))
        assertEquals("""{"command":"seekTo","data":30.5}""", json(Command.SeekTo(30.5)))
        assertEquals("""{"command":"repeatMode","data":2}""", json(Command.SetRepeat(RepeatMode.One)))
        assertEquals("""{"command":"queueMove","data":{"fromIndex":3,"toIndex":4}}""", json(Command.QueueMove(3, 4)))
        assertThrows(IllegalArgumentException::class.java) { Command.SetVolume(101) }
    }

    @Test fun `continuous kinds`() {
        assertEquals(Command.Kind.Volume, Command.SetVolume(1).kind)
        assertEquals(Command.Kind.Seek, Command.SeekTo(1.0).kind)
        assertEquals(Command.Kind.Discrete, Command.Next.kind)
    }
}
