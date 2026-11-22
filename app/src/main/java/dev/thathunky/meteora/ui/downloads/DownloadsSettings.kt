package dev.thathunky.meteora.ui.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.material3.Slider
import dev.thathunky.meteora.downloads.DownloadSettings
import kotlin.math.roundToInt
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

/** Smart downloads: the switch, the size, "Refresh now", what the last refresh did, and the listening history. */
@Composable
private fun SmartDownloadsSettings(vm: DownloadsViewModel) {
    val s = vm.downloads.settings
    val on by s.smartEnabled.collectAsStateWithLifecycle()
    val size by s.smartSize.collectAsStateWithLifecycle()
    val last by s.lastRefresh.collectAsStateWithLifecycle()
    var dragging by remember(size) { mutableStateOf(size.toFloat()) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.smart_title)) },
        supportingContent = { Text(stringResource(R.string.smart_text, dragging.roundToInt())) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = vm.smart::setEnabled),
    )
    if (!on) return
    Slider(
        value = dragging,
        onValueChange = { dragging = it },
        onValueChangeFinished = { s.setSmartSize(dragging.roundToInt()) },
        valueRange = 0f..DownloadSettings.SMART_MAX.toFloat(),
        steps = DownloadSettings.SMART_MAX / 10 - 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(lastRefreshText(last), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MButton(stringResource(R.string.smart_refresh), vm.smart::refreshNow, kind = ButtonKind.Tonal)
    ListItem(
        headlineContent = { Text(stringResource(R.string.listen_clear)) },
        supportingContent = { Text(stringResource(R.string.listen_clear_text)) },
        modifier = Modifier.clickable { vm.downloads.listens.clear() },
    )
}

@Composable
private fun lastRefreshText(raw: String?): String {
    val parts = raw?.split('|')?.mapNotNull { it.toLongOrNull() }
    if (parts == null || parts.size != 4) return stringResource(R.string.smart_never)
    if (parts[0] < 0) return stringResource(R.string.smart_failed)
    val at = DateUtils.getRelativeTimeSpanString(parts[3], System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.smart_last, parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), at)
}
