package dev.thathunky.meteora.ui.update

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MLinearLoading
import dev.thathunky.meteora.ui.components.MProgress
import dev.thathunky.meteora.ui.components.MSurface
import dev.thathunky.meteora.ui.components.Tone
import dev.thathunky.meteora.ui.components.surfaceContentColor
import dev.thathunky.meteora.ui.settings.SettingsGroupTitle
import dev.thathunky.meteora.ui.settings.SettingsNote
import dev.thathunky.meteora.ui.settings.SettingsSwitchRow
import dev.thathunky.meteora.update.Failure
import dev.thathunky.meteora.update.Release
import dev.thathunky.meteora.update.UpdateState
import dev.thathunky.meteora.update.Updater
import dev.thathunky.meteora.update.release
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(val updater: Updater) : ViewModel()

/**
 * Home's update card: one line in `HomeScreen`. Draws nothing unless there is a release on
 * offer (or one being fetched or installed). Showing it is also when the once-a-day check runs,
 * so nothing outside `ui/update` had to change for "check when the app is opened".
 */
@Composable
fun UpdateCard(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    var canInstall by remember { mutableStateOf(vm.updater.canInstallPackages()) }
    LifecycleResumeEffect(Unit) {
        // Back from "Allow from this source", or the app opened again on another day.
        canInstall = vm.updater.canInstallPackages()
        vm.updater.checkIfDue()
        onPauseOrDispose { }
    }
    if (state.release == null) return
    MSurface(tone = Tone.Primary, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateContent(state, vm.updater, canInstall, surfaceContentColor(Tone.Primary))
        }
    }
}

/** Settings → About: the check button and its result, the offer itself, and the two switches. One line in `AboutSettingsScreen`. */
@Composable
fun UpdateSettingsSection(vm: UpdateViewModel = hiltViewModel()) {
    val u = vm.updater
    val state by u.state.collectAsStateWithLifecycle()
    val auto by u.store.auto.collectAsStateWithLifecycle()
    val pre by u.store.includePreReleases.collectAsStateWithLifecycle()
    val last by u.store.lastCheckAt.collectAsStateWithLifecycle()
    val skipped by u.store.skipped.collectAsStateWithLifecycle()
    var canInstall by remember { mutableStateOf(u.canInstallPackages()) }
    LifecycleResumeEffect(Unit) {
        canInstall = u.canInstallPackages()
        onPauseOrDispose { }
    }

    SettingsGroupTitle(stringResource(R.string.update_section))
    Text(
        last?.let { stringResource(R.string.update_last_check, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()) }
            ?: stringResource(R.string.update_never_checked),
        style = MaterialTheme.typography.bodyMedium,
    )
    when (val s = state) {
        UpdateState.Checking -> MLinearLoading(Modifier.fillMaxWidth())
        UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium)
        is UpdateState.Failed -> if (s.release == null) Text(failureText(s.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        else -> {}
    }
    if (state.release != null) {
        MSurface(tone = Tone.Primary, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateContent(state, u, canInstall, surfaceContentColor(Tone.Primary))
            }
        }
    } else {
        MButton(
            stringResource(if (state == UpdateState.Checking) R.string.update_checking else R.string.update_check),
            { u.check(manual = true) },
            kind = ButtonKind.Tonal,
            enabled = state != UpdateState.Checking,
        )
    }
    SettingsSwitchRow(stringResource(R.string.update_auto), auto, u.store::setAuto, supporting = stringResource(R.string.update_auto_note))
    SettingsSwitchRow(stringResource(R.string.update_pre), pre, u.store::setIncludePreReleases, supporting = stringResource(R.string.update_pre_note))
    skipped?.let { v ->
        SettingsNote(stringResource(R.string.update_skipped, v.display()))
        MButton(stringResource(R.string.update_unskip), { u.store.setSkipped(null) }, kind = ButtonKind.Outlined)
    }
}

/** The offer and what follows it, inside a card. */
@Composable
private fun UpdateContent(state: UpdateState, u: Updater, canInstall: Boolean, color: androidx.compose.ui.graphics.Color) {
    val r = state.release ?: return
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var notes by remember { mutableStateOf(false) }
    val title = when (state) {
        is UpdateState.Ready, is UpdateState.Installing -> stringResource(R.string.update_ready, r.version.display())
        is UpdateState.Failed -> stringResource(R.string.update_failed)
        else -> stringResource(R.string.update_available, r.version.display())
    }
    Text(title, style = MaterialTheme.typography.titleMedium, color = color)
    when (state) {
        is UpdateState.Available -> {
            Text(stringResource(R.string.update_size, Formatter.formatShortFileSize(context, r.apkSize)), style = MaterialTheme.typography.bodyMedium, color = color)
            if (!canInstall) Text(stringResource(R.string.update_source_note), style = MaterialTheme.typography.bodySmall, color = color)
        }
        is UpdateState.Downloading -> {
            val pct = state.percent
            MProgress(progress = { pct / 100f }, amplitude = { 0f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_downloading, pct), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        is UpdateState.Verifying -> {
            MLinearLoading(Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_verifying), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        is UpdateState.Ready -> if (!canInstall) Text(stringResource(R.string.update_source_note), style = MaterialTheme.typography.bodySmall, color = color)
        is UpdateState.Installing -> {
            MLinearLoading(Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        is UpdateState.Failed -> {
            Text(failureText(state.reason), style = MaterialTheme.typography.bodyMedium, color = color)
            state.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        }
        else -> {}
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            is UpdateState.Available -> MButton(stringResource(R.string.update_download), u::download)
            is UpdateState.Downloading -> MButton(stringResource(R.string.action_cancel), u::cancelDownload, kind = ButtonKind.Outlined)
            is UpdateState.Ready -> MButton(stringResource(R.string.update_install), u::install)
            is UpdateState.Failed -> {
                if (state.reason.needsManualInstall) MButton(stringResource(R.string.update_open_page), { uri.openUri(r.pageUrl) })
                else if (state.reason.retryable) MButton(stringResource(R.string.action_try_again), u::download)
            }
            else -> {}
        }
        if (r.notes.isNotBlank()) MButton(stringResource(R.string.update_notes), { notes = true }, kind = ButtonKind.Text)
        if (state !is UpdateState.Installing && state !is UpdateState.Verifying) MButton(stringResource(R.string.update_skip), u::skip, kind = ButtonKind.Text)
    }
    if (notes) NotesDialog(r) { notes = false }
}

@Composable
private fun NotesDialog(r: Release, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.version.display()) },
        text = {
            Text(
                plainNotes(r.notes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.update_close)) } },
    )
}

@Composable
private fun failureText(f: Failure): String = stringResource(
    when (f) {
        Failure.Network -> R.string.update_fail_network
        Failure.Busy -> R.string.update_fail_busy
        Failure.Storage -> R.string.update_fail_storage
        Failure.Size -> R.string.update_fail_size
        Failure.Digest -> R.string.update_fail_digest
        Failure.NotAnApk -> R.string.update_fail_not_apk
        Failure.WrongPackage -> R.string.update_fail_package
        Failure.NotNewer -> R.string.update_fail_not_newer
        Failure.DifferentSigner -> R.string.update_fail_signer
        Failure.InstallConflict -> R.string.update_fail_conflict
        Failure.InstallStorage -> R.string.update_fail_install_storage
        Failure.InstallBlocked -> R.string.update_fail_blocked
        Failure.InstallIncompatible -> R.string.update_fail_incompatible
        Failure.InstallInvalid -> R.string.update_fail_invalid
        Failure.InstallOther -> R.string.update_fail_install
    },
)

/** Release notes are Markdown; shown as plain text: no emphasis marks, headings or link targets, bullets as "•". */
internal fun plainNotes(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { line ->
    line
        .replace(LINK, "$1")
        .replace("**", "").replace("__", "").replace("`", "")
        .replace(HEADING, "")
        .replace(BULLET, "$1• ")
        .trimEnd()
}.trim()

private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val HEADING = Regex("""^#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*+]\s+""")
