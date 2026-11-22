package dev.thathunky.meteora.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.lyrics.LrcLibStore
import javax.inject.Inject

@HiltViewModel
class LrcLibSettingViewModel @Inject constructor(private val store: LrcLibStore) : ViewModel() {
    val enabled = store.enabled
    fun set(on: Boolean) = store.set(on)
}

/**
 * Settings → Playback: "Find synced lyrics on LRCLIB", off by default, with what it sends. The
 * page's own switch row, so it lines up with the text above it (a ListItem adds its own inset).
 */
@Composable
fun LrcLibSetting(vm: LrcLibSettingViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsStateWithLifecycle()
    SettingsSwitchRow(stringResource(R.string.lrclib_title), on, vm::set, supporting = stringResource(R.string.lrclib_note))
}
