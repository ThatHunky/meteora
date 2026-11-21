package dev.thathunky.meteora.data

import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.playback.toLocal
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient
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
) {
    private val pc get() = router.pc

    /** Song tap = song + radio; collection = play from the top (optionally from a track). */
    fun play(item: MusicItem, startVideoId: String? = null) {
        when (item) {
            is MusicItem.Song -> router.play(PlayIntent.SongRadio(item.videoId))
            is MusicItem.Playlist -> router.play(PlayIntent.Collection(item.playlistId, startVideoId))
            is MusicItem.Album -> withAlbumPlaylist(item) { router.play(PlayIntent.Collection(it, startVideoId)) }
            is MusicItem.Artist -> scope.launch {
                runCatching { ytm.artist(item.browseId) }.getOrNull()?.let { page ->
                    (page.shufflePlaylistId ?: page.radioPlaylistId)?.let { router.play(PlayIntent.Collection(it)) }
                }
            }
        }
    }

    fun playCollectionTrack(playlistId: String, videoId: String) =
        router.play(PlayIntent.Collection(playlistId, videoId))

    fun shuffleCollection(playlistId: String, videoIds: List<String>) {
        val start = videoIds.randomOrNull() ?: return
        if (router.isPhone) return router.play(PlayIntent.Collection(playlistId, start), shuffle = true)
        // changeVideo has no shuffle flag: start from a random track, then turn shuffle on once loaded.
        pc.play(PlayIntent.Collection(playlistId, start))
        scope.launch {
            withTimeoutOrNull(8_000) {
                pc.state.first { it?.playlistId == playlistId && it.queue.isNotEmpty() && !it.isGenerating }
            }?.let { if (!it.shuffle) pc.send(Command.ToggleShuffle) }
        }
    }

    fun playNext(item: MusicItem) = enqueue(item, next = true)
    fun addToQueue(item: MusicItem) = enqueue(item, next = false)

    fun radio(item: MusicItem.Song) = router.play(PlayIntent.SongRadio(item.videoId))

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
