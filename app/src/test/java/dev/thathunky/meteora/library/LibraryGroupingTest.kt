package dev.thathunky.meteora.library

import dev.thathunky.meteora.downloads.DownloadCollection
import dev.thathunky.meteora.downloads.DownloadEntry
import dev.thathunky.meteora.downloads.DownloadState
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {
    private val uk = LibraryCollation(Locale.forLanguageTag("uk"))
    private val en = LibraryCollation(Locale.ENGLISH)
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 22, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun sortTitles(c: LibraryCollation, vararg t: String) = t.sortedWith(compareBy { c.key(it) })

    // ---- collation ----

    @Test fun `Ukrainian letters sort in the alphabet's order in a Ukrainian app`() {
        assertEquals(listOf("арка", "Гуси", "Ґанок", "Іній", "Їжак", "Яр"), sortTitles(uk, "Яр", "Їжак", "Іній", "Ґанок", "Гуси", "арка"))
    }

    @Test fun `Ukrainian titles sort in their alphabet in an English app too`() {
        // An English collator compares Cyrillic by code point: і (U+0456) after я (U+044F).
        assertEquals(listOf("Іній", "Яр"), sortTitles(en, "Яр", "Іній"))
    }

    @Test fun `scripts never interleave and the app language's script comes first`() {
        val mixed = arrayOf("Яр", "Abba", "Іній", "Zoo", "1999", "арка")
        assertEquals(listOf("Abba", "Zoo", "арка", "Іній", "Яр", "1999"), sortTitles(en, *mixed))
        assertEquals(listOf("арка", "Іній", "Яр", "Abba", "Zoo", "1999"), sortTitles(uk, *mixed))
    }

    @Test fun `a leading The is ignored, other articles are not`() {
        assertEquals("Beatles", en.sortText("The Beatles"))
        assertEquals("B", en.indexLetter(en.sortText("The Beatles")))
        assertEquals("A-ha", en.sortText("A-ha"))
        assertEquals("A Day to Remember", en.sortText("A Day to Remember"))
        assertEquals("The", en.sortText("The"))
        assertEquals("Theory", en.sortText("Theory"))
        assertEquals(listOf("Abba", "The Beatles", "Coldplay"), sortTitles(en, "Coldplay", "The Beatles", "Abba"))
    }

    @Test fun `index letters fold Latin accents but keep Cyrillic letters`() {
        assertEquals("E", en.indexLetter("Édith"))
        assertEquals("Ї", uk.indexLetter("їжак"))
        assertEquals("Й", uk.indexLetter("йолка"))
        assertEquals("Ґ", uk.indexLetter("ґанок"))
        assertEquals("Є", uk.indexLetter("Єва"))
        assertEquals("T", en.indexLetter("'Til Tuesday"))
        assertEquals("#", en.indexLetter("2Pac"))
        assertEquals("#", en.indexLetter("..."))
        assertEquals("#", en.indexLetter("東京"))
    }

    @Test fun `case does not split a letter`() {
        assertEquals(en.indexLetter("abba"), en.indexLetter("ABBA"))
        assertEquals(uk.indexLetter("іній"), uk.indexLetter("Іній"))
    }

    @Test fun `various artists are recognised in both languages`() {
        assertTrue(LibraryCollation.isVarious("Various Artists"))
        assertTrue(LibraryCollation.isVarious("Різні виконавці"))
        assertTrue(LibraryCollation.isVarious(" "))
        assertTrue(LibraryCollation.isVarious(null))
        assertFalse(LibraryCollation.isVarious("Okean Elzy"))
    }

    @Test fun `search folds case and accents`() {
        assertTrue(LibraryCollation.fold("Beyoncé", Locale.ENGLISH).contains(LibraryCollation.fold("BEYONCE", Locale.ENGLISH)))
    }

    // ---- choices ----

    @Test fun `stored choices parse back and unknown ones fall back`() {
        assertEquals(DownloadGroup.Artists, LibraryChoices.parseGroup("Artists"))
        assertEquals(DownloadGroup.Songs, LibraryChoices.parseGroup("Folders"))
        assertEquals(DownloadFilter.All, LibraryChoices.parseFilter(null))
        assertEquals(LibrarySort.Count, LibraryChoices.parseSort("Count", LibraryChoices.sortsFor(DownloadGroup.Artists)))
        // Count is not a sort of the Songs group.
        assertEquals(LibrarySort.Title, LibraryChoices.parseSort("Count", LibraryChoices.sortsFor(DownloadGroup.Songs)))
        assertEquals(LibrarySort.Default, LibraryChoices.parseSort(null, LibraryChoices.sortsFor(LibraryScreen.Liked)))
    }

    // ---- dates ----

    private fun ago(days: Long, hours: Long = 0) = now - days * 86_400_000 - hours * 3_600_000

    @Test fun `recently added buckets follow the calendar`() {
        assertEquals(Label.Today, dateBucket(ago(0, 14), now, zone))
        assertEquals(Label.Yesterday, dateBucket(ago(1), now, zone))
        assertEquals(Label.ThisWeek, dateBucket(ago(6), now, zone))
        assertEquals(Label.ThisMonth, dateBucket(ago(7), now, zone))
        assertEquals(Label.ThisMonth, dateBucket(ago(29), now, zone))
        assertEquals(Label.Month(2026, 10), dateBucket(ago(30), now, zone))
        assertEquals(Label.Month(2025, 12), dateBucket(ago(340), now, zone))
        assertEquals(Label.Earlier, dateBucket(0, now, zone))
    }

    // ---- downloads ----

    private fun song(
        id: String,
        title: String,
        artist: String = "A",
        album: String? = null,
        albumArtist: String? = null,
        track: Int? = null,
        bytes: Long = 1,
        added: Long = 0,
        smart: Boolean = false,
        single: Boolean = !smart,
        collections: Set<String> = emptySet(),
        state: DownloadState = DownloadState.Done,
    ) = DownloadEntry(id, title, artist, album, albumArtist, track, null, null, 1.0, state = state, bytes = bytes, single = single, smart = smart, collections = collections, addedAtMs = added)

    private fun build(songs: List<DownloadEntry>, q: DownloadsQuery, collections: List<DownloadCollection> = emptyList(), c: LibraryCollation = en) =
        DownloadsLibrary.build(songs, collections, q, c, now, zone)

    private fun DownloadsView.describe(): List<String> = rows.map {
        when (it) {
            is DownloadRow.Header -> "[" + ((it.label as? Label.Letter)?.letter ?: it.label.id) + "]"
            is DownloadRow.Song -> (if (it.inGroup) "  " else "") + it.e.title
            is DownloadRow.Group -> "<${it.title}:${it.songIds.size}>"
            is DownloadRow.Collection -> "{${it.c.title}}"
        }
    }

    @Test fun `songs by title get a header per letter and an index that points at them`() {
        val v = build(listOf(song("1", "Bb"), song("2", "ab"), song("3", "Ba"), song("4", "Яр"), song("5", "9 Crimes")), DownloadsQuery())
        assertEquals(listOf("[A]", "ab", "[B]", "Ba", "Bb", "[Я]", "Яр", "[#]", "9 Crimes"), v.describe())
        assertEquals(listOf("A", "B", "Я", "#"), v.index.letters)
        v.index.positions.forEachIndexed { i, p -> assertEquals(Label.Letter(v.index.letters[i]), (v.rows[p] as DownloadRow.Header).label) }
        assertEquals(1, v.index.letterAt(v.index.positions[1]))
        assertEquals(1, v.index.letterAt(v.index.positions[1] + 2))
    }

    @Test fun `equal titles keep a fixed order`() {
        val a = build(listOf(song("b", "Same"), song("a", "same")), DownloadsQuery())
        val b = build(listOf(song("a", "same"), song("b", "Same")), DownloadsQuery())
        assertEquals(a.shownIds, b.shownIds)
    }

    @Test fun `artists group by the folder artist and various artists go last`() {
        val songs = listOf(
            song("1", "Two", artist = "Queen, David Bowie", albumArtist = "Queen", album = "Hot Space", track = 2, bytes = 10),
            song("2", "One", artist = "Queen", albumArtist = "Queen", album = "Hot Space", track = 1, bytes = 20),
            song("3", "Solo", artist = "ABBA", bytes = 5),
            song("4", "Mix", artist = "Various Artists"),
            song("5", "Lone", artist = "abba"),
        )
        val closed = build(songs, DownloadsQuery(group = DownloadGroup.Artists))
        assertEquals(listOf("[A]", "<ABBA:2>", "[Q]", "<Queen:2>", "[various]", "Mix"), closed.describe())
        // Every song plays, closed groups included, in the order shown; inside a group by track.
        assertEquals(listOf("5", "3", "2", "1", "4"), closed.playOrder)
        val open = build(songs, DownloadsQuery(group = DownloadGroup.Artists, expanded = setOf("a:queen")))
        assertEquals(listOf("[A]", "<ABBA:2>", "[Q]", "<Queen:2>", "  One", "  Two", "[various]", "Mix"), open.describe())
        val bySize = build(songs, DownloadsQuery(group = DownloadGroup.Artists, sort = LibrarySort.Size))
        assertEquals(listOf("<Queen:2>", "<ABBA:2>", "[various]", "Mix"), bySize.describe())
    }

    @Test fun `albums group by album and artist, songs without an album go last`() {
        val songs = listOf(
            song("1", "B-side", album = "Hits", albumArtist = "X", track = 2),
            song("2", "A-side", album = "Hits", albumArtist = "X", track = 1),
            song("3", "Other", album = "Hits", albumArtist = "Y"),
            song("4", "Loose"),
        )
        val v = build(songs, DownloadsQuery(group = DownloadGroup.Albums, sort = LibrarySort.Title, expanded = setOf("b:hits|x")))
        assertEquals(listOf("[H]", "<Hits:2>", "  A-side", "  B-side", "<Hits:1>", "[noalbum]", "Loose"), v.describe())
        val g = v.rows.filterIsInstance<DownloadRow.Group>()
        assertEquals(listOf("X", "Y"), g.map { it.subtitle })
    }

    @Test fun `recently added is newest first under date headers`() {
        val v = build(listOf(song("1", "Old", added = ago(40)), song("2", "New", added = ago(0)), song("3", "Unknown")), DownloadsQuery(group = DownloadGroup.Recent))
        assertEquals(listOf("[today]", "New", "[m:2026-10]", "Old", "[earlier]", "Unknown"), v.describe())
        assertTrue(v.index.isEmpty)
    }

    @Test fun `collections come first and fold away`() {
        val c = DownloadCollection("P", "Road trip", false, null, null, listOf("1"))
        val songs = listOf(song("1", "Song", collections = setOf("P"), single = false))
        assertEquals(listOf("[collections]", "{Road trip}", "[S]", "Song"), build(songs, DownloadsQuery(), listOf(c)).describe())
        assertEquals(listOf("[collections]", "[S]", "Song"), build(songs, DownloadsQuery(collectionsOpen = false), listOf(c)).describe())
    }

    @Test fun `the smart filter is offered only when there are smart downloads`() {
        val songs = listOf(song("1", "Mine"), song("2", "Auto", smart = true), song("3", "Both", smart = true, single = true))
        val all = build(songs, DownloadsQuery())
        assertTrue(all.hasSmart)
        assertEquals(listOf("Auto"), build(songs, DownloadsQuery(filter = DownloadFilter.Smart)).rows.filterIsInstance<DownloadRow.Song>().map { it.e.title })
        assertEquals(listOf("Both", "Mine"), build(songs, DownloadsQuery(filter = DownloadFilter.Mine)).rows.filterIsInstance<DownloadRow.Song>().map { it.e.title })
        val noSmart = build(listOf(song("1", "Mine", single = false, collections = setOf("x"))), DownloadsQuery(filter = DownloadFilter.Smart))
        assertFalse(noSmart.hasSmart)
        assertEquals(1, noSmart.shownIds.size)
    }

    @Test fun `search matches title, artist and album and opens groups`() {
        val songs = listOf(song("1", "Halo", artist = "Beyoncé"), song("2", "Other", artist = "Z"))
        val v = build(songs, DownloadsQuery(group = DownloadGroup.Artists, query = "beyonce"))
        assertEquals(listOf("[B]", "<Beyoncé:1>", "  Halo"), v.describe())
    }

    @Test fun `counts and sizes add up and only complete songs play`() {
        val v = build(listOf(song("1", "A", bytes = 100), song("2", "B", bytes = 50, state = DownloadState.Queued)), DownloadsQuery())
        assertEquals(2, v.totalSongs)
        assertEquals(100, v.totalBytes)
        assertEquals(listOf("1"), v.playOrder)
        assertEquals(listOf("1", "2"), v.shownIds)
    }

    @Test fun `bulk remove skips songs kept for their album or playlist`() {
        val songs = listOf(song("1", "A"), song("2", "B", collections = setOf("x")), song("3", "C")).associateBy { it.videoId }
        assertEquals(listOf("1", "3") to 1, DownloadsLibrary.removal(songs, listOf("1", "2", "3", "gone")))
    }

    @Test fun `five hundred songs build quickly`() {
        val songs = (0 until 500).map { song("$it", "Song ${it * 7919 % 500}", artist = "Artist ${it % 60}", album = "Album ${it % 90}", albumArtist = "Artist ${it % 60}") }
        val start = System.nanoTime()
        DownloadGroup.entries.forEach { build(songs, DownloadsQuery(group = it, expanded = setOf("a:artist 1"))) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    // ---- plain lists ----

    private data class T(val id: String, val title: String, val artist: String?, val album: String?)

    private fun sectioned(items: List<T>, sort: LibrarySort, c: LibraryCollation = en) =
        SectionedList.build(items, sort, c, id = { it.id }, title = { it.title }, artist = { it.artist }, album = { it.album })

    @Test fun `liked songs keep YouTube's order by default, with keys that survive a sort`() {
        val items = listOf(T("v", "Zed", "B", null), T("w", "Alpha", "A", "X"), T("v", "Zed", "B", null))
        val d = sectioned(items, LibrarySort.Default)
        assertEquals(listOf("v", "w", "v#2"), d.rows.map { it.key })
        val t = sectioned(items, LibrarySort.Title)
        assertEquals(listOf("h:l:A", "w", "h:l:Z", "v", "v#2"), t.rows.map { it.key })
        assertEquals(listOf("A", "Z"), t.index.letters)
    }

    @Test fun `liked by artist and album put the unknowns last`() {
        val items = listOf(T("1", "S1", "", "X"), T("2", "S2", "Abba", null), T("3", "S3", "Ярема", "Y"))
        assertEquals(listOf("h:l:A", "2", "h:l:Я", "3", "h:various", "1"), sectioned(items, LibrarySort.Artist).rows.map { it.key })
        assertEquals(listOf("h:l:X", "1", "h:l:Y", "3", "h:noalbum", "2"), sectioned(items, LibrarySort.Album).rows.map { it.key })
    }

    @Test fun `the scroller shows only for long lists with enough letters`() {
        val idx = LetterIndex(listOf("A", "B", "C", "D", "E"), listOf(0, 10, 20, 30, 40))
        assertTrue(LetterIndex.worthShowing(idx, 45))
        assertFalse(LetterIndex.worthShowing(idx, 39))
        assertFalse(LetterIndex.worthShowing(LetterIndex(listOf("A"), listOf(0)), 400))
    }
}
