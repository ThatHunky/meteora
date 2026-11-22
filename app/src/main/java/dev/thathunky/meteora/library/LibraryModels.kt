package dev.thathunky.meteora.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a section header says. The UI turns these into strings; pure code never holds text. */
sealed interface Label {
    /** Stable, for the header's list key. */
    val id: String

    data class Letter(val letter: String) : Label { override val id get() = "l:$letter" }
    data object Today : Label { override val id get() = "today" }
    data object Yesterday : Label { override val id get() = "yesterday" }
    data object ThisWeek : Label { override val id get() = "week" }
    data object ThisMonth : Label { override val id get() = "month" }
    data class Month(val year: Int, val month: Int) : Label { override val id get() = "m:$year-$month" }
    data object Earlier : Label { override val id get() = "earlier" }
    data object NoAlbum : Label { override val id get() = "noalbum" }
    data object Various : Label { override val id get() = "various" }
    data object Collections : Label { override val id get() = "collections" }
}

/** How the Downloads list is grouped. */
enum class DownloadGroup { Songs, Artists, Albums, Recent }

/** Every sort any library list offers; each list allows some of them ([sortsFor]). */
enum class LibrarySort { Default, Title, Artist, Album, Count, Size }

/** Which downloads the list shows. Smart = kept by smart downloads only. */
enum class DownloadFilter { All, Mine, Smart }

/** The lists whose choices are remembered. */
enum class LibraryScreen { Downloads, Liked, Playlists }

object LibraryChoices {
    fun sortsFor(group: DownloadGroup): List<LibrarySort> = when (group) {
        DownloadGroup.Songs -> listOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Size)
        DownloadGroup.Artists -> listOf(LibrarySort.Title, LibrarySort.Count, LibrarySort.Size)
        DownloadGroup.Albums -> listOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Size)
        DownloadGroup.Recent -> emptyList()
    }

    fun sortsFor(screen: LibraryScreen): List<LibrarySort> = when (screen) {
        LibraryScreen.Downloads -> sortsFor(DownloadGroup.Songs)
        LibraryScreen.Liked -> listOf(LibrarySort.Default, LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album)
        LibraryScreen.Playlists -> listOf(LibrarySort.Default, LibrarySort.Title)
    }

    // Stored values from an older or newer build fall back to the first choice.
    fun parseGroup(stored: String?): DownloadGroup = DownloadGroup.entries.firstOrNull { it.name == stored } ?: DownloadGroup.Songs
    fun parseFilter(stored: String?): DownloadFilter = DownloadFilter.entries.firstOrNull { it.name == stored } ?: DownloadFilter.All
    fun parseSort(stored: String?, allowed: List<LibrarySort>): LibrarySort =
        allowed.firstOrNull { it.name == stored } ?: allowed.firstOrNull() ?: LibrarySort.Default
}

/** Which "recently added" bucket a time falls in, by the calendar in [zone]. Unknown (0) is Earlier. */
fun dateBucket(addedAtMs: Long, nowMs: Long, zone: ZoneId): Label {
    if (addedAtMs <= 0) return Label.Earlier
    val day = Instant.ofEpochMilli(addedAtMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> Label.Today
        day == today.minusDays(1) -> Label.Yesterday
        day.isAfter(today.minusDays(7)) -> Label.ThisWeek
        day.isAfter(today.minusDays(30)) -> Label.ThisMonth
        else -> Label.Month(day.year, day.monthValue)
    }
}

/** Used by tests and the UI to name a month bucket. */
fun Label.Month.date(): LocalDate = LocalDate.of(year, month, 1)

/**
 * Where each index letter's header is in a flat row list. [rows] is only the part of the list
 * that is grouped; the caller adds the position of the first row.
 */
data class LetterIndex(val letters: List<String>, val positions: List<Int>) {
    val isEmpty get() = letters.isEmpty()

    /** The letter whose section holds row [row] (the last header at or before it). */
    fun letterAt(row: Int): Int {
        var found = 0
        for (i in positions.indices) if (positions[i] <= row) found = i else break
        return found
    }

    companion object {
        val Empty = LetterIndex(emptyList(), emptyList())

        fun of(labels: List<Pair<Label, Int>>): LetterIndex {
            val l = labels.filter { it.first is Label.Letter }
            return LetterIndex(l.map { (it.first as Label.Letter).letter }, l.map { it.second })
        }

        /** The scroller is worth showing: a long list with enough letters. */
        fun worthShowing(index: LetterIndex, rows: Int): Boolean = rows >= 40 && index.letters.size >= 5
    }
}
