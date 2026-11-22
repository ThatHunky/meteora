package dev.thathunky.meteora.downloads

enum class DownloadState { Queued, Downloading, Done, Failed }

/** One downloaded (or queued) song, as the index keeps it. */
data class DownloadEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** The folder's artist: the album's for an album download, else the song's first artist. */
    val albumArtist: String?,
    val trackNumber: Int?,
    val year: String? = null,
    val artworkUrl: String?,
    val durationSec: Double,
    val state: DownloadState = DownloadState.Queued,
    /** `content://` URI of the file, once written. */
    val uri: String? = null,
    val itag: Int = 0,
    val bytes: Long = 0,
    /** Downloaded by itself (the button on a song). */
    val single: Boolean = false,
    /** Kept by smart downloads. */
    val smart: Boolean = false,
    /** Albums and playlists (their ids) that asked for it. */
    val collections: Set<String> = emptySet(),
    val addedAtMs: Long = 0,
) {
    /** Asked for by the user, alone or with an album or playlist. Smart downloads never touch these. */
    val manual: Boolean get() = single || collections.isNotEmpty()
    val wanted: Boolean get() = manual || smart
}

fun DownloadEntry.toLocalSong() = dev.thathunky.meteora.playback.LocalSong(
    videoId = videoId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    artworkUrl = artworkUrl,
)

/** An album or playlist the user downloaded, with its tracks in order. */
data class DownloadCollection(
    val id: String,
    val title: String,
    val isAlbum: Boolean,
    val thumbnail: String?,
    /** The browse id to open its page with (MPREb_… or VL…). */
    val browseId: String?,
    val videoIds: List<String>,
    val addedAtMs: Long = 0,
)

/** Pure rules over the index, tested on the JVM. */
object DownloadRules {
    /** What changes when one reason to keep songs goes away. */
    data class Release(val delete: List<DownloadEntry>, val keep: List<DownloadEntry>)

    /** Removing collection [id]: its songs lose it; those no other reason keeps are deleted. */
    fun removeCollection(entries: Collection<DownloadEntry>, id: String): Release =
        split(entries.filter { id in it.collections }.map { it.copy(collections = it.collections - id) })

    /** Removing a song from the song's own button: gone, unless it is a collection track (then it stays for that). */
    fun removeSingle(entry: DownloadEntry): Release = split(listOf(entry.copy(single = false, smart = false)))

    /** Smart downloads that dropped out of the wanted list. Manual ones only lose the flag. */
    fun removeSmart(entries: Collection<DownloadEntry>, ids: Set<String>): Release =
        split(entries.filter { it.videoId in ids && it.smart }.map { it.copy(smart = false) })

    private fun split(changed: List<DownloadEntry>) = Release(changed.filterNot { it.wanted }, changed.filter { it.wanted })

    /**
     * The entry for a song being asked for again, merged with what the index has. A file that
     * already exists keeps its number: a playlist never renumbers it. An album sets its own order.
     */
    fun merge(existing: DownloadEntry?, incoming: DownloadEntry, fromAlbum: Boolean): DownloadEntry {
        if (existing == null) return incoming
        val written = existing.state == DownloadState.Done
        // Not written yet and asked for by its album: the album's folder, artist and number win.
        val base = if (fromAlbum && !written) existing.copy(album = incoming.album, albumArtist = incoming.albumArtist, year = incoming.year ?: existing.year) else existing
        return base.copy(
            single = existing.single || incoming.single,
            smart = existing.smart || incoming.smart,
            collections = existing.collections + incoming.collections,
            trackNumber = when {
                written -> existing.trackNumber
                fromAlbum -> incoming.trackNumber
                else -> existing.trackNumber ?: incoming.trackNumber
            },
            state = if (existing.state == DownloadState.Failed) DownloadState.Queued else existing.state,
        )
    }

    /**
     * The album's artist from its page header. YouTube sends either the artist alone on the first
     * line with "Album • 2013" under it, or one line "Album • Artist • 2013". Not from the track
     * rows: on an album page their second column is the play count ("6.4M plays"), not an artist.
     */
    fun albumArtist(subtitle: String?, secondSubtitle: String?): String? {
        val first = subtitle?.trim().orEmpty()
        if (first.isEmpty()) return null
        if (secondSubtitle != null && '•' !in first) return first
        val parts = first.split('•').map { it.trim() }.filter { it.isNotEmpty() && year(it) != it }
        return parts.getOrNull(1)
    }

    /** "2013" from an album page's subtitle such as "Album • Artist • 2013". */
    fun year(text: String?): String? = text?.let { Regex("\\b(19|20)\\d{2}\\b").findAll(it).lastOrNull()?.value }
}
