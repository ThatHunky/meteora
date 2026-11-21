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
)

class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
