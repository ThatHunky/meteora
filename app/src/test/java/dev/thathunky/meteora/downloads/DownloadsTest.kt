package dev.thathunky.meteora.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPathsTest {
    @Test fun `forbidden characters, dots and spaces`() {
        assertEquals("AC DC", DownloadPaths.part("AC/DC", "x"))
        assertEquals("What Is Love", DownloadPaths.part("  What: Is * Love?  ", "x"))
        assertEquals("hidden", DownloadPaths.part("...hidden", "x"))
        assertEquals("a b c d", DownloadPaths.part("a\\b|c\"d<>", "x"))
        assertEquals("Unknown artist", DownloadPaths.part("  ", "Unknown artist"))
        assertEquals("Singles", DownloadPaths.part(null, "Singles"))
        assertEquals("x", DownloadPaths.part("...", "x"))
    }

    @Test fun `Cyrillic is kept and cut at 80 characters`() {
        assertEquals("Океан Ельзи", DownloadPaths.part("Океан Ельзи", "x"))
        val long = "Ї".repeat(100)
        assertEquals(80, DownloadPaths.part(long, "x").length)
        // Emoji are two chars each: the cut never splits one.
        val emoji = "😀".repeat(90)
        assertEquals(80, DownloadPaths.part(emoji, "x").codePointCount(0, DownloadPaths.part(emoji, "x").length))
    }

    @Test fun `folder and file name`() {
        assertEquals("Music/Meteora/Океан Ельзи/Земля/", DownloadPaths.relativePath("Океан Ельзи", "Земля"))
        assertEquals("Music/Meteora/Unknown artist/Singles/", DownloadPaths.relativePath(null, ""))
        assertEquals("03 Обійми.m4a", DownloadPaths.displayName(3, "Обійми"))
        assertEquals("12 A B.m4a", DownloadPaths.displayName(12, "A/B"))
        assertEquals("Song.m4a", DownloadPaths.displayName(null, "Song"))
        assertEquals("Track.m4a", DownloadPaths.displayName(0, "?"))
    }

    @Test fun `two songs that sanitise to the same name`() {
        val a = DownloadPaths.displayName(1, "Intro?")
        val b = DownloadPaths.displayName(1, "Intro*")
        assertEquals(a, b)
        assertEquals("01 Intro (2).m4a", DownloadPaths.unique(b, listOf(a)))
        assertEquals("01 Intro (3).m4a", DownloadPaths.unique(b, listOf("01 intro.m4a", "01 Intro (2).m4a")))
        assertEquals("01 Intro.m4a", DownloadPaths.unique(a, emptyList()))
    }
}

class DownloadsGateTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun `signed out is never allowed`() =
        assertEquals(DownloadsGate.Decision.SignedOut, DownloadsGate.decide(false, 1000, 2000))

    @Test fun `a positive answer is remembered for 30 days`() {
        val at = 10 * day
        assertEquals(DownloadsGate.Decision.Allowed, DownloadsGate.decide(true, at, at + 29 * day))
        assertEquals(DownloadsGate.Decision.Check, DownloadsGate.decide(true, at, at + 30 * day))
        assertEquals(DownloadsGate.Decision.Check, DownloadsGate.decide(true, null, at))
        // A clock that went backwards does not count as fresh.
        assertEquals(DownloadsGate.Decision.Check, DownloadsGate.decide(true, at, at - day))
        assertEquals(DownloadsGate.Decision.Check, DownloadsGate.decide(true, at, at + day, forceCheck = true))
    }

    @Test fun `a negative answer is not remembered`() {
        assertNull(DownloadsGate.remember(false, 5))
        assertEquals(5L, DownloadsGate.remember(true, 5))
    }
}

class DownloadRangesTest {
    private val mib = 1024L * 1024

    @Test fun `ranges of at most 10 MiB`() {
        assertEquals("bytes=0-3449446", DownloadRanges.range(0, 3_449_447))
        assertEquals("bytes=0-${10 * mib - 1}", DownloadRanges.range(0, 25 * mib))
        assertEquals("bytes=${20 * mib}-${25 * mib - 1}", DownloadRanges.range(20 * mib, 25 * mib))
    }

    @Test fun `resume after re-resolving`() {
        assertEquals(5000L, DownloadRanges.resumeAt(141, 141, 5000))
        // Another format: the bytes so far belong to a different file.
        assertEquals(0L, DownloadRanges.resumeAt(141, 140, 5000))
    }
}

class DownloadRulesTest {
    private fun e(id: String, single: Boolean = false, smart: Boolean = false, vararg c: String, state: DownloadState = DownloadState.Done, track: Int? = null) =
        DownloadEntry(id, "T$id", "A", "Al", "A", track, null, null, 100.0, state, uri = "content://$id", single = single, smart = smart, collections = c.toSet())

    @Test fun `removing an album deletes only what nothing else needs`() {
        val songs = listOf(e("1", c = arrayOf("album")), e("2", c = arrayOf("album", "pl")), e("3", single = true, c = arrayOf("album")), e("4", smart = true, c = arrayOf("album")), e("5", c = arrayOf("pl")))
        val r = DownloadRules.removeCollection(songs, "album")
        assertEquals(listOf("1"), r.delete.map { it.videoId })
        assertEquals(listOf("2", "3", "4"), r.keep.map { it.videoId })
        assertTrue(r.keep.none { "album" in it.collections })
    }

    @Test fun `removing a song alone keeps it for its album`() {
        assertEquals(1, DownloadRules.removeSingle(e("1", single = true)).delete.size)
        val inAlbum = DownloadRules.removeSingle(e("1", single = true, c = arrayOf("album")))
        assertTrue(inAlbum.delete.isEmpty())
        assertFalse(inAlbum.keep.single().single)
    }

    @Test fun `smart removal never touches manual downloads`() {
        val r = DownloadRules.removeSmart(listOf(e("1", smart = true), e("2", smart = true, single = true), e("3", single = true)), setOf("1", "2", "3"))
        assertEquals(listOf("1"), r.delete.map { it.videoId })
        assertEquals(listOf("2"), r.keep.map { it.videoId })
    }

    @Test fun `a playlist never renumbers a file that exists`() {
        val existing = e("1", single = true, track = 5)
        val fromPlaylist = e("1", c = arrayOf("pl"), state = DownloadState.Queued, track = 9)
        val m = DownloadRules.merge(existing, fromPlaylist, fromAlbum = false)
        assertEquals(5, m.trackNumber)
        assertEquals(setOf("pl"), m.collections)
        assertTrue(m.single)
        assertEquals(DownloadState.Done, m.state)
        // Written already: not even an album renumbers it.
        assertEquals(5, DownloadRules.merge(existing, fromPlaylist, fromAlbum = true).trackNumber)
    }

    @Test fun `an album sets the number of a song not written yet`() {
        val queued = e("1", single = true, state = DownloadState.Queued, track = null).copy(album = "Single version")
        val m = DownloadRules.merge(queued, e("1", c = arrayOf("album"), state = DownloadState.Queued, track = 4).copy(album = "Album"), fromAlbum = true)
        assertEquals(4, m.trackNumber)
        assertEquals("Album", m.album)
        // A failed song asked for again is queued again.
        assertEquals(DownloadState.Queued, DownloadRules.merge(e("1", state = DownloadState.Failed), e("1", single = true), false).state)
    }

    @Test fun `year from an album subtitle`() {
        assertEquals("2013", DownloadRules.year("Album • Океан Ельзи • 2013"))
        assertNull(DownloadRules.year("EP • 12 songs"))
    }
}

class DownloadIndexTest {
    private fun e(id: String, state: DownloadState, added: Long) =
        DownloadEntry(id, "T", "A", null, null, null, null, null, 1.0, state, single = true, addedAtMs = added)

    @Test fun `round trip through the store`() {
        val store = MemoryDownloadStore()
        val index = DownloadIndex(store)
        index.put(e("b", DownloadState.Queued, 2))
        index.put(e("a", DownloadState.Queued, 1))
        index.put(e("c", DownloadState.Done, 0).copy(uri = "content://c"))
        index.putCollection(DownloadCollection("OLAK", "Album", true, null, "MPREb", listOf("a", "b")))
        // A new index over the same store sees the same thing.
        val again = DownloadIndex(store)
        assertEquals(listOf("a", "b"), again.queued().map { it.videoId })
        assertEquals("content://c", again.completeUri("c"))
        assertNull(again.completeUri("a"))
        assertEquals(listOf("a", "b"), again.collections.value.getValue("OLAK").videoIds)
        again.delete("c")
        assertNull(DownloadIndex(store).get("c"))
    }

    @Test fun `work cut off by a restart is queued again`() {
        val store = MemoryDownloadStore()
        store.putSong(e("x", DownloadState.Downloading, 0))
        assertEquals(DownloadState.Queued, DownloadIndex(store).get("x")?.state)
    }

    @Test fun `collection status`() {
        val done = e("a", DownloadState.Done, 0)
        assertEquals(DownloadStatus.Done, DownloadStatus.ofCollection(listOf(done, done)))
        assertEquals(DownloadStatus.Downloading(0.5f), DownloadStatus.ofCollection(listOf(done, e("b", DownloadState.Queued, 0))))
        assertEquals(DownloadStatus.Failed, DownloadStatus.ofCollection(listOf(done, e("b", DownloadState.Failed, 0))))
    }
}
