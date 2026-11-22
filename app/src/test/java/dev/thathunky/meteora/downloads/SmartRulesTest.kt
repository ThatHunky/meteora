package dev.thathunky.meteora.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenRulesTest {
    @Test fun `play past half or four minutes, skip in the first 30 s by Next`() {
        assertEquals(ListenOutcome.Play, ListenRules.outcome(100_000, 200_000, byUser = true))
        assertEquals(ListenOutcome.Nothing, ListenRules.outcome(99_000, 200_000, byUser = true))
        // A 20-minute track counts after 4 minutes.
        assertEquals(ListenOutcome.Play, ListenRules.outcome(240_000, 1_200_000, byUser = false))
        assertEquals(ListenOutcome.Skip, ListenRules.outcome(29_000, 200_000, byUser = true))
        // Reaching the end of a very short track is a play, not a skip.
        assertEquals(ListenOutcome.Play, ListenRules.outcome(20_000, 20_000, byUser = false))
        assertEquals(ListenOutcome.Nothing, ListenRules.outcome(10_000, 200_000, byUser = false))
    }

    @Test fun `counting`() {
        val song = Listen("a", 0, 0, 0, "T", "A")
        val one = ListenRules.apply(null, song, ListenOutcome.Play, 1000)!!
        assertEquals(1, one.plays)
        assertEquals(1000L, one.lastPlayedMs)
        val two = ListenRules.apply(one, song, ListenOutcome.Skip, 2000)!!
        assertEquals(1, two.plays)
        assertEquals(1, two.skips)
        assertNull(ListenRules.apply(two, song, ListenOutcome.Nothing, 3000))
    }

    @Test fun `a skip does not count as having played it`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 1000 * day
        val old = Listen("a", 10, 0, now - 60 * day, "T", "A")
        val skipped = ListenRules.apply(old, old, ListenOutcome.Skip, now)!!
        assertEquals(now - 60 * day, skipped.lastPlayedMs)
        // The score goes down, not up (it went from 2.5 to 8.5 when a skip reset the decay).
        assertTrue(SmartRules.habit(skipped, now) < SmartRules.habit(old, now))
        // Not protected from removal by a skip.
        val plan = SmartRules.plan(listOf("a"), SmartRules.Wanted(listOf(SmartSong("b", "T", "B", null, null, 1.0)), mapOf("a" to 0.1, "b" to 5.0)), 1, mapOf("a" to skipped), now)
        assertEquals(listOf("a"), plan.remove)
    }

    @Test fun `which jumps leave a track and which can be a skip`() {
        // Next, or a tap on a later queue entry.
        assertEquals(true, ListenRules.leftByUser(ListenRules.Jump.Seek, sameItem = false, forward = true, skippedForError = false))
        // Previous, or a tap on an earlier entry: left, but not a skip.
        assertEquals(false, ListenRules.leftByUser(ListenRules.Jump.Seek, sameItem = false, forward = false, skippedForError = false))
        // The player moving past a track that failed to play.
        assertEquals(false, ListenRules.leftByUser(ListenRules.Jump.Seek, sameItem = false, forward = true, skippedForError = true))
        // A seek inside the track.
        assertNull(ListenRules.leftByUser(ListenRules.Jump.Seek, sameItem = true, forward = false, skippedForError = false))
        // The end, repeat-one included.
        assertEquals(false, ListenRules.leftByUser(ListenRules.Jump.Auto, sameItem = true, forward = false, skippedForError = false))
        // A new queue replacing it.
        assertEquals(false, ListenRules.leftByUser(ListenRules.Jump.Remove, sameItem = false, forward = false, skippedForError = false))
        assertNull(ListenRules.leftByUser(ListenRules.Jump.Other, sameItem = false, forward = true, skippedForError = false))
    }
}

class SmartRulesTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1000 * day

    private fun s(id: String, artist: String = "A$id") = SmartSong(id, "T$id", artist, null, null, 200.0)
    private fun l(id: String, plays: Int, skips: Int = 0, daysAgo: Int = 0) = Listen(id, plays, skips, now - daysAgo * day, "T$id", "A$id")

    @Test fun `habit halves after a month and skips pull it to zero`() {
        assertEquals(4.0, SmartRules.habit(l("a", 4), now), 1e-9)
        assertEquals(2.0, SmartRules.habit(l("a", 4, daysAgo = 30), now), 1e-9)
        assertEquals(1.0, SmartRules.habit(l("a", 4, daysAgo = 60), now), 1e-9)
        assertEquals(2.5, SmartRules.habit(l("a", 4, skips = 1), now), 1e-9)
        // Skipped more often than played: zero, never negative.
        assertEquals(0.0, SmartRules.habit(l("a", 2, skips = 3), now), 1e-9)
    }

    @Test fun `like and listen again bonuses`() {
        assertEquals(5.0, SmartRules.likeBonus(0), 1e-9)
        assertEquals(4.0, SmartRules.likeBonus(25), 1e-9)
        assertEquals(3.0, SmartRules.likeBonus(50), 1e-9)
        assertEquals(3.0, SmartRules.likeBonus(400), 1e-9)
        assertEquals(0.0, SmartRules.likeBonus(null), 1e-9)
        assertEquals(2.0, SmartRules.listenAgainBonus(0, 5), 1e-9)
        assertEquals(1.0, SmartRules.listenAgainBonus(2, 5), 1e-9)
        assertEquals(0.0, SmartRules.listenAgainBonus(4, 5), 1e-9)
        assertEquals(2.0, SmartRules.listenAgainBonus(0, 1), 1e-9)
    }

    private fun inputs(size: Int, listens: List<Listen> = emptyList(), likes: List<SmartSong> = emptyList(), again: List<SmartSong> = emptyList(), mix: List<SmartSong> = emptyList(), manual: Set<String> = emptySet()) =
        SmartRules.Inputs(size, listens.associateBy { it.videoId }, likes, again, mix, manual, now)

    @Test fun `three shares of the size`() {
        // 20 played songs, 20 likes nobody played, a mix of 20 new songs.
        val played = (1..20).map { l("p$it", plays = 30 - it) }
        val likes = (1..20).map { s("l$it") }
        val mix = (1..20).map { s("m$it") }
        val w = SmartRules.wanted(inputs(20, played, likes, mix = mix))
        assertEquals(20, w.songs.size)
        val ids = w.songs.map { it.videoId }
        // Favourites 12: the likes score 3..5 and outrank the weaker plays, so both mix in by score.
        // Likes 5 more, fresh 3 from the mix.
        assertEquals(3, ids.count { it.startsWith("m") })
        assertTrue(ids.count { it.startsWith("l") } >= 5)
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test fun `no mix gives its share to favourites`() {
        val played = (1..30).map { l("p$it", plays = 40 - it) }
        val w = SmartRules.wanted(inputs(10, played))
        assertEquals((1..10).map { "p$it" }, w.songs.map { it.videoId })
    }

    @Test fun `a share that cannot be filled passes its room to favourites`() {
        val played = (1..30).map { l("p$it", plays = 40 - it) }
        // Two likes only, both already among the favourites by score.
        val w = SmartRules.wanted(inputs(10, played + l("x", 0), likes = listOf(s("p1"), s("p2"))))
        assertEquals(10, w.songs.size)
        assertTrue(w.songs.all { it.videoId.startsWith("p") })
    }

    @Test fun `manual downloads are outside the limit`() {
        // 300 songs downloaded by hand, size 100: none of them takes a smart slot.
        val manual = (1..300).map { "h$it" }.toSet()
        val played = (1..300).map { l("h$it", plays = 50) } + (1..150).map { l("p$it", plays = 10) }
        val w = SmartRules.wanted(inputs(100, played, likes = (1..50).map { s("h$it") }, manual = manual))
        assertEquals(100, w.songs.size)
        assertTrue(w.songs.none { it.videoId in manual })
        // A manual song that is removed again can come back as a smart one if it scores high.
        val back = SmartRules.wanted(inputs(100, played, manual = manual - "h1"))
        assertTrue(back.songs.any { it.videoId == "h1" })
    }

    @Test fun `fresh share keeps to two in a row per artist and new songs only`() {
        val mix = listOf(s("1", "X"), s("2", "X"), s("3", "X"), s("4", "Y"), s("5", "X"))
        assertEquals(listOf("1", "2", "4", "3", "5"), SmartRules.fresh(mix, 5).map { it.videoId })
        assertEquals(listOf("1", "2", "4"), SmartRules.fresh(mix, 3).map { it.videoId })
        // A mix song already played on this phone is not "fresh".
        val w = SmartRules.wanted(inputs(10, listOf(l("m1", plays = 1)), mix = listOf(s("m1"), s("m2"))))
        assertEquals(listOf("m1", "m2"), w.songs.map { it.videoId }.sorted())
        assertEquals("m1", w.songs.first().videoId) // as a favourite, by its play
    }

    private fun wanted(vararg scored: Pair<String, Double>) =
        SmartRules.Wanted(scored.map { s(it.first) }, scored.toMap())

    @Test fun `a kept song goes only for one scoring 20 percent higher`() {
        val current = listOf("a", "b")
        val listens = mapOf("a" to l("a", 1, daysAgo = 100), "b" to l("b", 1, daysAgo = 100))
        // "a" dropped out of the wanted list; "n" scores 1.1 times it: not enough.
        val scores = mapOf("a" to 1.0, "b" to 5.0, "n" to 1.1)
        val w1 = SmartRules.Wanted(listOf(s("b"), s("n")), scores)
        val p1 = SmartRules.plan(current, w1, 2, listens, now)
        assertEquals(listOf("a", "b"), p1.keep.sorted())
        assertTrue(p1.add.isEmpty() && p1.remove.isEmpty())
        val w2 = SmartRules.Wanted(listOf(s("b"), s("n")), scores + ("n" to 1.2))
        val p2 = SmartRules.plan(current, w2, 2, listens, now)
        assertEquals(listOf("a"), p2.remove)
        assertEquals(listOf("n"), p2.add.map { it.videoId })
    }

    @Test fun `at most 20 percent replaced in one refresh`() {
        val current = (1..10).map { "c$it" }
        val listens = current.associateWith { l(it, 1, daysAgo = 100) }
        val scores = current.associateWith { 1.0 } + (1..10).associate { "n$it" to 10.0 }
        val w = SmartRules.Wanted((1..10).map { s("n$it") }, scores)
        val p = SmartRules.plan(current, w, 10, listens, now)
        assertEquals(2, p.remove.size)
        assertEquals(2, p.add.size)
        assertEquals(8, p.keep.size)
    }

    @Test fun `nothing played in the last 14 days is removed`() {
        val listens = mapOf("a" to l("a", 1, daysAgo = 13))
        val w = SmartRules.Wanted(listOf(s("n")), mapOf("a" to 0.1, "n" to 100.0))
        val p = SmartRules.plan(listOf("a"), w, 1, listens, now)
        assertEquals(listOf("a"), p.keep)
        assertTrue(p.remove.isEmpty())
        val older = SmartRules.plan(listOf("a"), w, 1, mapOf("a" to l("a", 1, daysAgo = 15)), now)
        assertEquals(listOf("a"), older.remove)
    }

    @Test fun `free room is filled without counting as replacement`() {
        // A smart song became manual (left out of current): its slot is refilled at once.
        val w = wanted("n1" to 1.0, "n2" to 1.0, "n3" to 1.0)
        val p = SmartRules.plan(listOf("k"), w, 4, emptyMap(), now)
        assertEquals(listOf("n1", "n2", "n3"), p.add.map { it.videoId })
        assertEquals(listOf("k"), p.keep)
    }

    @Test fun `a lowered size drops the weakest`() {
        val w = SmartRules.Wanted(listOf(s("a")), mapOf("a" to 5.0, "b" to 1.0, "c" to 3.0))
        val p = SmartRules.plan(listOf("a", "b", "c"), w, 2, emptyMap(), now)
        assertEquals(listOf("b"), p.remove)
        assertFalse("b" in p.keep)
    }

    @Test fun `Home shelves`() {
        assertTrue(SmartHome.isListenAgain("Listen again"))
        assertTrue(SmartHome.isListenAgain("Слухати знову"))
        assertFalse(SmartHome.isListenAgain("Quick picks"))
        assertTrue(SmartHome.isMix("My Supermix"))
        assertTrue(SmartHome.isMix("Мій мікс 1"))
        assertFalse(SmartHome.isMix("Liked music"))
    }
}
