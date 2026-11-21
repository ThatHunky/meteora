package dev.thathunky.meteora.companion

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A `/api/v1/command` request. Built by hand instead of by a serializer so that absent ids are
 * *omitted*: the server coerces `null` to "" and an empty watch endpoint wipes the PC's queue
 * (verified in spike S1).
 */
sealed interface Command {
    val name: String

    /** Commands of the same kind replace each other while waiting for rate-limit budget. */
    val kind: Kind get() = Kind.Discrete

    enum class Kind { Discrete, Seek, Volume }

    fun toJson(): JsonObject = buildJsonObject {
        put("command", name)
        data()?.let { put("data", it) }
    }

    fun data(): JsonElement? = null

    data object PlayPause : Command { override val name = "playPause" }
    data object Play : Command { override val name = "play" }
    data object Pause : Command { override val name = "pause" }
    data object Next : Command { override val name = "next" }
    data object Previous : Command { override val name = "previous" }
    data object VolumeUp : Command { override val name = "volumeUp" }
    data object VolumeDown : Command { override val name = "volumeDown" }
    data object Mute : Command { override val name = "mute" }
    data object Unmute : Command { override val name = "unmute" }
    data object ToggleShuffle : Command { override val name = "shuffle" }
    data object ToggleLike : Command { override val name = "toggleLike" }
    data object ToggleDislike : Command { override val name = "toggleDislike" }

    data class SetVolume(val volume: Int) : Command {
        init { require(volume in 0..100) }
        override val name = "setVolume"
        override val kind = Kind.Volume
        override fun data() = JsonPrimitive(volume)
    }

    data class SeekTo(val seconds: Double) : Command {
        init { require(seconds >= 0) }
        override val name = "seekTo"
        override val kind = Kind.Seek
        override fun data() = JsonPrimitive(seconds)
    }

    data class SetRepeat(val mode: RepeatMode) : Command {
        override val name = "repeatMode"
        override fun data() = JsonPrimitive(mode.wire)
    }

    data class PlayQueueIndex(val index: Int) : Command {
        init { require(index >= 0) }
        override val name = "playQueueIndex"
        override fun data() = JsonPrimitive(index)
    }

    /** At least one of [videoId] / [playlistId]. */
    data class ChangeVideo(val videoId: String? = null, val playlistId: String? = null) : Command {
        init { require(!videoId.isNullOrEmpty() || !playlistId.isNullOrEmpty()) }
        override val name = "changeVideo"
        override fun data() = buildJsonObject {
            if (!videoId.isNullOrEmpty()) put("videoId", videoId)
            if (!playlistId.isNullOrEmpty()) put("playlistId", playlistId)
        }
    }

    /** Exactly one of [videoId] / [playlistId]. */
    data class QueueAdd(val videoId: String? = null, val playlistId: String? = null, val index: Int) : Command {
        init {
            require(videoId.isNullOrEmpty() != playlistId.isNullOrEmpty()) { "exactly one of videoId/playlistId" }
            require(index >= 0)
        }
        override val name = "queueAdd"
        override fun data() = buildJsonObject {
            if (!videoId.isNullOrEmpty()) put("videoId", videoId)
            if (!playlistId.isNullOrEmpty()) put("playlistId", playlistId)
            put("index", index)
        }
    }

    data class QueueRemove(val index: Int) : Command {
        init { require(index >= 0) }
        override val name = "queueRemove"
        override fun data() = JsonPrimitive(index)
    }

    data class QueueMove(val fromIndex: Int, val toIndex: Int) : Command {
        init { require(fromIndex >= 0 && toIndex >= 0) }
        override val name = "queueMove"
        override fun data() = buildJsonObject {
            put("fromIndex", fromIndex)
            put("toIndex", toIndex)
        }
    }
}

enum class RepeatMode(val wire: Int) {
    Unknown(-1), None(0), All(1), One(2);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}
