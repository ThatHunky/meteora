package dev.thathunky.meteora.companion

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandThrottlerTest {

    private class Rig(scope: TestScope, failFirstWith429: Boolean = false) {
        val sent = mutableListOf<Pair<Long, Command>>()
        private var fail = failFirstWith429
        val throttler = CommandThrottler(
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
            send = { c ->
                if (fail) {
                    fail = false
                    throw CompanionException(429, null, "Rate limit exceeded")
                }
                sent += scope.testScheduler.currentTime to c
            },
        )
    }

    @Test fun `never more than two per second`() = runTest {
        val r = Rig(this)
        repeat(6) { r.throttler.submit(Command.Next) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(6, r.sent.size)
        r.sent.map { it.first }.windowed(3).forEach { (a, _, c) -> assertTrue("3 sends within $a..$c", c - a >= 1_000) }
    }

    @Test fun `slider drag collapses to the latest value`() = runTest {
        val r = Rig(this)
        r.throttler.submit(Command.Next)
        r.throttler.submit(Command.Next) // budget now spent
        runCurrent()
        (0..50).forEach { r.throttler.submit(Command.SetVolume(it)) }
        advanceTimeBy(60_000); runCurrent()
        val volumes = r.sent.map { it.second }.filterIsInstance<Command.SetVolume>()
        assertEquals(listOf(Command.SetVolume(50)), volumes)
    }

    @Test fun `discrete commands jump ahead of continuous ones`() = runTest {
        val r = Rig(this)
        r.throttler.submit(Command.Next)
        r.throttler.submit(Command.Next)
        runCurrent()
        r.throttler.submit(Command.SeekTo(10.0))
        r.throttler.submit(Command.PlayPause)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(Command.Next, Command.Next, Command.PlayPause, Command.SeekTo(10.0)), r.sent.map { it.second })
    }

    @Test fun `429 is retried after a window`() = runTest {
        val r = Rig(this, failFirstWith429 = true)
        r.throttler.submit(Command.PlayPause)
        advanceTimeBy(500)
        assertTrue(r.sent.isEmpty())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(Command.PlayPause), r.sent.map { it.second })
    }
}
