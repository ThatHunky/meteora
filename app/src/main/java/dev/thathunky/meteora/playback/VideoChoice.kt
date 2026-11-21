package dev.thathunky.meteora.playback

import dev.thathunky.meteora.ytm.VideoSegment

/**
 * Where a song's picture comes from in video mode: [videoId]'s video, starting [offsetMs] into it
 * when the song's time 0 is, and cut at [endMs] (video time) so the picture never outlasts the
 * sound. The sound always stays the song's own.
 */
data class VideoPlan(val videoId: String, val offsetMs: Long = 0, val endMs: Long? = null)

object VideoChoice {
    /** Offsets this close count as one; the segment times are rounded to whole frames or so. */
    private const val SAME_OFFSET_MS = 100L

    /**
     * - A video (OMV, UGC, anything but ATV) is its own picture.
     * - An audio track (ATV) with a counterpart shows the counterpart, if the segment map says by
     *   how much the two are shifted, and that shift is the same throughout and has the video
     *   start no later than the song. A merged audio + video source can only cut the start of the
     *   video, so any other map (and no map) plays audio-only rather than a picture out of sync.
     * - Anything else has no picture.
     */
    fun plan(videoId: String, isVideo: Boolean, counterpartId: String?, segments: List<VideoSegment>, durationMs: Long): VideoPlan? {
        if (isVideo) return VideoPlan(videoId)
        val counterpart = counterpartId ?: return null
        val offset = constantOffsetMs(segments) ?: return null
        if (offset < 0) return null
        return VideoPlan(counterpart, offset, endMs = durationMs.takeIf { it > 0 }?.let { it + offset })
    }

    /** Video time for [audioMs] in the song, or null where no segment covers it. */
    fun videoPositionMs(segments: List<VideoSegment>, audioMs: Long): Long? =
        segments.firstOrNull { audioMs >= it.audioStartMs && audioMs < it.audioStartMs + it.durationMs }
            ?.let { audioMs - it.audioStartMs + it.videoStartMs }

    /** `video - audio` when every segment is shifted by the same amount; null otherwise or without segments. */
    fun constantOffsetMs(segments: List<VideoSegment>): Long? {
        if (segments.isEmpty()) return null
        val offsets = segments.map { it.videoStartMs - it.audioStartMs }
        return offsets.first().takeIf { offsets.max() - offsets.min() <= SAME_OFFSET_MS }
    }
}
