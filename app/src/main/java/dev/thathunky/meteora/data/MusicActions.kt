package dev.thathunky.meteora.data

import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Turns a browsed [MusicItem] into PC commands, resolving album playlist ids on demand. */
@Singleton
class MusicActions @Inject constructor(
    private val player: PlayerRepository,
    private val ytm: YtmClient,
    @AppScope private val scope: CoroutineScope,
) {
    /** Song tap = song + radio; collection = play from the top (optionally from a track). */
    fun play(item: MusicItem, startVideoId: String? = null) {
        when (item) {
            is MusicItem.Song -> player.play(PlayIntent.SongRadio(item.videoId))
            is MusicItem.Playlist -> player.play(PlayIntent.Collection(item.playlistId, startVideoId))
            is MusicItem.Album -> withAlbumPlaylist(item) { player.play(PlayIntent.Collection(it, startVideoId)) }
            is MusicItem.Artist -> scope.launch {
                runCatching { ytm.artist(item.browseId) }.getOrNull()?.let { page ->
                    (page.shufflePlaylistId ?: page.radioPlaylistId)?.let { player.play(PlayIntent.Collection(it)) }
                }
            }
        }
    }

    fun playCollectionTrack(playlistId: String, videoId: String) =
        player.play(PlayIntent.Collection(playlistId, videoId))

    /** changeVideo has no shuffle flag: start from a random track, then turn shuffle on once loaded. */
    fun shuffleCollection(playlistId: String, videoIds: List<String>) {
        val start = videoIds.randomOrNull() ?: return
        player.play(PlayIntent.Collection(playlistId, start))
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(8_000) {
                player.state.first { it?.playlistId == playlistId && it.queue.isNotEmpty() && !it.isGenerating }
            }?.let { if (!it.shuffle) player.send(dev.thathunky.meteora.companion.Command.ToggleShuffle) }
        }
    }

    fun playNext(item: MusicItem) = enqueue(item, next = true)
    fun addToQueue(item: MusicItem) = enqueue(item, next = false)

    fun radio(item: MusicItem.Song) = player.play(PlayIntent.SongRadio(item.videoId))

    private fun enqueue(item: MusicItem, next: Boolean) {
        val add: (String?, String?) -> Unit = { v, p -> if (next) player.playNext(v, p) else player.addToQueue(v, p) }
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
