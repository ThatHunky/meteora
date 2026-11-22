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
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import dev.thathunky.meteora.stream.VideoQuality
import dev.thathunky.meteora.ytm.VideoSegment
import java.io.IOException
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
import dev.thathunky.meteora.ytm.AutomixRef
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
    /** Not an audio-only (ATV) track: it is its own music video. */
    val isVideo: Boolean = false,
    val videoCounterpartId: String? = null,
    val segments: List<VideoSegment> = emptyList(),
) {
    /** Where its picture comes from in video mode; null when it has none to show. */
    val videoPlan: VideoPlan? get() = VideoChoice.plan(videoId, isVideo, videoCounterpartId, segments, (durationSec * 1000).toLong())
}

fun MusicItem.Song.toLocal() = LocalSong(
    videoId = videoId,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.name,
    albumId = album?.browseId,
    artistId = artists.firstOrNull()?.browseId,
    durationSec = parseDuration(duration),
    artworkUrl = thumbnail?.withArtSize(1080),
    isVideo = isVideo,
    videoCounterpartId = videoCounterpartId,
    segments = segments,
)

fun QueueEntry.toLocal() = LocalSong(videoId, title, artist, durationSec = parseDuration(duration), artworkUrl = artworkUrl?.withArtSize(1080))

/** A track's picture could not be resolved; it then plays as audio. An IOException, as data sources must throw. */
class VideoUnavailableException(message: String, cause: Throwable?) : IOException(message, cause)

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
    private val autoplay: AutoplayStore,
    private val downloads: dev.thathunky.meteora.downloads.DownloadManager,
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
    /** What to append once an album or playlist runs out (autoplay). Used once, then its radio continues. */
    private var automix: AutomixRef? = null
    private var extending = false
    private var unshuffled: List<String>? = null
    private val likes = HashMap<String, LikeStatus>()
    /** Tracks the user rated in this session; YouTube's answer no longer overrides these. */
    private val ratedHere = HashSet<String>()
    private val retried = HashSet<String>()
    /** videoId → tier it was first opened with. Written on the loader thread. */
    private val pinned = ConcurrentHashMap<String, StreamQuality>()
    /** videoId → its downloaded file's URI, or "" to stream; fixed at the first open like [pinned]. Loader thread. */
    private val pinnedFile = ConcurrentHashMap<String, String>()
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
            // Ranges over 10 MiB are throttled: video files (and very long tracks) go in chunks.
            .setUpstreamDataSourceFactory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(media)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Resolve first, cache second: the cache key includes the format the resolver picked.
        // A downloaded file is read straight from MediaStore, never through the HTTP cache.
        val network = SchemeDataSource.Factory(cached) { androidx.media3.datasource.ContentDataSource(context) }
        val resolving = ResolvingDataSource.Factory(network) { spec ->
            if (spec.uri.scheme == VIDEO_SCHEME) return@Factory resolveVideo(spec)
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val id = spec.uri.host ?: return@Factory spec
            downloadedFile(id)?.let { return@Factory spec.buildUpon().setUri(Uri.parse(it)).setKey(null).build() }
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
        // With video, the same 10 minutes of 720p would be 100 MB or more: past the first minute,
        // loading also stops at [MAX_BUFFER_BYTES]. Audio alone never gets near it.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(MediaSources(DefaultMediaSourceFactory(resolving), resolving))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(listener)
                // No picture until a screen shows one (attachVideo).
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            }
    }

    // ---- video ----

    /** Session-wide, not per track: once on, every following track that has a video shows it. Main thread. */
    private var videoMode = false
    /** Tracks whose picture failed this session: they play as audio, even in video mode. Main thread. */
    private val noVideo = HashSet<String>()
    /** Video id → ceiling it was first opened with, for the same reason as [pinned]. Written on the loader thread. */
    private val pinnedVideo = ConcurrentHashMap<String, VideoQuality>()
    /** Views showing the picture, the last one on top (Now Playing, then full screen over it). Main thread. */
    private val videoViews = ArrayList<View>()

    /** The track's downloaded file, decided once per track; a file deleted outside the app is forgotten and the track streams. Loader thread. */
    private fun downloadedFile(id: String): String? = pinnedFile.getOrPut(id) {
        val uri = downloads.index.completeUri(id) ?: return@getOrPut ""
        if (downloads.fileExists(uri)) uri else "".also { downloads.forgetMissing(id) }
    }.ifEmpty { null }

    /** Runs on the loader thread, like the audio branch. */
    private fun resolveVideo(spec: DataSpec): DataSpec {
        val id = spec.uri.host ?: throw VideoUnavailableException("no video id", null)
        val q = pinnedVideo.getOrPut(id) { resolver.quality.currentVideo() }
        val stream = try {
            runBlocking { resolver.resolveVideo(id, q) }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw VideoUnavailableException("video for $id: ${e.message}", e)
        }
        val size = stream.contentLength
        val length = if (spec.length == C.LENGTH_UNSET.toLong() && size != null && spec.position < size) size - spec.position else spec.length
        return spec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(stream.cacheKey)
            .setLength(length)
            .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers)
            .build()
    }

    /**
     * Audio items as before. An item in video mode (its URI carries [Q_VIDEO]) is the same audio
     * source merged with the picture's (`meteora-video://<id>`), so the sound is exactly what audio
     * mode plays, same pinned format.
     */
    private inner class MediaSources(private val default: DefaultMediaSourceFactory, video: DataSource.Factory) : MediaSource.Factory by default {
        private val videoFactory = ProgressiveMediaSource.Factory(video).setLoadErrorHandlingPolicy(NoRetryForMissingVideo())

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val audio = default.createMediaSource(mediaItem)
            if (mediaItem.localConfiguration?.uri?.getQueryParameter(Q_VIDEO) == null) return audio
            val plan = mediaItem.toLocalSong().videoPlan ?: return audio
            var picture: MediaSource = videoFactory.createMediaSource(MediaItem.fromUri("$VIDEO_SCHEME://${plan.videoId}"))
            if (plan.offsetMs > 0 || plan.endMs != null) {
                picture = ClippingMediaSource.Builder(picture)
                    .setStartPositionMs(plan.offsetMs)
                    .apply { plan.endMs?.let { setEndPositionMs(it) } }
                    .build()
            }
            // Audio first: the item's timeline and metadata are the song's.
            return MergingMediaSource(audio, picture)
        }
    }

    /** A picture that cannot be resolved is not retried: the track falls back to audio at once. */
    private class NoRetryForMissingVideo : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (generateSequence(loadErrorInfo.exception as Throwable?) { it.cause }.any { it is VideoUnavailableException }) C.TIME_UNSET
            else super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** Bumped when the user picks a picture quality: video items get a new URI, so they reload. Main thread. */
    private var videoRev = 0

    fun videoQuality(): VideoQuality = resolver.quality.currentVideo()

    /** The picker on the picture: applies now, to the current track too, and is kept for this kind of network. */
    fun setVideoQuality(q: VideoQuality) = scope.launch(Dispatchers.Main) {
        if (resolver.quality.currentVideo() == q) return@launch
        resolver.quality.setCurrentVideo(q)
        pinnedVideo.clear()
        videoRev++
        Log.i(TAG, "video quality $q")
        _player?.let { syncVideoModes(it) }
    }

    /** For the stats overlay. Main thread. */
    fun nerdStats(): NerdStats? {
        val p = _player ?: return null
        val song = p.currentMediaItem?.toLocalSong() ?: return null
        val audio = pinned[song.videoId]?.let { resolver.cached(song.videoId, it) }
        val pictureId = song.videoPlan?.videoId
        val video = pictureId?.let { id -> pinnedVideo[id]?.let { resolver.cachedVideo(id, it) } }
        val vf = p.videoFormat
        val af = p.audioFormat
        val counters = p.videoDecoderCounters
        return NerdStats(
            videoId = song.videoId,
            pictureId = pictureId,
            videoCodec = vf?.codecs ?: video?.codec,
            videoItag = video?.itag,
            width = vf?.width?.coerceAtLeast(0) ?: 0,
            height = vf?.height?.coerceAtLeast(0) ?: 0,
            fps = vf?.frameRate ?: 0f,
            videoBytes = video?.contentLength,
            audioCodec = af?.codecs ?: af?.sampleMimeType?.substringAfter("/"),
            audioItag = audio?.cacheKey?.substringAfter("/", "")?.toIntOrNull(),
            sampleRate = af?.sampleRate?.coerceAtLeast(0) ?: 0,
            audioBytes = audio?.contentLength,
            audioSource = audio?.source,
            durationSec = song.durationSec,
            bufferSec = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000.0,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
            // The ceiling this track was opened with; it keeps it when the setting or the network changes.
            ceiling = "${(pictureId?.let { pinnedVideo[it] } ?: videoQuality()).maxHeight}p",
        )
    }

    fun setVideoMode(on: Boolean) = scope.launch(Dispatchers.Main) {
        if (videoMode == on) return@launch
        videoMode = on
        Log.i(TAG, "video mode ${if (on) "on" else "off"}")
        _player?.let { syncVideoModes(it) }
        publish()
    }

    /** Show the picture in [view] (a TextureView or SurfaceView); the video track turns on. Main thread. */
    fun attachVideo(view: View) {
        videoViews.remove(view)
        videoViews.add(view)
        showOn(view)
        applyVideoTrack()
    }

    /** [view] no longer shows the picture; the one under it takes over, or the video track turns off. Main thread. */
    fun detachVideo(view: View) {
        val wasTop = videoViews.lastOrNull() === view
        if (!videoViews.remove(view)) return
        val p = _player ?: return
        when (view) {
            is TextureView -> p.clearVideoTextureView(view)
            is SurfaceView -> p.clearVideoSurfaceView(view)
        }
        if (wasTop) videoViews.lastOrNull()?.let(::showOn)
        applyVideoTrack()
    }

    private fun showOn(view: View) {
        when (view) {
            is TextureView -> player.setVideoTextureView(view)
            is SurfaceView -> player.setVideoSurfaceView(view)
        }
    }

    /**
     * The video track is on only while a view shows it. Off in the background, with the screen
     * off and with Now Playing closed, so no data goes to pictures nobody sees; the sound goes on.
     */
    private fun applyVideoTrack() {
        val p = _player ?: return
        val off = videoViews.isEmpty()
        if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == off) return
        Log.i(TAG, "video track ${if (off) "off" else "on"}")
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build()
    }

    /**
     * Brings the current item and the next [VIDEO_AHEAD] in line with the mode; later ones are
     * fixed as playback gets near them. The current one restarts at the same position.
     */
    private fun syncVideoModes(p: ExoPlayer) {
        val count = p.mediaItemCount
        if (count == 0) return
        val current = p.currentMediaItemIndex
        for (i in current..minOf(count - 1, current + VIDEO_AHEAD)) {
            val item = p.getMediaItemAt(i)
            val wanted = mediaItem(item.toLocalSong())
            if (wanted.localConfiguration?.uri == item.localConfiguration?.uri) continue
            if (i == current) {
                val position = p.currentPosition
                p.replaceMediaItem(i, wanted)
                p.seekTo(i, position)
            } else {
                p.replaceMediaItem(i, wanted)
            }
        }
    }

    /** The current track's picture failed: play it as audio from where it was. True if that was the case. */
    private fun dropVideo(p: ExoPlayer): Boolean {
        val i = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return false
        if (item.localConfiguration?.uri?.getQueryParameter(Q_VIDEO) == null) return false
        noVideo.add(item.mediaId)
        Log.w(TAG, "no picture for ${item.mediaId}: playing it as audio")
        val position = p.currentPosition
        p.replaceMediaItem(i, mediaItem(item.toLocalSong()))
        p.seekTo(i, position)
        p.prepare()
        return true
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                retried.clear()
                // Tracks ahead may already be part-buffered in their pinned tier (two or more are
                // fetched ahead), so only the ones behind the current track lose their pin.
                val ahead = (player.currentMediaItemIndex until player.mediaItemCount).map { player.getMediaItemAt(it) }
                pinned.keys.retainAll(ahead.mapTo(HashSet()) { it.mediaId })
                pinnedFile.keys.retainAll(ahead.mapTo(HashSet()) { it.mediaId })
                pinnedVideo.keys.retainAll(ahead.mapNotNullTo(HashSet()) { it.toLocalSong().videoPlan?.videoId })
                maybeExtendRadio()
                // Not from inside the callback: it replaces items.
                if (player.mediaItemCount > 0) scope.launch(Dispatchers.Main) { _player?.let(::syncVideoModes) }
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                failedInARow = 0
                networkAttempts = 0
            }
            publish()
        }

        /** The listening log for smart downloads: where the track that was left got to, and whether the user moved on. */
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            // A seek within the track, or the queue rearranged around it, is not leaving it. A new
            // queue replacing the track (REMOVE) can count a play but never a skip.
            val moved = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_REMOVE
            if (!auto && (!moved || old.mediaItem?.mediaId == new.mediaItem?.mediaId)) return
            old.mediaItem?.let { downloads.listens.left(it.toLocalSong(), old.positionMs, byUser = reason == Player.DISCONTINUITY_REASON_SEEK) }
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
                // No picture to be had: play the sound alone at once.
                isVideoUnavailable(error) && dropVideo(p) -> Unit
                // Most likely an expired or IP-bound URL: resolve again once, then skip the track.
                id != null && retried.add(id) -> {
                    // A failed web music URL (no Premium, or it wants a token) retries on the anonymous path.
                    resolver.invalidate(id, pinned[id]?.let { resolver.cached(id, it) }?.source)
                    pinnedFile.remove(id)
                    p.currentMediaItem?.toLocalSong()?.videoPlan?.let { resolver.invalidateVideo(it.videoId) }
                    p.prepare()
                }
                // Failed again in video mode: the sound on its own may well play.
                dropVideo(p) -> Unit
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

    private fun isVideoUnavailable(error: PlaybackException): Boolean =
        generateSequence(error.cause) { it.cause }.any { it is VideoUnavailableException }

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
                automix = null
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
        // The queue's copy of the playing song knows more (its music-video counterpart). Same URI
        // (audio mode, or no picture either way): only the metadata is updated, playback goes on.
        // Otherwise it now has a picture to show: restart it with one at the same position.
        val richer = mediaItem(loaded.songs[start])
        val i = p.currentMediaItemIndex
        if (richer.localConfiguration?.uri == p.currentMediaItem?.localConfiguration?.uri) {
            p.replaceMediaItem(i, richer)
        } else {
            val position = p.currentPosition
            p.replaceMediaItem(i, richer)
            p.seekTo(i, position)
        }
        publish()
        return true
    }

    private suspend fun queueFor(intent: PlayIntent, shuffle: Boolean): Loaded =
        when (intent) {
            is PlayIntent.SongRadio -> ytm.watchQueue(videoId = intent.videoId)
            is PlayIntent.Collection -> ytm.watchQueue(intent.startVideoId, intent.playlistId)
            PlayIntent.LikedSongs -> ytm.watchQueue(playlistId = "LM")
        }.also { playlistId = (intent as? PlayIntent.Collection)?.playlistId ?: (intent as? PlayIntent.SongRadio)?.let { "RDAMVM${it.videoId}" } ?: "LM" }
            .let { q ->
                // Shuffle without a chosen track (from a list's menu): start on a random one, as YTM does.
                val randomStart = shuffle && intent is PlayIntent.Collection && intent.startVideoId == null && q.songs.isNotEmpty()
                val start = if (randomStart) q.songs.indices.random() else q.startIndex
                Loaded(q.songs.map { it.toLocal() }, start, q.continuation, shuffle, q.likes, q.automix)
            }

    /**
     * Start radio from the track playing now. It keeps playing; the rest of the queue is replaced
     * by its radio once YouTube answers. Ignored if another track is playing by then.
     */
    fun radioAroundCurrent(videoId: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = runCatching { queueFor(PlayIntent.SongRadio(videoId), false) }.onFailure {
                Log.w(TAG, "radio load failed", it)
                _errors.tryEmit(it)
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                val p = _player ?: return@withContext
                if (p.currentMediaItem?.mediaId != videoId) return@withContext
                radioContinuation = loaded.continuation
                automix = null
                mergeLikes(loaded.likes)
                unshuffled = null
                val current = p.currentMediaItemIndex
                if (current + 1 < p.mediaItemCount) p.removeMediaItems(current + 1, p.mediaItemCount)
                if (current > 0) p.removeMediaItems(0, current)
                p.addMediaItems(loaded.songs.filter { it.videoId != videoId }.map(::mediaItem))
                publish()
            }
        }
    }

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
            automix = null
            apply(Loaded(songs, startIndex, null, false), positionMs, playWhenReady)
        }
    }

    private data class Loaded(
        val songs: List<LocalSong>,
        val start: Int,
        val continuation: String?,
        val shuffle: Boolean,
        val likes: Map<String, LikeStatus> = emptyMap(),
        val automix: AutomixRef? = null,
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
                automix = loaded.automix
                mergeLikes(loaded.likes)
                if (!putQueueAround(loaded)) apply(loaded, 0, true)
                // A single or a short EP is near its end from the start, with no track change to notice it.
                maybeExtendRadio()
            }
        }
    }

    private fun apply(loaded: Loaded, positionMs: Long, playWhenReady: Boolean) {
        val p = player
        startedAlone = null
        retried.clear()
        pinned.clear()
        pinnedFile.clear()
        pinnedVideo.clear()
        // A new queue gives pictures that failed before another chance (the network may have changed).
        noVideo.clear()
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

    /**
     * Radios are endless: fetch more when we get near the end. A queue without a continuation
     * (an album or playlist) goes on with its automix once, if autoplay is on, and then that radio's
     * continuation. Repeat-all wins over autoplay. Main thread.
     */
    private fun maybeExtendRadio() {
        val p = _player ?: return
        if (extending || p.mediaItemCount - p.currentMediaItemIndex > 3) return
        val token = radioContinuation
        val mix = automix.takeIf { token == null && autoplay.enabled.value && p.repeatMode != Player.REPEAT_MODE_ALL }
        if (token == null && mix == null) return
        extending = true
        scope.launch {
            val more: WatchQueue? = runCatching {
                if (token != null) ytm.watchQueueContinuation(token) else ytm.watchQueue(playlistId = mix!!.playlistId, params = mix.params)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                extending = false
                if (more == null) return@withContext
                // Another queue was loaded while this was out: it is not ours to extend.
                if (if (token != null) radioContinuation != token else automix != mix) return@withContext
                radioContinuation = more.continuation
                // A long playlist pages through continuations first; its automix may come on a later page.
                automix = if (token == null) null else more.automix ?: automix
                if (token == null) Log.i(TAG, "autoplay: ${mix?.playlistId}, ${more.songs.size} tracks")
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
            is Command.SetRepeat -> {
                p.repeatMode = when (command.mode) {
                    RepeatMode.All -> Player.REPEAT_MODE_ALL
                    RepeatMode.One -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                // Repeat-all off near the end of an album: autoplay may now go on.
                maybeExtendRadio()
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
        automix = null
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
            hasVideo = song.videoPlan != null && song.videoId !in noVideo,
            videoMode = videoMode,
        )
    }

    /** In video mode, a song with a picture gets [Q_VIDEO] on its URI, which [MediaSources] reads. */
    private fun mediaItem(s: LocalSong): MediaItem = MediaItem.Builder()
        .setMediaId(s.videoId)
        .setUri(if (videoMode && s.videoId !in noVideo && s.videoPlan != null) "$SCHEME://${s.videoId}?$Q_VIDEO=1&r=$videoRev" else "$SCHEME://${s.videoId}")
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
                    putBoolean(X_IS_VIDEO, s.isVideo)
                    putString(X_COUNTERPART, s.videoCounterpartId)
                    if (s.segments.isNotEmpty()) putLongArray(X_SEGMENTS, s.segments.flatMap { listOf(it.audioStartMs, it.videoStartMs, it.durationMs) }.toLongArray())
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
            isVideo = m.extras?.getBoolean(X_IS_VIDEO) == true,
            videoCounterpartId = m.extras?.getString(X_COUNTERPART),
            segments = m.extras?.getLongArray(X_SEGMENTS)?.toList()?.chunked(3)?.filter { it.size == 3 }
                ?.map { VideoSegment(it[0], it[1], it[2]) }.orEmpty(),
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
        const val VIDEO_SCHEME = "meteora-video"
        /** Query parameter on an item's URI: this item plays with its picture. */
        const val Q_VIDEO = "video"
        const val X_ALBUM_ID = "albumId"
        const val X_ARTIST_ID = "artistId"
        const val X_IS_VIDEO = "isVideo"
        const val X_COUNTERPART = "videoCounterpart"
        /** Flattened (audio start, video start, duration) triples. */
        const val X_SEGMENTS = "segments"
        const val VIDEO_AHEAD = 2
        const val MAX_BUFFER_BYTES = 50 * 1024 * 1024
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
