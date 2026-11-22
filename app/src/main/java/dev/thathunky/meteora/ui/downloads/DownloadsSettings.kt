package dev.thathunky.meteora.ui.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.downloads.DownloadSettings
import dev.thathunky.meteora.downloads.DownloadState
import dev.thathunky.meteora.stream.StreamQuality
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.SegmentedChoice
import dev.thathunky.meteora.ui.settings.SettingsGroupTitle
import dev.thathunky.meteora.ui.settings.SettingsNote
import dev.thathunky.meteora.ui.settings.SettingsSwitchRow
import kotlin.math.roundToInt

/** The smart downloads slider: at least one step, so "on" always keeps something. */
internal const val SMART_STEP = 10
internal val SMART_RANGE = SMART_STEP.toFloat()..DownloadSettings.SMART_MAX.toFloat()
/** Slider steps between the ends, one per [SMART_STEP]. */
internal val SMART_STEPS = (DownloadSettings.SMART_MAX - SMART_STEP) / SMART_STEP - 1

/**
 * Settings → Playback, after the video quality: download quality, Wi-Fi only, space used and
 * free, "Remove all downloads", and smart downloads. One line in `PlaybackSettingsScreen`.
 * Built from the page's own settings rows, so it lines up with the rest of the page.
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

    SettingsGroupTitle(stringResource(R.string.settings_downloads))
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
    SettingsNote(stringResource(if (quality == StreamQuality.High) R.string.download_quality_high else R.string.download_quality_normal))
    SettingsSwitchRow(
        stringResource(R.string.download_wifi_only),
        wifiOnly,
        {
            s.setWifiOnly(it)
            // Queued work picks up the new network rule.
            if (d.index.queued().isNotEmpty() || d.index.songs.value.values.any { e -> e.state == DownloadState.Downloading }) d.schedule(replace = true)
        },
    )
    val doneCount = remember(songs) { songs.values.count { it.state == DownloadState.Done } }
    val used = remember(songs) { d.usedBytes() }
    val free = remember(songs) { d.freeBytes() }
    Text(
        pluralStringResource(R.plurals.downloads_song_count, doneCount, doneCount) + " · " +
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
    var dragging by remember(size) { mutableStateOf(size.toFloat().coerceIn(SMART_RANGE)) }
    var clearing by remember { mutableStateOf(false) }
    val shown = dragging.roundToInt()
    SettingsSwitchRow(stringResource(R.string.smart_title), on, vm.smart::setEnabled, supporting = stringResource(R.string.smart_text, shown))
    if (on) {
        val sizeLabel = pluralStringResource(R.plurals.downloads_song_count, shown, shown)
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { s.setSmartSize(dragging.roundToInt()) },
            valueRange = SMART_RANGE,
            steps = SMART_STEPS,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = sizeLabel },
        )
        SettingsNote(lastRefreshText(last))
        MButton(stringResource(R.string.smart_refresh), vm.smart::refreshNow, kind = ButtonKind.Tonal)
    }
    // Shown with smart downloads off too: the history is kept either way and is the user's to clear.
    Text(stringResource(R.string.listen_clear), style = MaterialTheme.typography.bodyLarge)
    SettingsNote(stringResource(R.string.listen_clear_text))
    MButton(stringResource(R.string.action_clear), { clearing = true }, kind = ButtonKind.Outlined)
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.listen_clear)) },
            text = { Text(stringResource(R.string.listen_clear_confirm)) },
            confirmButton = { TextButton({ clearing = false; vm.downloads.listens.clear() }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton({ clearing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun lastRefreshText(raw: String?): String {
    val parts = raw?.split('|')?.mapNotNull { it.toLongOrNull() }
    if (parts == null || parts.size != 4) return stringResource(R.string.smart_never)
    if (parts[0] < 0) return stringResource(R.string.smart_failed)
    val at = DateUtils.getRelativeTimeSpanString(parts[3], System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(R.string.smart_last, parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), at)
}
