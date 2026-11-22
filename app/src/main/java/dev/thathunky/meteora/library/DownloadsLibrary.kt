package dev.thathunky.meteora.library

import dev.thathunky.meteora.downloads.DownloadCollection
import dev.thathunky.meteora.downloads.DownloadEntry
import dev.thathunky.meteora.downloads.DownloadState
import java.time.ZoneId

/** What the Downloads list asks for: the remembered choices plus this visit's search and open groups. */
data class DownloadsQuery(
    val group: DownloadGroup = DownloadGroup.Songs,
    val sort: LibrarySort = LibrarySort.Title,
    val filter: DownloadFilter = DownloadFilter.All,
    val query: String = "",
    /** Artist and album groups the user opened ([DownloadRow.Group.id]). Searching opens them all. */
    val expanded: Set<String> = emptySet(),
    val collectionsOpen: Boolean = true,
)

sealed interface DownloadRow {
    val key: String

    data class Header(val label: Label, val count: Int? = null, val collapsible: Boolean = false, val expanded: Boolean = true) : DownloadRow {
        override val key get() = "h:" + label.id
    }
    data class Collection(val c: DownloadCollection) : DownloadRow {
        override val key get() = "c:" + c.id
    }
    data class Song(val e: DownloadEntry, val inGroup: Boolean = false) : DownloadRow {
        override val key get() = "s:" + e.videoId
    }
    /** An artist or an album, collapsed or open. [subtitle]: the album's artist. */
    data class Group(
        val id: String,
        val title: String,
        val subtitle: String?,
        val artwork: String?,
        val songIds: List<String>,
        val bytes: Long,
        val expanded: Boolean,
    ) : DownloadRow {
        override val key get() = "g:$id"
    }
}

data class DownloadsView(
    val rows: List<DownloadRow>,
    val index: LetterIndex,
    /** Complete songs in the order shown, closed groups included: what a tap plays. */
    val playOrder: List<String>,
    /** Every song the filter and search let through: what Select all selects. */
    val shownIds: List<String>,
    val totalSongs: Int,
    val totalBytes: Long,
    /** Some songs are kept by smart downloads only, so the filter is worth offering. */
    val hasSmart: Boolean,
)

/** A smart download nobody asked for by hand. */
val DownloadEntry.smartOnly: Boolean get() = smart && !manual

/** The artist a song is filed under: the folder's (album) artist, else the song's own. */
fun DownloadEntry.folderArtist(): String = albumArtist?.takeIf { it.isNotBlank() } ?: artist

/** The pure Downloads list: filtering, search, grouping, sorting and the letter index. */
object DownloadsLibrary {
    fun build(
        songs: Collection<DownloadEntry>,
        collections: Collection<DownloadCollection>,
        q: DownloadsQuery,
        collation: LibraryCollation,
        nowMs: Long,
        zone: ZoneId,
    ): DownloadsView {
        val hasSmart = songs.any { it.smartOnly }
        val filter = if (hasSmart) q.filter else DownloadFilter.All
        val needle = LibraryCollation.fold(q.query.trim(), collation.locale)
        val searching = needle.isNotEmpty()
        fun hit(vararg texts: String?) = !searching || texts.any { it != null && LibraryCollation.fold(it, collation.locale).contains(needle) }
        val shown = songs.filter {
            when (filter) {
                DownloadFilter.All -> true
                DownloadFilter.Mine -> it.manual
                DownloadFilter.Smart -> it.smartOnly
            } && hit(it.title, it.artist, it.album, it.albumArtist)
        }
        val rows = ArrayList<DownloadRow>(shown.size + 60)
        val headers = ArrayList<Pair<Label, Int>>()
        val order = ArrayList<DownloadEntry>(shown.size)
        fun header(label: Label) {
            headers += label to rows.size
            rows += DownloadRow.Header(label)
        }

        if (filter != DownloadFilter.Smart) {
            val cols = collections.filter { hit(it.title) }.sortedByDescending { it.addedAtMs }
            if (cols.isNotEmpty()) {
                val open = q.collectionsOpen || searching
                rows += DownloadRow.Header(Label.Collections, cols.size, collapsible = true, expanded = open)
                if (open) cols.forEach { rows += DownloadRow.Collection(it) }
            }
        }

        val titleKeys = HashMap<String, LibraryCollation.Key>(shown.size * 2)
        fun titleKey(e: DownloadEntry) = titleKeys.getOrPut(e.videoId) { collation.key(e.title) }
        val byTitle = compareBy<DownloadEntry> { titleKey(it) }.thenBy { it.videoId }

        when (q.group) {
            DownloadGroup.Songs -> when (q.sort) {
                LibrarySort.Size -> shown.sortedWith(compareByDescending<DownloadEntry> { it.bytes }.then(byTitle)).forEach {
                    rows += DownloadRow.Song(it); order += it
                }
                LibrarySort.Artist -> {
                    val (various, named) = shown.partition { LibraryCollation.isVarious(it.artist) }
                    val artistKeys = named.associate { it.videoId to collation.key(it.artist) }
                    var last: Label? = null
                    named.sortedWith(compareBy<DownloadEntry> { artistKeys.getValue(it.videoId) }.then(byTitle)).forEach {
                        val l = Label.Letter(artistKeys.getValue(it.videoId).letter)
                        if (l != last) { header(l); last = l }
                        rows += DownloadRow.Song(it); order += it
                    }
                    if (various.isNotEmpty()) header(Label.Various)
                    various.sortedWith(byTitle).forEach { rows += DownloadRow.Song(it); order += it }
                }
                else -> {
                    var last: Label? = null
                    shown.sortedWith(byTitle).forEach {
                        val l = Label.Letter(titleKey(it).letter)
                        if (l != last) { header(l); last = l }
                        rows += DownloadRow.Song(it); order += it
                    }
                }
            }
            DownloadGroup.Recent -> {
                var last: Label? = null
                shown.sortedWith(compareByDescending<DownloadEntry> { it.addedAtMs }.then(byTitle)).forEach {
                    val l = dateBucket(it.addedAtMs, nowMs, zone)
                    if (l != last) { header(l); last = l }
                    rows += DownloadRow.Song(it); order += it
                }
            }
            DownloadGroup.Artists, DownloadGroup.Albums -> {
                val albums = q.group == DownloadGroup.Albums
                val tailLabel = if (albums) Label.NoAlbum else Label.Various
                val (tail, filed) = shown.partition { if (albums) it.album.isNullOrBlank() else LibraryCollation.isVarious(it.folderArtist()) }
                val inGroup = compareBy<DownloadEntry, LibraryCollation.Key?>(nullsLast()) { e -> e.album?.takeIf { it.isNotBlank() }?.let { collation.key(it) } }
                    .thenBy(nullsLast()) { it.trackNumber }
                    .then(byTitle)
                class G(val id: String, val title: String, val artist: String?, val members: List<DownloadEntry>) {
                    val nameKey = collation.key(title)
                    val artistKey = artist?.let { collation.key(it) }
                    val bytes = members.sumOf { it.bytes }
                }
                val groups = filed.groupBy {
                    val artist = LibraryCollation.fold(it.folderArtist(), collation.locale)
                    if (albums) "b:" + LibraryCollation.fold(it.album!!, collation.locale) + "|" + artist else "a:$artist"
                }.map { (id, list) ->
                    val sorted = list.sortedWith(inGroup)
                    // The most common spelling names the group; on a tie, the one with more capitals ("ABBA" over "abba").
                    val name = sorted.groupingBy { if (albums) it.album!! else it.folderArtist() }.eachCount().entries
                        .maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { e -> e.key.count { it.isUpperCase() } }.thenByDescending { it.key }).key
                    G(id, name, if (albums) sorted.first().folderArtist() else null, sorted)
                }
                val sortedGroups = when (q.sort) {
                    LibrarySort.Count -> groups.sortedWith(compareByDescending<G> { it.members.size }.thenBy { it.nameKey }.thenBy { it.id })
                    LibrarySort.Size -> groups.sortedWith(compareByDescending<G> { it.bytes }.thenBy { it.nameKey }.thenBy { it.id })
                    LibrarySort.Artist -> groups.sortedWith(compareBy<G, LibraryCollation.Key?>(nullsLast()) { it.artistKey }.thenBy { it.nameKey }.thenBy { it.id })
                    else -> groups.sortedWith(compareBy<G> { it.nameKey }.thenBy { it.id })
                }
                val lettered = q.sort == LibrarySort.Title || q.sort == LibrarySort.Artist
                var last: Label? = null
                sortedGroups.forEach { g ->
                    if (lettered) {
                        val l = Label.Letter(((if (q.sort == LibrarySort.Artist) g.artistKey else null) ?: g.nameKey).letter)
                        if (l != last) { header(l); last = l }
                    }
                    val open = searching || g.id in q.expanded
                    rows += DownloadRow.Group(
                        id = g.id,
                        title = g.title,
                        subtitle = g.artist,
                        artwork = g.members.firstNotNullOfOrNull { it.artworkUrl },
                        songIds = g.members.map { it.videoId },
                        bytes = g.bytes,
                        expanded = open,
                    )
                    if (open) g.members.forEach { rows += DownloadRow.Song(it, inGroup = true) }
                    order += g.members
                }
                if (tail.isNotEmpty()) header(tailLabel)
                tail.sortedWith(byTitle).forEach { rows += DownloadRow.Song(it); order += it }
            }
        }
        return DownloadsView(
            rows = rows,
            index = LetterIndex.of(headers),
            playOrder = order.filter { it.state == DownloadState.Done }.map { it.videoId },
            shownIds = order.map { it.videoId },
            totalSongs = songs.size,
            totalBytes = songs.filter { it.state == DownloadState.Done }.sumOf { it.bytes },
            hasSmart = hasSmart,
        )
    }

    /** Which of [ids] a bulk Remove deletes, and how many stay for their album or playlist. */
    fun removal(songs: Map<String, DownloadEntry>, ids: Collection<String>): Pair<List<String>, Int> {
        val present = ids.mapNotNull { songs[it] }
        val (removable, kept) = present.partition { it.collections.isEmpty() }
        return removable.map { it.videoId } to kept.size
    }
}
