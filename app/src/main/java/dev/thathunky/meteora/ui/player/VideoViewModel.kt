package dev.thathunky.meteora.ui.player

import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.data.PlaybackRouter
import dev.thathunky.meteora.data.PlaybackTarget
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Song / Video switch and the picture. Its own narrow flows: the playback state changes
 * several times a second, these only when the track or the mode does.
 */
@UnstableApi
@HiltViewModel
class VideoViewModel @Inject constructor(private val router: PlaybackRouter) : ViewModel() {

    private val phone = router.target.map { it == PlaybackTarget.Phone }.distinctUntilChanged()
    private val mode = router.state.map { it?.videoMode == true }.distinctUntilChanged()
    private val hasVideo = router.state.map { it?.hasVideo == true }.distinctUntilChanged()

    val videoMode: StateFlow<Boolean> = mode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Phone target only (YTMDesktop shows the video itself). Stays while on Video, so there is a way back. */
    val showSwitch: StateFlow<Boolean> = combine(phone, mode, hasVideo) { p, m, h -> p && (h || m) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The picture takes the artwork's place. */
    val showVideo: StateFlow<Boolean> = combine(phone, mode, hasVideo) { p, m, h -> p && m && h }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val fullscreen = MutableStateFlow(false)

    fun setVideoMode(on: Boolean) {
        if (!router.isPhone) return
        router.local.setVideoMode(on)
        if (!on) fullscreen.value = false
    }

    /** For the picture's size and first frame. Main thread. */
    val player: Player get() = router.local.player

    fun attach(view: View) = router.local.attachVideo(view)

    fun detach(view: View) = router.local.detachVideo(view)
}
