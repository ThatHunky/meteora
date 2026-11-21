package dev.thathunky.meteora.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.data.PlayerRepository
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.TrackState
import android.net.Uri

/**
 * A Media3 player that plays nothing locally: it mirrors the PC's state and turns every
 * transport call into a companion command. Gives notification / lock screen / Bluetooth
 * buttons / Wear controls, and (via a REMOTE [DeviceInfo]) hardware volume keys → PC volume.
 */
@UnstableApi
class RemotePlayer(private val repo: PlayerRepository) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var snapshot: PlaybackState? = null

    /** Must be called on the main thread. */
    fun update(s: PlaybackState?) {
        snapshot = s
        invalidateState()
    }

    override fun getState(): State {
        val s = snapshot
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_SET_SHUFFLE_MODE,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_GET_DEVICE_VOLUME,
            COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setDeviceInfo(DEVICE_INFO)

        if (s?.track == null) {
            return builder.setPlaybackState(STATE_IDLE).setPlaylist(emptyList()).build()
        }

        val items = if (s.queue.isNotEmpty()) {
            s.queue.mapIndexed { i, e ->
                val isCurrent = i == s.selectedIndex
                MediaItemData.Builder("$i:${e.videoId}")
                    .setMediaItem(
                        MediaItem.Builder().setMediaId(e.videoId).setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(if (isCurrent) s.track.title else e.title)
                                .setArtist(if (isCurrent) s.track.artist else e.artist)
                                .setAlbumTitle(if (isCurrent) s.track.album else null)
                                .setArtworkUri((if (isCurrent) s.track.artworkUrl else e.artworkUrl)?.let(Uri::parse))
                                .build(),
                        ).build(),
                    )
                    .setDurationUs(if (isCurrent) (s.track.durationSec * 1_000_000).toLong() else C.TIME_UNSET)
                    .setIsSeekable(isCurrent && !s.track.isLive)
                    .build()
            }
        } else {
            listOf(
                MediaItemData.Builder(s.track.videoId)
                    .setMediaItem(
                        MediaItem.Builder().setMediaId(s.track.videoId).setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(s.track.title)
                                .setArtist(s.track.artist)
                                .setAlbumTitle(s.track.album)
                                .setArtworkUri(s.track.artworkUrl?.let(Uri::parse))
                                .build(),
                        ).build(),
                    )
                    .setDurationUs((s.track.durationSec * 1_000_000).toLong())
                    .setIsSeekable(!s.track.isLive)
                    .build(),
            )
        }
        val index = s.selectedIndex.coerceIn(0, items.lastIndex)
        val playing = s.trackState == TrackState.Playing
        val positionMs = (s.progressAt(SystemClock.elapsedRealtime()) * 1000).toLong()

        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setPlaybackState(if (s.trackState == TrackState.Buffering) STATE_BUFFERING else STATE_READY)
            .setPlayWhenReady(playing || s.trackState == TrackState.Buffering, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(
                if (playing) PositionSupplier.getExtrapolating(positionMs, 1f) else PositionSupplier.getConstant(positionMs),
            )
            .setRepeatMode(
                when (s.repeat) {
                    RepeatMode.All -> REPEAT_MODE_ALL
                    RepeatMode.One -> REPEAT_MODE_ONE
                    else -> REPEAT_MODE_OFF
                },
            )
            .setShuffleModeEnabled(s.shuffle)
            .setDeviceVolume(s.volume)
            .setIsDeviceMuted(s.muted)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        repo.send(if (playWhenReady) Command.Play else Command.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val s = snapshot
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> repo.send(Command.Next)
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> repo.send(Command.Previous)
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD ->
                repo.send(Command.SeekTo(positionMs.coerceAtLeast(0) / 1000.0))
            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val id = s?.queue?.getOrNull(mediaItemIndex)?.videoId
                if (id != null) repo.queueCommandFor(id, mediaItemIndex) { Command.PlayQueueIndex(it) }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repo.send(
            Command.SetRepeat(
                when (repeatMode) {
                    REPEAT_MODE_ALL -> RepeatMode.All
                    REPEAT_MODE_ONE -> RepeatMode.One
                    else -> RepeatMode.None
                },
            ),
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != snapshot?.shuffle) repo.send(Command.ToggleShuffle)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        repo.send(Command.SetVolume(deviceVolume.coerceIn(0, 100)))
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        repo.send(Command.SetVolume(((snapshot?.volume ?: 0) + VOLUME_STEP).coerceAtMost(100)))
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        repo.send(Command.SetVolume(((snapshot?.volume ?: 0) - VOLUME_STEP).coerceAtLeast(0)))
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val VOLUME_STEP = 5
        private val DEVICE_INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()
    }
}
