package dev.thathunky.meteora.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.data.PlaybackRouter
import dev.thathunky.meteora.data.PlaybackTarget
import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.domain.PlaybackState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(private val player: PlaybackRouter) : ViewModel() {

    val state: StateFlow<PlaybackState?> = player.state
    val connection: StateFlow<ConnectionState> = player.connection
    val target: StateFlow<PlaybackTarget> = player.target
    val pcName: StateFlow<String?> = player.pc.connection.let { c ->
        kotlinx.coroutines.flow.combine(c, player.pc.state) { conn, _ -> (conn as? ConnectionState.Connected)?.pcName }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    fun setTarget(t: PlaybackTarget) = player.setTarget(t)

    /** Optimistic seek target: shown until the PC reports a position near it (or 3 s pass). */
    private val _seekOverride = MutableStateFlow<Double?>(null)
    val seekOverride: StateFlow<Double?> = _seekOverride.asStateFlow()

    private val _volumeOverride = MutableStateFlow<Int?>(null)
    val volumeOverride: StateFlow<Int?> = _volumeOverride.asStateFlow()

    private var seekClear: Job? = null
    private var volumeClear: Job? = null

    fun playPause() = player.send(Command.PlayPause)
    fun next() = player.send(Command.Next)
    fun previous() = player.send(Command.Previous)
    fun toggleLike() = player.send(Command.ToggleLike)
    fun toggleDislike() = player.send(Command.ToggleDislike)
    fun toggleShuffle() = player.send(Command.ToggleShuffle)

    fun cycleRepeat() {
        val next = when (state.value?.repeat) {
            RepeatMode.None, RepeatMode.Unknown, null -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.None
        }
        player.send(Command.SetRepeat(next))
    }

    /** Called once when the user lets go of the seek bar. */
    fun seekTo(seconds: Double) {
        _seekOverride.value = seconds
        player.send(Command.SeekTo(seconds.coerceAtLeast(0.0)))
        seekClear?.cancel()
        seekClear = viewModelScope.launch {
            delay(3_000)
            _seekOverride.value = null
        }
    }

    /** Fine to call on every drag frame: the throttler keeps only the latest value. */
    fun setVolume(volume: Int) {
        _volumeOverride.value = volume
        player.send(Command.SetVolume(volume.coerceIn(0, 100)))
        volumeClear?.cancel()
        volumeClear = viewModelScope.launch {
            delay(2_000)
            _volumeOverride.value = null
        }
    }

    fun onPositionReport(progress: Double) {
        val o = _seekOverride.value ?: return
        if (kotlin.math.abs(progress - o) < 2.5) _seekOverride.value = null
    }

    fun playAt(videoId: String, index: Int) =
        player.queueCommandFor(videoId, index) { Command.PlayQueueIndex(it) }

    fun remove(videoId: String, index: Int) =
        player.queueCommandFor(videoId, index) { Command.QueueRemove(it) }

    fun move(videoId: String, from: Int, to: Int) =
        player.queueCommandFor(videoId, from) { Command.QueueMove(it, to) }
}
