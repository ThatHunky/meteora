package dev.thathunky.meteora.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.stream.StreamQuality
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.SegmentedChoice

/**
 * Settings → Playback, after the video quality: download quality, Wi-Fi only, space used and
 * free, "Remove all downloads", and smart downloads. One line in `PlaybackSettingsScreen`.
 */
@Composable
fun DownloadsSettingsSection(vm: DownloadsViewModel = hiltViewModel()) {
    val d = vm.downloads
    val s = d.settings
    val context = LocalContext.current
    val quality by s.quality.collectAsStateWithLifecycle()
    val wifiOnly by s.wifiOnly.collectAsStateWithLifecycle()
    val songs by d.index.songs.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    Text(stringResource(R.string.settings_downloads), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.download_quality), style = MaterialTheme.typography.labelLarge)
    SegmentedChoice(
        listOf(
            Choice(StreamQuality.Normal, stringResource(R.string.quality_normal)),
            Choice(StreamQuality.High, stringResource(R.string.quality_high)),
        ),
        quality,
        s::setQuality,
        Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(if (quality == StreamQuality.High) R.string.download_quality_high else R.string.download_quality_normal),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.download_wifi_only)) },
        trailingContent = { Switch(checked = wifiOnly, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = wifiOnly, role = Role.Switch, onValueChange = {
            s.setWifiOnly(it)
            // Queued work picks up the new network rule.
            if (d.index.queued().isNotEmpty()) d.schedule()
        }),
    )
    val used = remember(songs) { d.usedBytes() }
    val free = remember(songs) { d.freeBytes() }
    Text(
        stringResource(R.string.download_space, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, free)),
        style = MaterialTheme.typography.bodyMedium,
    )
    MButton(stringResource(R.string.download_remove_all), { confirm = true }, kind = ButtonKind.Tonal, enabled = songs.isNotEmpty())
    SmartDownloadsSettings(vm)
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.download_remove_all)) },
            text = { Text(stringResource(R.string.download_remove_all_confirm)) },
            confirmButton = { TextButton({ confirm = false; d.removeAll() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Filled in by smart downloads (D5). */
@Composable
private fun SmartDownloadsSettings(vm: DownloadsViewModel) = Unit
