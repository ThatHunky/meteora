package dev.thathunky.meteora.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.data.PairedPc
import dev.thathunky.meteora.data.PairedPcStore
import dev.thathunky.meteora.data.PlayerRepository
import dev.thathunky.meteora.domain.PlaybackState
import javax.inject.Inject
import androidx.compose.ui.graphics.Color
import dev.thathunky.meteora.ui.theme.AlbumSeedExtractor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val router: dev.thathunky.meteora.data.PlaybackRouter,
    private val store: PairedPcStore,
    seeds: AlbumSeedExtractor,
    private val ytmAuth: dev.thathunky.meteora.ytm.YtmAuthStore,
    private val quality: dev.thathunky.meteora.stream.StreamQualityStore,
) : ViewModel() {
    val qualityWifi: StateFlow<dev.thathunky.meteora.stream.StreamQuality> = quality.wifi
    val qualityMobile: StateFlow<dev.thathunky.meteora.stream.StreamQuality> = quality.mobile
    fun setQualityWifi(q: dev.thathunky.meteora.stream.StreamQuality) = quality.setWifi(q)
    fun setQualityMobile(q: dev.thathunky.meteora.stream.StreamQuality) = quality.setMobile(q)

    val ytmSignedIn: StateFlow<Boolean> = ytmAuth.signedIn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun signOutYtm() {
        viewModelScope.launch { ytmAuth.signOut() }
    }
    private val player: PlayerRepository = router.pc
    val connection: StateFlow<ConnectionState> = router.connection
    val target: StateFlow<dev.thathunky.meteora.data.PlaybackTarget> = router.target
    fun setTarget(t: dev.thathunky.meteora.data.PlaybackTarget) = router.setTarget(t)
    val playback: StateFlow<PlaybackState?> = router.state

    /**
     * Whether anything is loaded. The shell reads this instead of [playback], which changes
     * several times a second while the PC plays and would recompose everything that reads it.
     */
    val hasTrack: StateFlow<Boolean> = router.state
        .map { it?.track != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, router.state.value?.track != null)

    val pairedPc: StateFlow<PairedPc?> = store.pc.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Theme seed from the current album art; keeps the last one while a track change settles. */
    val albumSeed: StateFlow<Color?> = router.state
        .map { it?.track?.artworkUrl }
        .filterNotNull()
        .distinctUntilChanged()
        .mapLatest { seeds.seedFor(it) }
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Called from ON_START / ON_STOP of the activity. */
    fun onForeground() = player.acquire()
    fun onBackground() = player.release()

    fun send(command: Command) = router.send(command)

    fun reconnect() = player.reconnect()

    fun forgetPc() {
        viewModelScope.launch {
            store.clear()
            player.reconnect()
        }
    }
}
