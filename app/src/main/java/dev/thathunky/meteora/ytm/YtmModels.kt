package dev.thathunky.meteora.ytm

/** Browse/search results from YouTube Music, normalised. Every field the API may omit is nullable. */
sealed interface MusicItem {
    val title: String
    val thumbnail: String?

    data class Song(
        val videoId: String,
        override val title: String,
        val artists: List<ArtistRef>,
        val album: AlbumRef?,
        val duration: String?,
        override val thumbnail: String?,
        val isVideo: Boolean,
    ) : MusicItem

    data class Album(
        val browseId: String,
        override val title: String,
        val subtitle: String,
        override val thumbnail: String?,
        /** OLAK5uy_… when the API includes it (play buttons); else resolved from the album page. */
        val playlistId: String?,
    ) : MusicItem

    data class Artist(
        val browseId: String,
        override val title: String,
        val subtitle: String,
        override val thumbnail: String?,
    ) : MusicItem

    data class Playlist(
        /** Browse id, usually "VL" + playlistId. */
        val browseId: String,
        override val title: String,
        val subtitle: String,
        override val thumbnail: String?,
        val playlistId: String,
    ) : MusicItem
}

data class ArtistRef(val name: String, val browseId: String?)
data class AlbumRef(val name: String, val browseId: String?)

data class Shelf(val title: String, val items: List<MusicItem>, val moreBrowseId: String? = null)

data class SearchResults(val top: MusicItem?, val items: List<MusicItem>)

data class CollectionPage(
    val title: String,
    val subtitle: String,
    val secondSubtitle: String?,
    val thumbnail: String?,
    /** What to pass to changeVideo to play the whole thing (OLAK… for albums). */
    val playlistId: String?,
    val tracks: List<MusicItem.Song>,
)

data class ArtistPage(
    val name: String,
    val thumbnail: String?,
    val description: String?,
    val shelves: List<Shelf>,
    /** Artist radio / shuffle playlist ids if offered. */
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
)

/** A watch queue from the `next` endpoint; [startIndex] is the item the player starts on. */
/** [likes]: video id → like status, for the tracks the response says it for (signed in only). */
data class WatchQueue(
    val songs: List<MusicItem.Song>,
    val startIndex: Int,
    val continuation: String?,
    val likes: Map<String, dev.thathunky.meteora.domain.LikeStatus> = emptyMap(),
    /** What YouTube plays after an album or playlist ends (its "autoplay"), if the response says. */
    val automix: AutomixRef? = null,
)

/** A `watchPlaylistEndpoint` to fetch once a collection runs out, e.g. `RDAMPL<playlistId>`. */
data class AutomixRef(val playlistId: String, val params: String?)

/** The radio for a playlist or album id. Already a radio (`RDAMVM…`, `RDAMPL…`): itself. Verified for OLAK5uy_, PL and RDCLAK ids. */
fun collectionRadioId(playlistId: String): String {
    val id = playlistId.removePrefix("VL")
    return if (id.startsWith("RDAM")) id else "RDAMPL$id"
}
