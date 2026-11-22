package dev.thathunky.meteora.data

import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.downloads.toLocalSong
import dev.thathunky.meteora.playback.toLocal
import dev.thathunky.meteora.ytm.ArtistPage
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient
import dev.thathunky.meteora.ytm.collectionRadioId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a browsed [MusicItem] into playback on the current target: PC commands, or the phone's
 * own queue. Resolves album playlist ids on demand.
 */
@UnstableApi
@Singleton
class MusicActions @Inject constructor(
    private val router: PlaybackRouter,
    private val ytm: YtmClient,
    @AppScope private val scope: CoroutineScope,
    private val downloads: dev.thathunky.meteora.downloads.DownloadManager,
) {
    private val pc get() = router.pc

    /**
     * On the phone with no network, a downloaded song or collection plays from the index (YouTube's
     * queue needs the network). True if it did.
     */
    private fun playOffline(playlistId: String?, videoId: String?): Boolean {
        if (!router.isPhone || !downloads.offline()) return false
        val (songs, start) = downloads.localQueue(playlistId, videoId) ?: return false
        router.local.setQueue(songs, start, source = playlistId)
        return true
    }

    /** The Downloads tab: always from the files, online or not. */
    fun playDownloaded(playlistId: String?, videoId: String?) {
        val (songs, start) = downloads.localQueue(playlistId, videoId) ?: return
        if (router.isPhone) router.local.setQueue(songs, start, source = playlistId)
        else if (playlistId != null) router.play(PlayIntent.Collection(playlistId, videoId)) else videoId?.let { router.play(PlayIntent.SongRadio(it)) }
    }

    /**
     * The Downloads list: its complete songs in the order shown, from [startVideoId]. On the PC,
     * which cannot play the phone's files, the song and its radio.
     */
    fun playDownloadedList(videoIds: List<String>, startVideoId: String) {
        val songs = downloads.index.songs.value
        val done = videoIds.mapNotNull { id -> songs[id]?.takeIf { it.state == dev.thathunky.meteora.downloads.DownloadState.Done } }
        if (done.isEmpty()) return
        if (router.isPhone) {
            router.local.setQueue(done.map { it.toLocalSong() }, done.indexOfFirst { it.videoId == startVideoId }.coerceAtLeast(0))
        } else {
            router.play(PlayIntent.SongRadio(startVideoId))
        }
    }

    /** The long-press sheet's Download. */
    fun download(item: MusicItem) {
        downloads.downloadItem(item)
    }

    /** The long-press sheet's Remove download. */
    fun removeDownload(item: MusicItem) {
        when (item) {
            is MusicItem.Song -> downloads.removeSong(item.videoId)
            is MusicItem.Album -> downloads.index.collectionFor(item.playlistId, item.browseId)?.let { downloads.removeCollection(it.id) }
            is MusicItem.Playlist -> downloads.index.collectionFor(item.playlistId, item.browseId)?.let { downloads.removeCollection(it.id) }
            is MusicItem.Artist -> Unit
        }
    }

    /** Song tap = song + radio; collection = play from the top (optionally from a track). */
    fun play(item: MusicItem, startVideoId: String? = null) {
        val offlineKey = when (item) {
            is MusicItem.Song -> null to item.videoId
            is MusicItem.Playlist -> item.playlistId to startVideoId
            is MusicItem.Album -> item.playlistId to startVideoId
            is MusicItem.Artist -> null to null
        }
        if (offlineKey.second != null || offlineKey.first != null) {
            if (playOffline(offlineKey.first, offlineKey.second)) return
        }
        when (item) {
            is MusicItem.Song -> router.play(PlayIntent.SongRadio(item.videoId), first = item.toLocal())
            is MusicItem.Playlist -> router.play(PlayIntent.Collection(item.playlistId, startVideoId))
            is MusicItem.Album -> withAlbumPlaylist(item) { router.play(PlayIntent.Collection(it, startVideoId)) }
            is MusicItem.Artist -> withArtistPage(item) { page ->
                (page.shufflePlaylistId ?: page.radioPlaylistId)?.let { router.play(PlayIntent.Collection(it)) }
            }
        }
    }

    fun playCollectionTrack(playlistId: String, videoId: String) {
        if (playOffline(playlistId, videoId)) return
        router.play(PlayIntent.Collection(playlistId, videoId))
    }

    fun shuffleCollection(playlistId: String, videoIds: List<String>) {
        val start = videoIds.randomOrNull() ?: return
        if (router.isPhone) return router.play(PlayIntent.Collection(playlistId, start), shuffle = true)
        shuffleOnPc(playlistId, start)
    }

    /** Shuffle for an album, playlist or artist opened from a list, with no track list in hand. */
    fun shuffle(item: MusicItem) {
        when (item) {
            is MusicItem.Song -> Unit
            is MusicItem.Playlist -> shuffleWhole(item.playlistId)
            is MusicItem.Album -> withAlbumPlaylist(item, ::shuffleWhole)
            // The artist's shuffle playlist is already in random order.
            is MusicItem.Artist -> withArtistPage(item) { page -> page.shufflePlaylistId?.let { router.play(PlayIntent.Collection(it)) } }
        }
    }

    private fun shuffleWhole(playlistId: String) {
        if (router.isPhone) router.play(PlayIntent.Collection(playlistId), shuffle = true) else shuffleOnPc(playlistId, null)
    }

    /** changeVideo has no shuffle flag: start it, then turn shuffle on once it has loaded. */
    private fun shuffleOnPc(playlistId: String, startVideoId: String?) {
        pc.play(PlayIntent.Collection(playlistId, startVideoId))
        scope.launch {
            withTimeoutOrNull(8_000) {
                pc.state.first { it?.playlistId == playlistId && it.queue.isNotEmpty() && !it.isGenerating }
            }?.let { if (!it.shuffle) pc.send(Command.ToggleShuffle) }
        }
    }

    fun playNext(item: MusicItem) = enqueue(item, next = true)
    fun addToQueue(item: MusicItem) = enqueue(item, next = false)

    /** Start radio: a song's radio, a collection's (`RDAMPL…`), or the artist's own. */
    fun radio(item: MusicItem) {
        when (item) {
            is MusicItem.Song -> router.play(PlayIntent.SongRadio(item.videoId), first = item.toLocal())
            is MusicItem.Playlist -> router.play(PlayIntent.Collection(collectionRadioId(item.playlistId)))
            is MusicItem.Album -> withAlbumPlaylist(item) { router.play(PlayIntent.Collection(collectionRadioId(it))) }
            is MusicItem.Artist -> withArtistPage(item) { page -> page.radioPlaylistId?.let { router.play(PlayIntent.Collection(it)) } }
        }
    }

    /** Start radio from the track that is playing now, without restarting it on the phone. */
    fun radioFromCurrent(videoId: String) {
        if (router.isPhone) router.local.radioAroundCurrent(videoId)
        // Not PlayIntent.SongRadio: on the loaded video that becomes a plain Play. Playlist only, so it is not a no-op.
        else pc.send(Command.ChangeVideo(playlistId = "RDAMVM$videoId"))
    }

    private fun withArtistPage(artist: MusicItem.Artist, block: (ArtistPage) -> Unit) {
        scope.launch { runCatching { ytm.artist(artist.browseId) }.getOrNull()?.let(block) }
    }

    private fun enqueue(item: MusicItem, next: Boolean) {
        if (router.isPhone) {
            when (item) {
                is MusicItem.Song -> router.local.insert(listOf(item.toLocal()), next)
                is MusicItem.Playlist -> router.local.insertCollection(item.playlistId, next)
                is MusicItem.Album -> withAlbumPlaylist(item) { router.local.insertCollection(it, next) }
                is MusicItem.Artist -> Unit
            }
            return
        }
        val add: (String?, String?) -> Unit = { v, p -> if (next) pc.playNext(v, p) else pc.addToQueue(v, p) }
        when (item) {
            is MusicItem.Song -> add(item.videoId, null)
            is MusicItem.Playlist -> add(null, item.playlistId)
            is MusicItem.Album -> withAlbumPlaylist(item) { add(null, it) }
            is MusicItem.Artist -> Unit
        }
    }

    private fun withAlbumPlaylist(album: MusicItem.Album, block: (String) -> Unit) {
        album.playlistId?.let { return block(it) }
        scope.launch { runCatching { ytm.album(album.browseId).playlistId }.getOrNull()?.let(block) }
    }
}
