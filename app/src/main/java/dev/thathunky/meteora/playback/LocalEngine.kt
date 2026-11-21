package dev.thathunky.meteora.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.QueueEntry
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.domain.TrackState
import dev.thathunky.meteora.domain.withArtSize
import dev.thathunky.meteora.stream.StreamResolver
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.WatchQueue
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** One song as the local queue knows it. */
data class LocalSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val durationSec: Double = 0.0,
    val artworkUrl: String? = null,
)

fun MusicItem.Song.toLocal() = LocalSong(
    videoId = videoId,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.name,
    albumId = album?.browseId,
    artistId = artists.firstOrNull()?.browseId,
    durationSec = parseDuration(duration),
    artworkUrl = thumbnail?.withArtSize(1080),
)

fun QueueEntry.toLocal() = LocalSong(videoId, title, artist, durationSec = parseDuration(duration), artworkUrl = artworkUrl?.withArtSize(1080))

/** "3:45" / "1:02:03" → seconds. */
fun parseDuration(text: String?): Double =
    text?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
        ?.fold(0) { acc, n -> acc * 60 + n }?.toDouble() ?: 0.0

/**
 * Plays on the phone itself. ExoPlayer owns the queue; each item's URI is `meteora://<videoId>`
 * and a [ResolvingDataSource] turns it into a stream URL only when ExoPlayer opens it, so the
 * next track is resolved while the current one is still playing. All player access is on the
 * main thread. Exposes the same [PlaybackState] the PC path does, so the UI is shared.
 */
@UnstableApi
@Singleton
class LocalEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: StreamResolver,
    private val ytm: YtmClient,
    private val okHttp: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors: SharedFlow<Throwable> = _errors

    private var _player: ExoPlayer? = null
    /** Main thread only. */
    val player: ExoPlayer get() = _player ?: build().also { _player = it }

    private var playlistId: String? = null
    private var radioContinuation: String? = null
    private var extending = false
    private var unshuffled: List<String>? = null
    private val likes = HashMap<String, LikeStatus>()
    private val retried = HashSet<String>()
    private var ticker: Job? = null
    private var loadJob: Job? = null

    private fun build(): ExoPlayer {
        val http = OkHttpDataSource.Factory(okHttp)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.host ?: return@Factory spec
            // Runs on ExoPlayer's loader thread, so blocking is fine.
            val stream = runBlocking { resolver.resolve(id) }
            spec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers)
                .build()
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply { addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) maybeExtendRadio()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker?.cancel()
            // Republish now and then so the position stays honest (the UI interpolates in between).
            if (isPlaying) ticker = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    delay(5_000)
                    publish()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = _player ?: return
            val id = p.currentMediaItem?.mediaId
            Log.w(TAG, "playback error on $id: ${error.errorCodeName}", error)
            // Most likely an expired or IP-bound URL: resolve again once, then skip the track.
            if (id != null && retried.add(id)) {
                resolver.invalidate(id)
                p.prepare()
                p.play()
            } else {
                _errors.tryEmit(error)
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        }
    }

    // ---- queue loading ----

    fun play(intent: PlayIntent, shuffle: Boolean = false) = load {
        when (intent) {
            is PlayIntent.SongRadio -> ytm.watchQueue(videoId = intent.videoId)
            is PlayIntent.Collection -> ytm.watchQueue(intent.startVideoId, intent.playlistId)
            PlayIntent.LikedSongs -> ytm.watchQueue(playlistId = "LM")
        }.also { playlistId = (intent as? PlayIntent.Collection)?.playlistId ?: (intent as? PlayIntent.SongRadio)?.let { "RDAMVM${it.videoId}" } ?: "LM" }
            .let { q -> Loaded(q.songs.map { it.toLocal() }, q.startIndex, q.continuation, shuffle) }
    }

    /** Start on [songs] right away (e.g. a hand-off from the PC). */
    fun setQueue(songs: List<LocalSong>, startIndex: Int, positionMs: Long = 0, playWhenReady: Boolean = true, source: String? = null) {
        loadJob?.cancel()
        scope.launch(Dispatchers.Main) {
            playlistId = source
            radioContinuation = null
            apply(Loaded(songs, startIndex, null, false), positionMs, playWhenReady)
        }
    }

    private data class Loaded(val songs: List<LocalSong>, val start: Int, val continuation: String?, val shuffle: Boolean)

    private fun load(fetch: suspend () -> Loaded) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = runCatching { fetch() }.onFailure {
                Log.w(TAG, "queue load failed", it)
                _errors.tryEmit(it)
            }.getOrNull() ?: return@launch
            if (loaded.songs.isEmpty()) return@launch
            // Warm the first track's stream while ExoPlayer is being set up.
            launch { runCatching { resolver.resolve(loaded.songs[loaded.start.coerceIn(0, loaded.songs.lastIndex)].videoId) } }
            withContext(Dispatchers.Main) {
                radioContinuation = loaded.continuation
                apply(loaded, 0, true)
            }
        }
    }

    private fun apply(loaded: Loaded, positionMs: Long, playWhenReady: Boolean) {
        val p = player
        retried.clear()
        unshuffled = null
        val start = loaded.start.coerceIn(0, loaded.songs.lastIndex)
        p.setMediaItems(loaded.songs.map(::mediaItem), start, positionMs)
        p.shuffleModeEnabled = false
        p.prepare()
        p.playWhenReady = playWhenReady
        if (loaded.shuffle) shuffle(true)
        publish()
    }

    fun insert(songs: List<LocalSong>, next: Boolean) = scope.launch(Dispatchers.Main) {
        val p = player
        if (p.mediaItemCount == 0) {
            apply(Loaded(songs, 0, null, false), 0, true)
            return@launch
        }
        val at = if (next) p.currentMediaItemIndex + 1 else p.mediaItemCount
        p.addMediaItems(at, songs.map(::mediaItem))
        unshuffled = unshuffled?.let { it + songs.map(LocalSong::videoId) }
        publish()
    }

    /** Add a whole album / playlist via its watch queue. */
    fun insertCollection(playlistId: String, next: Boolean) = scope.launch {
        runCatching { ytm.watchQueue(playlistId = playlistId) }
            .onSuccess { q -> insert(q.songs.map { it.toLocal() }, next) }
            .onFailure { _errors.tryEmit(it) }
    }

    /** Radios are endless: fetch more when we get near the end. */
    private fun maybeExtendRadio() {
        val p = _player ?: return
        val token = radioContinuation ?: return
        if (extending || p.mediaItemCount - p.currentMediaItemIndex > 3) return
        extending = true
        scope.launch {
            val more: WatchQueue? = runCatching { ytm.watchQueueContinuation(token) }.getOrNull()
            withContext(Dispatchers.Main) {
                extending = false
                if (more == null) return@withContext
                radioContinuation = more.continuation
                val have = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
                val fresh = more.songs.filter { it.videoId !in have }.map { it.toLocal() }
                if (fresh.isNotEmpty()) p.addMediaItems(fresh.map(::mediaItem))
                publish()
            }
        }
    }

    // ---- commands ----

    fun handle(command: Command) = scope.launch(Dispatchers.Main) {
        val p = _player ?: return@launch
        when (command) {
            Command.Play -> { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            Command.Pause -> p.pause()
            Command.PlayPause -> if (p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)) p.pause() else {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
                p.play()
            }
            Command.Next -> p.seekToNextMediaItem()
            Command.Previous -> p.seekToPrevious()
            is Command.SeekTo -> p.seekTo((command.seconds * 1000).toLong())
            is Command.SetVolume -> p.volume = command.volume / 100f
            is Command.SetRepeat -> p.repeatMode = when (command.mode) {
                RepeatMode.All -> Player.REPEAT_MODE_ALL
                RepeatMode.One -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            Command.ToggleShuffle -> shuffle(unshuffled == null)
            is Command.PlayQueueIndex -> if (command.index in 0 until p.mediaItemCount) {
                p.seekTo(command.index, 0)
                p.play()
            }
            is Command.QueueRemove -> if (command.index in 0 until p.mediaItemCount) {
                val id = p.getMediaItemAt(command.index).mediaId
                p.removeMediaItem(command.index)
                unshuffled = unshuffled?.minus(id)
            }
            is Command.QueueMove -> if (command.fromIndex in 0 until p.mediaItemCount) {
                p.moveMediaItem(command.fromIndex, command.toIndex.coerceIn(0, p.mediaItemCount - 1))
            }
            Command.ToggleLike -> rate(LikeStatus.Like)
            Command.ToggleDislike -> rate(LikeStatus.Dislike)
            else -> Log.d(TAG, "ignored on phone: ${command.name}")
        }
        publish()
    }

    /**
     * Shuffle like YTM does it: really reorder the queue (current track first), so the queue
     * sheet shows the order things will play in. Turning it off restores the original order.
     */
    private fun shuffle(on: Boolean) {
        val p = _player ?: return
        val count = p.mediaItemCount
        if (count == 0) return
        val items = (0 until count).map { p.getMediaItemAt(it) }
        val current = p.currentMediaItemIndex
        val playing = items[current]
        val newOrder = if (on) {
            unshuffled = items.map { it.mediaId }
            listOf(playing) + items.filterIndexed { i, _ -> i != current }.shuffled()
        } else {
            val rank = (unshuffled ?: return).withIndex().associate { it.value to it.index }
            unshuffled = null
            items.withIndex().sortedBy { rank[it.value.mediaId] ?: (Int.MAX_VALUE - count + it.index) }.map { it.value }
        }
        val k = newOrder.indexOfFirst { it === playing }
        // Replace everything around the playing item so playback isn't interrupted.
        if (current + 1 < count) p.removeMediaItems(current + 1, count)
        if (current > 0) p.removeMediaItems(0, current)
        p.addMediaItems(0, newOrder.subList(0, k))
        p.addMediaItems(newOrder.subList(k + 1, newOrder.size))
        publish()
    }

    private fun rate(target: LikeStatus) {
        val id = _player?.currentMediaItem?.mediaId ?: return
        val now = likes[id] ?: LikeStatus.Indifferent
        val next = if (now == target) LikeStatus.Indifferent else target
        likes[id] = next
        publish()
        scope.launch {
            runCatching { ytm.rate(id, next) }.onFailure {
                likes[id] = now
                withContext(Dispatchers.Main) { publish() }
                _errors.tryEmit(it)
            }
        }
    }

    fun pause() = handle(Command.Pause)

    /** Main thread. */
    fun snapshotQueue(): List<LocalSong> {
        val p = _player ?: return emptyList()
        return (0 until p.mediaItemCount).map { p.getMediaItemAt(it).toLocalSong() }
    }

    val currentPlaylistId: String? get() = playlistId

    fun stop() = scope.launch(Dispatchers.Main) {
        _player?.run {
            stop()
            clearMediaItems()
        }
        playlistId = null
        radioContinuation = null
        publish()
    }

    // ---- state ----

    private fun publish() {
        val p = _player ?: return
        if (p.mediaItemCount == 0) {
            _state.value = null
            return
        }
        val current = p.currentMediaItem ?: return
        val song = current.toLocalSong()
        val durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        _state.value = PlaybackState(
            trackState = when {
                p.isPlaying -> TrackState.Playing
                p.playWhenReady && p.playbackState == Player.STATE_BUFFERING -> TrackState.Buffering
                p.playWhenReady && p.playbackState == Player.STATE_IDLE && p.playerError == null -> TrackState.Buffering
                else -> TrackState.Paused
            },
            progressSec = p.currentPosition / 1000.0,
            reportedAtMs = SystemClock.elapsedRealtime(),
            volume = (p.volume * 100).toInt(),
            muted = p.volume == 0f,
            adPlaying = false,
            track = Track(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                artistChannelId = song.artistId,
                durationSec = durationMs?.div(1000.0) ?: song.durationSec,
                artworkUrl = song.artworkUrl,
                like = likes[song.videoId] ?: LikeStatus.Indifferent,
                isLive = false,
            ),
            queue = (0 until p.mediaItemCount).map { i ->
                val s = p.getMediaItemAt(i).toLocalSong()
                QueueEntry(s.videoId, s.title, s.artist, formatDuration(s.durationSec), s.artworkUrl?.withArtSize(226))
            },
            automix = emptyList(),
            selectedIndex = p.currentMediaItemIndex,
            repeat = when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.None
            },
            shuffle = unshuffled != null,
            isGenerating = extending,
            playlistId = playlistId,
        )
    }

    private fun mediaItem(s: LocalSong): MediaItem = MediaItem.Builder()
        .setMediaId(s.videoId)
        .setUri("$SCHEME://${s.videoId}")
        .setCustomCacheKey(s.videoId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(s.artworkUrl?.let(Uri::parse))
                .setDurationMs(if (s.durationSec > 0) (s.durationSec * 1000).toLong() else null)
                .setExtras(Bundle().apply {
                    putString(X_ALBUM_ID, s.albumId)
                    putString(X_ARTIST_ID, s.artistId)
                })
                .build(),
        )
        .build()

    private fun MediaItem.toLocalSong(): LocalSong {
        val m = mediaMetadata
        return LocalSong(
            videoId = mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString(),
            albumId = m.extras?.getString(X_ALBUM_ID),
            artistId = m.extras?.getString(X_ARTIST_ID),
            durationSec = (m.durationMs ?: 0L) / 1000.0,
            artworkUrl = m.artworkUri?.toString(),
        )
    }

    private fun formatDuration(sec: Double): String {
        if (sec <= 0) return ""
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val TAG = "LocalEngine"
        const val SCHEME = "meteora"
        const val X_ALBUM_ID = "albumId"
        const val X_ARTIST_ID = "artistId"
    }
}
