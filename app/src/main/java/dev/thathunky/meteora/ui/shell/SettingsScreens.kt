package dev.thathunky.meteora.ui.shell

import dev.thathunky.meteora.ui.settings.VideoQualitySection
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
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.PageTitle
import dev.thathunky.meteora.ui.components.SegmentedChoice
import dev.thathunky.meteora.ui.components.screenTitleStyle
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.settings.SettingsCategory
import dev.thathunky.meteora.ui.settings.currentAppearanceSummary
import dev.thathunky.meteora.ui.settings.SettingsGroupTitle
import androidx.compose.ui.text.style.TextOverflow
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
fun SettingsScreen(vm: ShellViewModel, open: (Route) -> Unit, bottomPadding: Dp) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val signedIn by vm.ytmSignedIn.collectAsStateWithLifecycle()
    val appearance = currentAppearanceSummary()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).padding(bottom = bottomPadding)) {
        PageTitle(stringResource(R.string.nav_settings))
        settingsCategories().forEach { c ->
            val summary = when (c) {
                SettingsCategory.Appearance -> appearance
                SettingsCategory.Playback ->
                    if (target == PlaybackTarget.Phone) stringResource(R.string.target_phone)
                    else pc?.name ?: stringResource(R.string.target_pc)
                SettingsCategory.Account -> stringResource(if (signedIn) R.string.ytm_signed_in else R.string.ytm_signed_out)
                SettingsCategory.Pc -> pc?.name ?: stringResource(R.string.not_connected)
                SettingsCategory.About -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
            }
            MRow(
                headline = stringResource(c.title()),
                supporting = summary,
                leading = { Icon(c.icon(), contentDescription = null) },
                modifier = Modifier.clickable { open(c.route()) },
            )
        }
    }
}

/** A sub-screen: back button and title on top, then scrolling content clear of the mini player. */
@Composable
private fun SettingsPage(title: Int, onBack: () -> Unit, bottomPadding: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack, text = stringResource(R.string.action_back))
            Text(stringResource(title), style = screenTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LocalLook.current.screenPadding).padding(bottom = 16.dp + bottomPadding),
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
        SettingsGroupTitle(stringResource(R.string.play_on), first = true)
        TargetSelector(target, pc?.name, vm::setTarget, Modifier.fillMaxWidth())
        SettingsGroupTitle(stringResource(R.string.quality_title))
        Text(stringResource(R.string.quality_wifi), style = MaterialTheme.typography.labelLarge)
        QualitySelector(qualityWifi, vm::setQualityWifi, Modifier.fillMaxWidth())
        Text(stringResource(R.string.quality_mobile), style = MaterialTheme.typography.labelLarge)
        QualitySelector(qualityMobile, vm::setQualityMobile, Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VideoQualitySection()
        dev.thathunky.meteora.ui.downloads.DownloadsSettingsSection()
        ListItem(
            headlineContent = { Text(stringResource(R.string.autoplay_title)) },
            supportingContent = { Text(stringResource(R.string.autoplay_note)) },
            trailingContent = { Switch(checked = autoplay, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = autoplay, role = Role.Switch, onValueChange = vm::setAutoplay),
        )
        dev.thathunky.meteora.ui.settings.LrcLibSetting()
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
fun AccountSettingsScreen(vm: ShellViewModel, open: (Route) -> Unit, onBack: () -> Unit, bottomPadding: Dp) {
    val ytmSignedIn by vm.ytmSignedIn.collectAsStateWithLifecycle()
    SettingsPage(R.string.ytm_account, onBack, bottomPadding) {
        // The page title already names the account; the state is what this page is about.
        StatusBlock(stringResource(if (ytmSignedIn) R.string.ytm_signed_in else R.string.ytm_signed_out))
        if (ytmSignedIn) MButton(stringResource(R.string.signout_action), vm::signOutYtm, kind = ButtonKind.Outlined)
        else MButton(stringResource(R.string.signin_action), { open(SignInRoute) })
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
fun PcSettingsScreen(vm: ShellViewModel, open: (Route) -> Unit, onBack: () -> Unit, bottomPadding: Dp) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    SettingsPage(R.string.settings_pc, onBack, bottomPadding) {
        StatusBlock(pc?.name ?: stringResource(R.string.not_connected), pc?.preferredOrder()?.joinToString(" · "))
        MButton(stringResource(R.string.settings_repair), { open(PairingRoute) })
        MButton(stringResource(R.string.settings_forget), vm::forgetPc, kind = ButtonKind.Outlined)
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
        StatusBlock(stringResource(R.string.app_name),stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

/**
 * The current state at the top of a sub-screen (signed in, the paired PC), aligned with the
 * rest of the page; a ListItem there added its own 16 dp inset on top of the page's.
 */
@Composable
private fun StatusBlock(headline: String, supporting: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        if (!supporting.isNullOrBlank()) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Low / Normal / High, in the official app's order. */
@Composable
private fun QualitySelector(
    quality: StreamQuality,
    onQuality: (StreamQuality) -> Unit,
    modifier: Modifier = Modifier,
) = SegmentedChoice(
    options = listOf(
        Choice(StreamQuality.Low, stringResource(R.string.quality_low)),
        Choice(StreamQuality.Normal, stringResource(R.string.quality_normal)),
        Choice(StreamQuality.High, stringResource(R.string.quality_high)),
    ),
    selected = quality,
    onSelect = onQuality,
    modifier = modifier,
)

/** PC / This phone. */
@Composable
fun TargetSelector(
    target: PlaybackTarget,
    pcName: String?,
    onTarget: (PlaybackTarget) -> Unit,
    modifier: Modifier = Modifier,
) = SegmentedChoice(
    options = listOf(
        Choice(PlaybackTarget.Pc, pcName ?: stringResource(R.string.target_pc), Icons.Outlined.Computer),
        Choice(PlaybackTarget.Phone, stringResource(R.string.target_phone), Icons.Outlined.PhoneAndroid),
    ),
    selected = target,
    onSelect = onTarget,
    modifier = modifier,
)