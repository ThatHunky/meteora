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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val player: PlayerRepository,
    private val store: PairedPcStore,
) : ViewModel() {
    val connection: StateFlow<ConnectionState> = player.connection
    val playback: StateFlow<PlaybackState?> = player.state
    val pairedPc: StateFlow<PairedPc?> = store.pc.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Called from ON_START / ON_STOP of the activity. */
    fun onForeground() = player.acquire()
    fun onBackground() = player.release()

    fun send(command: Command) = player.send(command)

    fun forgetPc() {
        viewModelScope.launch {
            store.clear()
            player.reconnect()
        }
    }
}
