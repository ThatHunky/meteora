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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
import dev.thathunky.meteora.stream.StreamQuality
import dev.thathunky.meteora.stream.StreamResolver
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.WatchQueue
import dev.thathunky.meteora.ytm.YtmClient
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    /** Tracks the user rated in this session; YouTube's answer no longer overrides these. */
    private val ratedHere = HashSet<String>()
    private val retried = HashSet<String>()
    /** videoId → tier it was first opened with. Written on the loader thread. */
    private val pinned = ConcurrentHashMap<String, StreamQuality>()
    /** Video id of a song that [play] started by itself while its queue is still loading. Main thread. */
    private var startedAlone: String? = null
    /** When the last play request came in, for the start-up time in the log. 0 once reported. */
    @Volatile private var tappedAt = 0L
    private var failedInARow = 0
    private var networkAttempts = 0
    private var networkRetry: Job? = null
    private var ticker: Job? = null
    private var loadJob: Job? = null

    /** Audio already downloaded, so replays, seeks back and repeat-one cost no network. In cacheDir: Android may clear it. */
    private val diskCache by lazy {
        SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(DISK_CACHE_BYTES), StandaloneDatabaseProvider(context))
    }

    private fun build(): ExoPlayer {
        // The shared client never times out a read (it also holds the PC's websocket). A stream
        // that stalls must fail so ExoPlayer can retry it, e.g. after Wi-Fi drops to mobile data.
        val media = okHttp.newBuilder().readTimeout(MEDIA_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        val cached = CacheDataSource.Factory()
            .setCache(diskCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(media))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Resolve first, cache second: the cache key includes the format the resolver picked.
        val resolving = ResolvingDataSource.Factory(cached) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.host ?: return@Factory spec
            // Runs on ExoPlayer's loader thread, so blocking is fine. The tier is fixed at the
            // first open: a later open (seek, stall) of another format would mix two files' bytes.
            val q = pinned.getOrPut(id) { resolver.quality.current() }
            val stream = runBlocking { resolver.resolve(id, q) }
            // YouTube throttles an open-ended request ("from byte N to the end") to about twice
            // playback speed. Asking for the exact byte range downloads at full speed, which is
            // what lets the buffer below fill and the next track load ahead.
            val size = stream.contentLength
            val length = if (spec.length == C.LENGTH_UNSET.toLong() && size != null && spec.position < size) size - spec.position else spec.length
            spec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setKey(stream.cacheKey)
                .setLength(length)
                .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers)
                .build()
        }
        // A track is a few MB: hold up to 10 minutes of audio, so the current track downloads
        // in full within seconds and the next one is fetched and buffered while this one plays.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setLoadControl(loadControl)
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
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                retried.clear()
                // Tracks ahead may already be part-buffered in their pinned tier (two or more are
                // fetched ahead), so only the ones behind the current track lose their pin.
                val keep = (player.currentMediaItemIndex until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
                pinned.keys.retainAll(keep)
                maybeExtendRadio()
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                failedInARow = 0
                networkAttempts = 0
            }
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && tappedAt != 0L) {
                Log.i(TAG, "playing ${SystemClock.elapsedRealtime() - tappedAt} ms after the tap")
                tappedAt = 0
            }
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
            when {
                // Offline is not the track's fault: wait and try the same track again. Skipping
                // here would burn through the whole queue while there is no network.
                isNetworkError(error) -> retryWhenBackOnline(p, error)
                // Most likely an expired or IP-bound URL: resolve again once, then skip the track.
                id != null && retried.add(id) -> {
                    // A failed web music URL (no Premium, or it wants a token) retries on the anonymous path.
                    resolver.invalidate(id, pinned[id]?.let { resolver.cached(id, it) }?.source)
                    p.prepare()
                }
                else -> {
                    _errors.tryEmit(error)
                    if (p.hasNextMediaItem() && ++failedInARow < MAX_FAILED_IN_A_ROW) {
                        p.seekToNextMediaItem()
                        p.prepare()
                    } else {
                        p.pause()
                    }
                }
            }
            publish()
        }
    }

    private fun isNetworkError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            generateSequence(error.cause) { it.cause }.any {
                it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is NoRouteToHostException
            }

    /** Re-prepare the failed track after 1, 2, 4… s (15 s at most); give up after [MAX_NETWORK_ATTEMPTS]. */
    private fun retryWhenBackOnline(p: ExoPlayer, error: PlaybackException) {
        networkRetry?.cancel()
        if (networkAttempts >= MAX_NETWORK_ATTEMPTS) {
            networkAttempts = 0
            _errors.tryEmit(error)
            p.pause()
            return
        }
        val wait = (1_000L shl networkAttempts.coerceAtMost(4)).coerceAtMost(15_000)
        networkAttempts++
        networkRetry = scope.launch(Dispatchers.Main) {
            delay(wait)
            if (p.playWhenReady && p.playbackState == Player.STATE_IDLE) p.prepare()
        }
    }

    // ---- queue loading ----

    /**
     * [first] is the tapped song when the caller knows it. It then starts at once, by itself, and
     * its queue is put around it when YouTube answers (about 0.8 s later), without a break.
     */
    fun play(intent: PlayIntent, shuffle: Boolean = false, first: LocalSong? = null) {
        // The tapped track is known before its queue is: resolve its stream alongside the queue request.
        val startId = when (intent) {
            is PlayIntent.SongRadio -> intent.videoId
            is PlayIntent.Collection -> if (shuffle) null else intent.startVideoId
            PlayIntent.LikedSongs -> null
        }
        startId?.let(resolver::warm)
        if (first != null && first.videoId == startId) {
            scope.launch(Dispatchers.Main) {
                radioContinuation = null
                apply(Loaded(listOf(first), 0, null, false), 0, true)
                startedAlone = first.videoId
            }
        }
        load { queueFor(intent, shuffle) }
    }

    /** The queue for the song that [play] started alone: add what comes before and after it. */
    private fun putQueueAround(loaded: Loaded): Boolean {
        val id = startedAlone ?: return false
        startedAlone = null
        val p = _player ?: return false
        val start = loaded.start.coerceIn(0, loaded.songs.lastIndex)
        // Not the song that is playing (YouTube may swap in another version): take the queue as it is.
        if (loaded.shuffle || p.mediaItemCount != 1 || p.currentMediaItem?.mediaId != id || loaded.songs[start].videoId != id) {
            Log.i(TAG, "queue starts with ${loaded.songs[start].videoId}, not the tapped $id: restarting on the queue")
            return false
        }
        p.addMediaItems(0, loaded.songs.subList(0, start).map(::mediaItem))
        p.addMediaItems(loaded.songs.subList(start + 1, loaded.songs.size).map(::mediaItem))
        publish()
        return true
    }

    private suspend fun queueFor(intent: PlayIntent, shuffle: Boolean): Loaded =
        when (intent) {
            is PlayIntent.SongRadio -> ytm.watchQueue(videoId = intent.videoId)
            is PlayIntent.Collection -> ytm.watchQueue(intent.startVideoId, intent.playlistId)
            PlayIntent.LikedSongs -> ytm.watchQueue(playlistId = "LM")
        }.also { playlistId = (intent as? PlayIntent.Collection)?.playlistId ?: (intent as? PlayIntent.SongRadio)?.let { "RDAMVM${it.videoId}" } ?: "LM" }
            .let { q -> Loaded(q.songs.map { it.toLocal() }, q.startIndex, q.continuation, shuffle, q.likes) }

    /** One-off stream setup, done when the phone becomes the target instead of on the first tap. */
    fun prewarm() = resolver.prewarm()

    /** Start on [songs] right away (e.g. a hand-off from the PC). */
    fun setQueue(
        songs: List<LocalSong>,
        startIndex: Int,
        positionMs: Long = 0,
        playWhenReady: Boolean = true,
        source: String? = null,
        likes: Map<String, LikeStatus> = emptyMap(),
    ) {
        loadJob?.cancel()
        songs.getOrNull(startIndex)?.let { resolver.warm(it.videoId) }
        scope.launch(Dispatchers.Main) {
            mergeLikes(likes)
            playlistId = source
            radioContinuation = null
            apply(Loaded(songs, startIndex, null, false), positionMs, playWhenReady)
        }
    }

    private data class Loaded(
        val songs: List<LocalSong>,
        val start: Int,
        val continuation: String?,
        val shuffle: Boolean,
        val likes: Map<String, LikeStatus> = emptyMap(),
    )

    /** Take YouTube's like statuses, except for tracks rated here since: ours is newer. Main thread. */
    private fun mergeLikes(fromYouTube: Map<String, LikeStatus>) {
        for ((id, status) in fromYouTube) if (id !in ratedHere) likes[id] = status
    }

    private fun load(fetch: suspend () -> Loaded) {
        loadJob?.cancel()
        val t0 = SystemClock.elapsedRealtime()
        tappedAt = t0
        loadJob = scope.launch {
            val loaded = runCatching { fetch() }.onFailure {
                Log.w(TAG, "queue load failed", it)
                _errors.tryEmit(it)
            }.getOrNull() ?: return@launch
            Log.i(TAG, "queue of ${loaded.songs.size} in ${SystemClock.elapsedRealtime() - t0} ms")
            if (loaded.songs.isEmpty()) return@launch
            // Warm the first track's stream while ExoPlayer is being set up (a no-op if play() already did).
            resolver.warm(loaded.songs[loaded.start.coerceIn(0, loaded.songs.lastIndex)].videoId)
            withContext(Dispatchers.Main) {
                radioContinuation = loaded.continuation
                mergeLikes(loaded.likes)
                if (!putQueueAround(loaded)) apply(loaded, 0, true)
            }
        }
    }

    private fun apply(loaded: Loaded, positionMs: Long, playWhenReady: Boolean) {
        val p = player
        startedAlone = null
        retried.clear()
        pinned.clear()
        failedInARow = 0
        networkAttempts = 0
        networkRetry?.cancel()
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
            .onSuccess { q ->
                withContext(Dispatchers.Main) { mergeLikes(q.likes) }
                insert(q.songs.map { it.toLocal() }, next)
            }
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
                mergeLikes(more.likes)
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
        ratedHere.add(id)
        publish()
        scope.launch {
            runCatching { ytm.rate(id, next) }.onFailure {
                // YouTube did not take it: show the old status again, and let its answer count again.
                withContext(Dispatchers.Main) {
                    likes[id] = now
                    ratedHere.remove(id)
                    publish()
                }
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
                // Waiting to retry after a network error.
                p.playWhenReady && networkRetry?.isActive == true -> TrackState.Buffering
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
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
        const val MEDIA_READ_TIMEOUT_S = 12L
        const val MIN_BUFFER_MS = 60_000
        const val MAX_BUFFER_MS = 10 * 60_000
        const val START_BUFFER_MS = 750
        const val REBUFFER_MS = 2_000
        const val MAX_FAILED_IN_A_ROW = 3
        const val MAX_NETWORK_ATTEMPTS = 8
    }
}
