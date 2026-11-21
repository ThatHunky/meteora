package dev.thathunky.meteora.playback

import dev.thathunky.meteora.ytm.VideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoChoiceTest {
    private val shifted = listOf(VideoSegment(0, 4_000, 100_000), VideoSegment(100_000, 104_000, 100_000))

    @Test fun officialVideoIsItsOwnPicture() =
        assertEquals(VideoPlan("ooooooooooo"), VideoChoice.plan("ooooooooooo", isVideo = true, counterpartId = null, segments = emptyList(), durationMs = 200_000))

    @Test fun uploadIsItsOwnPictureEvenWithACounterpart() =
        assertEquals(VideoPlan("uuuuuuuuuuu"), VideoChoice.plan("uuuuuuuuuuu", isVideo = true, counterpartId = "aaaaaaaaaaa", segments = shifted, durationMs = 200_000))

    @Test fun audioTrackWithCounterpartShowsTheCounterpart() =
        assertEquals(
            VideoPlan("vvvvvvvvvvv", offsetMs = 4_000, endMs = 204_000),
            VideoChoice.plan("aaaaaaaaaaa", isVideo = false, counterpartId = "vvvvvvvvvvv", segments = shifted, durationMs = 200_000),
        )

    @Test fun audioTrackWithoutCounterpartHasNoPicture() =
        assertNull(VideoChoice.plan("aaaaaaaaaaa", isVideo = false, counterpartId = null, segments = shifted, durationMs = 200_000))

    @Test fun counterpartWithoutSegmentMapStaysAudio() =
        assertNull(VideoChoice.plan("aaaaaaaaaaa", isVideo = false, counterpartId = "vvvvvvvvvvv", segments = emptyList(), durationMs = 200_000))

    @Test fun shiftThatChangesStaysAudio() {
        // The video cuts 10 s out in the middle: one offset cannot keep both halves in sync.
        val cut = listOf(VideoSegment(0, 0, 60_000), VideoSegment(70_000, 60_000, 100_000))
        assertNull(VideoChoice.constantOffsetMs(cut))
        assertNull(VideoChoice.plan("aaaaaaaaaaa", false, "vvvvvvvvvvv", cut, 170_000))
    }

    @Test fun videoStartingLaterThanTheSongStaysAudio() =
        assertNull(VideoChoice.plan("aaaaaaaaaaa", false, "vvvvvvvvvvv", listOf(VideoSegment(5_000, 0, 100_000)), 105_000))

    @Test fun unknownDurationIsNotCut() =
        assertEquals(VideoPlan("vvvvvvvvvvv", 0, null), VideoChoice.plan("aaaaaaaaaaa", false, "vvvvvvvvvvv", listOf(VideoSegment(0, 0, 1_000)), 0))

    @Test fun positionMapping() {
        assertEquals(4_000L, VideoChoice.videoPositionMs(shifted, 0))
        assertEquals(54_000L, VideoChoice.videoPositionMs(shifted, 50_000))
        // Inside the second segment.
        assertEquals(154_000L, VideoChoice.videoPositionMs(shifted, 150_000))
        // Past the end of the map.
        assertNull(VideoChoice.videoPositionMs(shifted, 200_000))
        assertNull(VideoChoice.videoPositionMs(emptyList(), 0))
    }
}
