package dev.thathunky.meteora.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

/** Settings → Playback: "Find synced lyrics on LRCLIB", off by default, with what it sends. */
@Composable
fun LrcLibSetting(vm: LrcLibSettingViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.lrclib_title)) },
        supportingContent = { Text(stringResource(R.string.lrclib_note)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = vm::set),
    )
}
