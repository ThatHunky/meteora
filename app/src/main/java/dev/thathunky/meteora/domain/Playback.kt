package dev.thathunky.meteora.domain

import dev.thathunky.meteora.companion.QueueItemDto
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.companion.StateDto
import dev.thathunky.meteora.companion.ThumbnailDto

enum class TrackState { Unknown, Paused, Playing, Buffering }

enum class LikeStatus { Unknown, Dislike, Indifferent, Like }

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumId: String?,
    val artistChannelId: String?,
    val durationSec: Double,
    val artworkUrl: String?,
    val like: LikeStatus,
    val isLive: Boolean,
)

data class QueueEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val artworkUrl: String?,
)

data class PlaybackState(
    val trackState: TrackState,
    /** Seconds, as last reported. Interpolate with [reportedAtMs] while playing. */
    val progressSec: Double,
    /** `SystemClock.elapsedRealtime()` when [progressSec] was received. */
    val reportedAtMs: Long,
    val volume: Int,
    val muted: Boolean,
    val adPlaying: Boolean,
    val track: Track?,
    val queue: List<QueueEntry>,
    val automix: List<QueueEntry>,
    val selectedIndex: Int,
    val repeat: RepeatMode,
    val shuffle: Boolean,
    val isGenerating: Boolean,
    val playlistId: String?,
    /** Phone target only: the current track has a picture to show in video mode. Always false for the PC. */
    val hasVideo: Boolean = false,
    /** Phone target only: the Song / Video switch is on Video (for the session, not the track). */
    val videoMode: Boolean = false,
) {
    val isPlaying get() = trackState == TrackState.Playing || trackState == TrackState.Buffering

    fun progressAt(nowMs: Long): Double {
        val dur = track?.durationSec ?: 0.0
        if (trackState != TrackState.Playing) return progressSec
        val p = progressSec + (nowMs - reportedAtMs) / 1000.0
        return if (dur > 0) p.coerceAtMost(dur) else p
    }

    /** A mid-track-change frame from YTM (queue emptied, nothing selected). */
    val isTransient get() = queue.isEmpty() && selectedIndex < 0 && isGenerating
}

/** Ask googleusercontent for a [size]px square (`=w60-h60…` → `=w1080-h1080…`); other URLs unchanged. */
fun String.withArtSize(size: Int): String = replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")

/** Largest thumbnail, upscaled via the googleusercontent size suffix when possible. */
fun List<ThumbnailDto>.bestArtwork(size: Int = 1080): String? {
    val best = maxByOrNull { it.width * it.height }?.url ?: return null
    return best.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
}

fun StateDto.toDomain(nowMs: Long): PlaybackState {
    val q = player.queue
    return PlaybackState(
        trackState = when (player.trackState) {
            0 -> TrackState.Paused
            1 -> TrackState.Playing
            2 -> TrackState.Buffering
            else -> TrackState.Unknown
        },
        progressSec = player.videoProgress,
        reportedAtMs = nowMs,
        volume = player.volume,
        muted = player.muted,
        adPlaying = player.adPlaying,
        track = video?.takeIf { it.id.isNotEmpty() }?.let { v ->
            Track(
                videoId = v.id,
                title = v.title,
                artist = v.author,
                album = v.album,
                albumId = v.albumId,
                artistChannelId = v.channelId,
                durationSec = v.durationSeconds,
                artworkUrl = v.thumbnails.bestArtwork(),
                like = when (v.likeStatus) {
                    0 -> LikeStatus.Dislike
                    1 -> LikeStatus.Indifferent
                    2 -> LikeStatus.Like
                    else -> LikeStatus.Unknown
                },
                isLive = v.isLive,
            )
        },
        queue = q?.items.orEmpty().map(QueueItemDto::toEntry),
        automix = q?.automixItems.orEmpty().map(QueueItemDto::toEntry),
        selectedIndex = q?.selectedItemIndex ?: -1,
        repeat = RepeatMode.of(q?.repeatMode ?: -1),
        shuffle = q?.shuffleEnabled ?: false,
        isGenerating = q?.isGenerating ?: false,
        playlistId = playlistId,
    )
}

private fun QueueItemDto.toEntry() = QueueEntry(videoId, title, author, duration, thumbnails.bestArtwork(226))

/** What the user asked to play; mapped to verified recipes in docs/api-notes.md. */
sealed interface PlayIntent {
    /** Song tap: the song plus its radio. */
    data class SongRadio(val videoId: String) : PlayIntent
    /** Album / playlist / mix from the top, or from a given track. */
    data class Collection(val playlistId: String, val startVideoId: String? = null) : PlayIntent
    data object LikedSongs : PlayIntent
}
