package dev.thathunky.meteora.playback

import java.util.Locale

/** "Stats for nerds": what the player is really decoding right now. Plain values, formatted by [lines]. */
data class NerdStats(
    val videoId: String,
    /** The id the picture comes from, when it is not the song's own (a counterpart video). */
    val pictureId: String?,
    val videoCodec: String?,
    val videoItag: Int?,
    val width: Int,
    val height: Int,
    val fps: Float,
    val videoBytes: Long?,
    val audioCodec: String?,
    val audioItag: Int?,
    val sampleRate: Int,
    val audioBytes: Long?,
    val audioSource: String?,
    val durationSec: Double,
    val bufferSec: Double,
    val droppedFrames: Int,
    val renderedFrames: Int,
    val ceiling: String,
) {
    fun lines(): List<Pair<String, String>> = buildList {
        add("Video ID" to if (pictureId != null && pictureId != videoId) "$videoId (picture $pictureId)" else videoId)
        if (width > 0) {
            val rate = if (fps > 0) "@" + String.format(Locale.ROOT, "%.0f", fps) else ""
            add("Resolution" to "${width}x$height$rate (limit $ceiling)")
            add("Video" to listOfNotNull(videoCodec, videoItag?.let { "itag $it" }, videoKbps()).joinToString(", "))
            add("Frames" to "$droppedFrames dropped of ${droppedFrames + renderedFrames}")
        }
        add("Audio" to listOfNotNull(audioCodec, audioItag?.let { "itag $it" }, if (sampleRate > 0) "$sampleRate Hz" else null, kbps(audioBytes)).joinToString(", "))
        audioSource?.let { add("Source" to it) }
        add("Buffer" to String.format(Locale.ROOT, "%.1f s", bufferSec))
    }

    /** Not for a counterpart's picture: that video's length is not the song's, so the average would be wrong. */
    private fun videoKbps(): String? = if (pictureId == null || pictureId == videoId) kbps(videoBytes) else null

    /** Average bitrate from the file size: the container does not always carry one. */
    private fun kbps(bytes: Long?): String? =
        if (bytes != null && bytes > 0 && durationSec > 0) "${(bytes * 8 / durationSec / 1000).toLong()} kbps" else null
}
