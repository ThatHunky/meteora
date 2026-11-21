package dev.thathunky.meteora.stream

/** A playable audio URL for one video, plus the headers to send with it. */
data class AudioStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    /** `SystemClock.elapsedRealtime()` after which the URL should be resolved again. */
    val staleAtMs: Long,
    /** Which path produced it, for logs and the settings screen. */
    val source: String,
    /**
     * Disk-cache key: video id plus format. Two lookups of one video can return different
     * formats (Opus from InnerTube, AAC from yt-dlp), and their bytes must never share an entry.
     */
    val cacheKey: String = videoId,
    /** The tier it was resolved for. */
    val quality: StreamQuality = StreamQuality.High,
    /** File size in bytes when YouTube reports it. Lets the player ask for an exact byte range. */
    val contentLength: Long? = null,
)

class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
