package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId

/**
 * One of a set of exclusive chips (Search's filters, Settings' Style row), drawn by the look.
 * Expressive, Flat and Soft keep Material's filter chip, which takes their corner from the theme.
 * Terminal draws a 1 dp box, the selected one inverted, in lower case; Retro an outlined box with
 * a hard shadow, the selected one filled and pushed in. Every form is a 48 dp touch target.
 * [fontFamily] overrides the look's font for the label (the Style row shows each look's own).
 */
@Composable
fun MChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    when (look.id) {
        LookId.Terminal, LookId.Retro -> {
            val terminal = look.id == LookId.Terminal
            val shape = if (terminal) RectangleShape else RoundedCornerShape(look.cornerSmall)
            val box = when {
                terminal && selected -> Modifier.background(scheme.onSurface)
                terminal -> Modifier.border(look.outline, scheme.outline, shape)
                else -> Modifier.retroBox(shape, if (selected) scheme.primary else scheme.surface, pressed = selected)
            }
            val color = when {
                terminal && selected -> scheme.surface
                !terminal && selected -> scheme.onPrimary
                else -> scheme.onSurface
            }
            Box(
                modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.defaultMinSize(minHeight = 32.dp).then(box).padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(lookLabel(label), style = MaterialTheme.typography.labelLarge, color = color, fontFamily = fontFamily, maxLines = 1)
                }
            }
        }
        else -> FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, fontFamily = fontFamily, maxLines = 1) },
            modifier = modifier,
        )
    }
}

/**
 * The switch at the end of a settings row whose whole row toggles ([SettingsSwitchRow]), so it
 * takes no clicks and says nothing itself. Terminal draws `[x]` / `[ ]`; Retro a square track
 * and thumb in an outlined box; the others Material's switch.
 */
@Composable
fun MSwitch(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    when (look.id) {
        LookId.Terminal -> Text(
            if (checked) "[x]" else "[ ]",
            style = MaterialTheme.typography.titleMedium,
            color = if (checked) scheme.primary else scheme.onSurfaceVariant,
            modifier = modifier.alpha(if (enabled) 1f else 0.38f).clearAndSetSemantics { },
        )
        LookId.Retro -> Box(
            modifier
                .alpha(if (enabled) 1f else 0.38f)
                .padding(end = look.hardShadow, bottom = look.hardShadow)
                .size(width = 48.dp, height = 26.dp)
                .retroBox(RoundedCornerShape(look.cornerSmall), if (checked) scheme.primary else scheme.surface)
                .padding(4.dp)
                .clearAndSetSemantics { },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .background(if (checked) scheme.onPrimary else scheme.outline, RoundedCornerShape(look.cornerSmall)),
            )
        }
        else -> Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = modifier)
    }
}
