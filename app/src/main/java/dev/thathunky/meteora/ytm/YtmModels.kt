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
