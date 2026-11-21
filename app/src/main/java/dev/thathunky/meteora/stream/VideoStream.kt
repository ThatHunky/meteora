package dev.thathunky.meteora.stream

import android.media.MediaCodecList

/** A playable video-only URL for one video (the picture; the sound comes from its [AudioStream]). */
data class VideoStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    /** `SystemClock.elapsedRealtime()` after which the URL should be resolved again. */
    val staleAtMs: Long,
    val itag: Int,
    val height: Int,
    /** `avc1`, `vp9` or `av01`. */
    val codec: String,
    /** Video id plus itag, as for audio; audio and video itags never overlap, so the files never mix. */
    val cacheKey: String,
    val quality: VideoQuality,
    /** File size in bytes. Lets the player ask for exact byte ranges. */
    val contentLength: Long?,
)

/** The video codecs this phone decodes in hardware, best compression first, as the core takes them. */
object VideoCodecs {
    private val families = listOf("av01" to "video/av01", "vp9" to "video/x-vnd.on2.vp9", "avc1" to "video/avc")

    /** Checked once. H.264 is always last, even without a hardware decoder: every phone plays it. */
    val preferred: String by lazy {
        val hw = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.isHardwareAccelerated }
                .flatMap { it.supportedTypes.toList() }
                .map { it.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
        (families.filter { (_, mime) -> mime in hw }.map { it.first } + "avc1").distinct().joinToString(",")
    }
}
