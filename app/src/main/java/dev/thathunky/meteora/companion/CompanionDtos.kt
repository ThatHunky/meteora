package dev.thathunky.meteora.companion

import kotlinx.serialization.Serializable

/*
 * Wire format of the YTMDesktop Companion Server v1 (verified against 2.1.0, see docs/api-notes.md).
 * Everything is lenient: unknown keys ignored, missing fields defaulted.
 */

@Serializable
data class MetadataDto(val apiVersions: List<String> = emptyList())

@Serializable
data class CodeRequestDto(val appId: String, val appName: String, val appVersion: String)

@Serializable
data class CodeResponseDto(val code: String)

@Serializable
data class TokenRequestDto(val appId: String, val code: String)

@Serializable
data class TokenResponseDto(val token: String)

@Serializable
data class ErrorDto(
    val statusCode: Int? = null,
    val code: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class StateDto(
    val player: PlayerDto = PlayerDto(),
    val video: VideoDto? = null,
    val playlistId: String? = null,
)

@Serializable
data class PlayerDto(
    /** -1 Unknown, 0 Paused, 1 Playing, 2 Buffering */
    val trackState: Int = -1,
    val videoProgress: Double = 0.0,
    val volume: Int = 0,
    val muted: Boolean = false,
    val adPlaying: Boolean = false,
    val queue: QueueDto? = null,
)

@Serializable
data class QueueDto(
    val autoplay: Boolean = false,
    val items: List<QueueItemDto> = emptyList(),
    val automixItems: List<QueueItemDto> = emptyList(),
    val isGenerating: Boolean = false,
    val isInfinite: Boolean = false,
    /** -1 Unknown, 0 None, 1 All, 2 One */
    val repeatMode: Int = -1,
    val selectedItemIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
)

@Serializable
data class QueueItemDto(
    val thumbnails: List<ThumbnailDto> = emptyList(),
    val title: String = "",
    val author: String = "",
    /** "3:39", a display string. */
    val duration: String = "",
    val selected: Boolean = false,
    val videoId: String = "",
)

@Serializable
data class VideoDto(
    val author: String = "",
    val channelId: String? = null,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    /** -1 Unknown, 0 Dislike, 1 Indifferent, 2 Like */
    val likeStatus: Int = -1,
    val thumbnails: List<ThumbnailDto> = emptyList(),
    val durationSeconds: Double = 0.0,
    val id: String = "",
    val isLive: Boolean = false,
    /** -1 Unknown, 0 MusicAudio, 1 MusicVideo, 2 MusicUploaded, 3 PodcastEpisode */
    val videoType: Int = -1,
    val metadataFilled: Boolean = false,
)

@Serializable
data class ThumbnailDto(val url: String, val width: Int = 0, val height: Int = 0)
