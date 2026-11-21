package dev.thathunky.meteora.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.thathunky.meteora.BuildConfig
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.PlaybackTarget
import dev.thathunky.meteora.stream.StreamQuality
import dev.thathunky.meteora.ui.settings.AppearanceSection
import dev.thathunky.meteora.ui.settings.SettingsCategory
import dev.thathunky.meteora.ui.settings.currentThemeName
import dev.thathunky.meteora.ui.settings.settingsCategories

private fun SettingsCategory.route(): Route = when (this) {
    SettingsCategory.Appearance -> SettingsAppearanceRoute
    SettingsCategory.Playback -> SettingsPlaybackRoute
    SettingsCategory.Account -> SettingsAccountRoute
    SettingsCategory.Pc -> SettingsPcRoute
    SettingsCategory.About -> SettingsAboutRoute
}

private fun SettingsCategory.title(): Int = when (this) {
    SettingsCategory.Appearance -> R.string.appearance_title
    SettingsCategory.Playback -> R.string.settings_playback
    SettingsCategory.Account -> R.string.ytm_account
    SettingsCategory.Pc -> R.string.settings_pc
    SettingsCategory.About -> R.string.settings_about
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.Appearance -> Icons.Outlined.Palette
    SettingsCategory.Playback -> Icons.Outlined.PlayCircle
    SettingsCategory.Account -> Icons.Outlined.AccountCircle
    SettingsCategory.Pc -> Icons.Outlined.Computer
    SettingsCategory.About -> Icons.Outlined.Info
}

/** The top-level list: one row per category with a line about its current state. */
@androidx.media3.common.util.UnstableApi
@Composable
fun SettingsScreen(vm: ShellViewModel, backStack: NavBackStack<NavKey>, bottomPadding: Dp) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val signedIn by vm.ytmSignedIn.collectAsStateWithLifecycle()
    val themeName = currentThemeName()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).padding(bottom = bottomPadding)) {
        Text(
            stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.displaySmallEmphasized,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        settingsCategories().forEach { c ->
            val summary = when (c) {
                SettingsCategory.Appearance -> themeName
                SettingsCategory.Playback ->
                    if (target == PlaybackTarget.Phone) stringResource(R.string.target_phone)
                    else pc?.name ?: stringResource(R.string.target_pc)
                SettingsCategory.Account -> stringResource(if (signedIn) R.string.ytm_signed_in else R.string.ytm_signed_out)
                SettingsCategory.Pc -> pc?.name ?: stringResource(R.string.not_connected)
                SettingsCategory.About -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
            }
            ListItem(
                headlineContent = { Text(stringResource(c.title())) },
                supportingContent = { Text(summary, maxLines = 1) },
                leadingContent = { Icon(c.icon(), contentDescription = null) },
                modifier = Modifier.clickable { backStack.add(c.route()) },
            )
        }
    }
}

/** A sub-screen: back button and title on top, then scrolling content clear of the mini player. */
@Composable
private fun SettingsPage(title: Int, onBack: () -> Unit, bottomPadding: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
            Text(stringResource(title), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, bottomPadding: Dp) =
    SettingsPage(R.string.appearance_title, onBack, bottomPadding) { AppearanceSection() }

@androidx.media3.common.util.UnstableApi
@Composable
fun PlaybackSettingsScreen(vm: ShellViewModel, onBack: () -> Unit, bottomPadding: Dp) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val qualityWifi by vm.qualityWifi.collectAsStateWithLifecycle()
    val qualityMobile by vm.qualityMobile.collectAsStateWithLifecycle()
    val autoplay by vm.autoplay.collectAsStateWithLifecycle()
    SettingsPage(R.string.settings_playback, onBack, bottomPadding) {
        ListItem(headlineContent = { Text(stringResource(R.string.play_on)) })
        TargetSelector(target, pc?.name, vm::setTarget, Modifier.fillMaxWidth())
        Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.quality_wifi), style = MaterialTheme.typography.labelLarge)
        QualitySelector(qualityWifi, vm::setQualityWifi, Modifier.fillMaxWidth())
        Text(stringResource(R.string.quality_mobile), style = MaterialTheme.typography.labelLarge)
        QualitySelector(qualityMobile, vm::setQualityMobile, Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.autoplay_title)) },
            supportingContent = { Text(stringResource(R.string.autoplay_note)) },
            trailingContent = { Switch(checked = autoplay, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = autoplay, role = Role.Switch, onValueChange = vm::setAutoplay),
        )
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
fun AccountSettingsScreen(vm: ShellViewModel, backStack: NavBackStack<NavKey>, onBack: () -> Unit, bottomPadding: Dp) {
    val ytmSignedIn by vm.ytmSignedIn.collectAsStateWithLifecycle()
    SettingsPage(R.string.ytm_account, onBack, bottomPadding) {
        ListItem(
            headlineContent = { Text(stringResource(if (ytmSignedIn) R.string.ytm_signed_in else R.string.ytm_signed_out)) },
            overlineContent = { Text(stringResource(R.string.ytm_account)) },
        )
        if (ytmSignedIn) OutlinedButton(onClick = vm::signOutYtm) { Text(stringResource(R.string.signout_action)) }
        else Button(onClick = { backStack.add(SignInRoute) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.signin_action)) }
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
fun PcSettingsScreen(vm: ShellViewModel, backStack: NavBackStack<NavKey>, onBack: () -> Unit, bottomPadding: Dp) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    SettingsPage(R.string.settings_pc, onBack, bottomPadding) {
        ListItem(
            headlineContent = { Text(pc?.name ?: stringResource(R.string.not_connected)) },
            supportingContent = { Text(pc?.preferredOrder()?.joinToString(" · ").orEmpty()) },
            overlineContent = { Text(stringResource(R.string.settings_pc)) },
        )
        Button(onClick = { backStack.add(PairingRoute) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.settings_repair))
        }
        OutlinedButton(onClick = vm::forgetPc) { Text(stringResource(R.string.settings_forget)) }
        Text(
            stringResource(R.string.settings_forget_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AboutSettingsScreen(onBack: () -> Unit, bottomPadding: Dp) =
    SettingsPage(R.string.settings_about, onBack, bottomPadding) {
        ListItem(headlineContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) })
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

/** Low / Normal / High, in the official app's order, as a connected button group like [TargetSelector]. */
@Composable
private fun QualitySelector(
    quality: StreamQuality,
    onQuality: (StreamQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        StreamQuality.Low to stringResource(R.string.quality_low),
        StreamQuality.Normal to stringResource(R.string.quality_normal),
        StreamQuality.High to stringResource(R.string.quality_high),
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (q, label) ->
            ToggleButton(
                checked = quality == q,
                onCheckedChange = { if (it) onQuality(q) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** PC / This phone, as a connected button group. */
@Composable
fun TargetSelector(
    target: PlaybackTarget,
    pcName: String?,
    onTarget: (PlaybackTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        PlaybackTarget.Pc to (pcName ?: stringResource(R.string.target_pc)),
        PlaybackTarget.Phone to stringResource(R.string.target_phone),
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (t, label) ->
            ToggleButton(
                checked = target == t,
                onCheckedChange = { if (it) onTarget(t) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (t == PlaybackTarget.Pc) Icons.Outlined.Computer else Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(label, maxLines = 1)
            }
        }
    }
}
