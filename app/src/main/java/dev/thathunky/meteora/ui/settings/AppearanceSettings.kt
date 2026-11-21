package dev.thathunky.meteora.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.theme.AllThemes
import dev.thathunky.meteora.ui.theme.AppTheme
import dev.thathunky.meteora.ui.theme.ArtworkShape
import dev.thathunky.meteora.ui.theme.LocalAlbumSeed
import dev.thathunky.meteora.ui.theme.ThemeMode
import dev.thathunky.meteora.ui.theme.ThemeStore
import dev.thathunky.meteora.ui.theme.isDark
import dev.thathunky.meteora.ui.theme.schemeFor
import dev.thathunky.meteora.ui.theme.themeById
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Passes [ThemeStore] through; the activity and Settings each get their own instance of this. */
@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: ThemeStore) : ViewModel() {
    val themeId: StateFlow<String> = store.themeId
    val mode: StateFlow<ThemeMode> = store.mode
    val pureBlack: StateFlow<Boolean> = store.pureBlack
    val artworkShape: StateFlow<ArtworkShape> = store.artworkShape

    fun setTheme(id: String) = store.setThemeId(id)
    fun setMode(m: ThemeMode) = store.setMode(m)
    fun setPureBlack(on: Boolean) = store.setPureBlack(on)
    fun setArtworkShape(s: ArtworkShape) = store.setArtworkShape(s)
}

@Composable
fun AppearanceSection(vm: AppearanceViewModel = hiltViewModel()) {
    val themeId by vm.themeId.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val pureBlack by vm.pureBlack.collectAsStateWithLifecycle()
    val artwork by vm.artworkShape.collectAsStateWithLifecycle()
    val theme = themeById(themeId)
    val systemDark = isSystemInDarkTheme()
    val fixed = theme is AppTheme.Fixed

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(AllThemes, key = { it.id }) { t ->
                ThemeCard(t, selected = t.id == theme.id, mode = mode, pureBlack = pureBlack, systemDark = systemDark) {
                    vm.setTheme(t.id)
                }
            }
        }
        Text(stringResource(R.string.appearance_mode), style = MaterialTheme.typography.labelLarge)
        // A fixed theme is the mode it was made for, so the choice has no effect then.
        Segmented(
            options = listOf(
                ThemeMode.System to stringResource(R.string.mode_system),
                ThemeMode.Light to stringResource(R.string.mode_light),
                ThemeMode.Dark to stringResource(R.string.mode_dark),
            ),
            selected = mode,
            onSelect = vm::setMode,
            enabled = !fixed,
        )
        val pureBlackEnabled = !fixed && isDark(theme, mode, systemDark)
        ListItem(
            headlineContent = { Text(stringResource(R.string.appearance_pure_black)) },
            trailingContent = { Switch(checked = pureBlack, onCheckedChange = null, enabled = pureBlackEnabled) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.toggleable(
                value = pureBlack,
                enabled = pureBlackEnabled,
                role = Role.Switch,
                onValueChange = vm::setPureBlack,
            ),
        )
        Text(stringResource(R.string.appearance_artwork), style = MaterialTheme.typography.labelLarge)
        Segmented(
            options = listOf(
                ArtworkShape.Rounded to stringResource(R.string.artwork_rounded),
                ArtworkShape.Scalloped to stringResource(R.string.artwork_scalloped),
            ),
            selected = artwork,
            onSelect = vm::setArtworkShape,
        )
    }
}

/** The theme's own background, three accents and a line of its text, with the name below. */
@Composable
private fun ThemeCard(t: AppTheme, selected: Boolean, mode: ThemeMode, pureBlack: Boolean, systemDark: Boolean, onClick: () -> Unit) {
    // Dynamic themes preview as they would look right now, album art included.
    val preview = schemeFor(t, isDark(t, mode, systemDark), pureBlack, LocalAlbumSeed.current)
    val name = when (t) {
        is AppTheme.Fixed -> t.name
        is AppTheme.Dynamic -> t.nameRes?.let { stringResource(it) } ?: "Meteora"
    }
    val author = (t as? AppTheme.Fixed)?.author?.let { stringResource(R.string.theme_by, it) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(112.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(name, author).joinToString(", ") }
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(shape)
                .background(preview.background)
                .border(
                    if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape,
                )
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(preview.primary, preview.secondary, preview.tertiary).forEach {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(it))
                    }
                }
                Text("Aa", color = preview.onBackground, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = preview.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(preview.primary).padding(2.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
        if (author != null) {
            Text(author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A connected button group, in the pattern of QualitySelector in MeteoraShell.kt. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { if (it) onSelect(value) },
                enabled = enabled,
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
