package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId

/**
 * A two-line list row at the look's height. Expressive is the Material ListItem as before; the
 * others are a plain row, because ListItem cannot go under 56 dp (Terminal rows are 48 dp).
 * [highlight] is the row's background. Terminal draws no fills and marks a [marked] row (the queue's current track) with > instead.
 */
@Composable
fun MRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    highlight: Color? = null,
    marked: Boolean = false,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    if (look.id == LookId.Expressive) {
        ListItem(
            headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            leadingContent = leading,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = highlight ?: scheme.surface),
            modifier = modifier,
        )
        return
    }
    val soft = look.id == LookId.Soft
    val terminal = look.id == LookId.Terminal
    // Soft draws each row as its own rounded tonal card; the others sit on the page.
    val back = when {
        terminal -> Modifier
        soft -> Modifier
            .padding(horizontal = look.screenPadding / 2, vertical = look.rowGap / 2)
            .clip(RoundedCornerShape(look.cornerMedium))
            .background(highlight ?: scheme.surfaceContainerLow)
        highlight != null -> Modifier.background(highlight)
        else -> Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(back)
            .heightIn(min = look.rowHeight)
            .padding(horizontal = if (soft) look.screenPadding / 2 else look.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(if (terminal) 10.dp else 14.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                (if (terminal && marked) "> " else "") + headline,
                style = MaterialTheme.typography.bodyLarge,
                color = if (terminal && marked) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/** A row of mutually exclusive options (mode, quality, target), drawn the look's way. */
@Composable
fun <T> SegmentedChoice(
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    when (look.id) {
        LookId.Expressive, LookId.Flat, LookId.Soft -> {
            val gap = when (look.id) {
                LookId.Expressive -> ButtonGroupDefaults.ConnectedSpaceBetween
                LookId.Flat -> 4.dp
                else -> 8.dp
            }
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                options.forEachIndexed { i, o ->
                    val shapes = when (look.id) {
                        LookId.Expressive -> when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        }
                        LookId.Flat -> RoundedCornerShape(8.dp).let { ToggleButtonShapes(it, it, it) }
                        else -> ToggleButtonShapes(CircleShape, CircleShape, CircleShape)
                    }
                    ToggleButton(
                        checked = selected == o.value,
                        onCheckedChange = { if (it) onSelect(o.value) },
                        enabled = enabled,
                        shapes = shapes,
                        modifier = Modifier.weight(1f).then(if (look.id == LookId.Soft) Modifier.heightIn(min = 52.dp) else Modifier),
                    ) {
                        if (o.icon != null) {
                            Icon(o.icon, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(o.label, maxLines = 1)
                    }
                }
            }
        }
        LookId.Terminal -> Row(
            modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).border(look.outline, scheme.outline, RectangleShape),
        ) {
            options.forEach { o ->
                val on = selected == o.value
                Box(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(o.value) }
                        .then(if (on) Modifier.background(scheme.onSurface) else Modifier)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (if (on) "* " else "") + lookLabel(o.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.surface else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LookId.Retro -> Row(
            modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).padding(end = look.hardShadow, bottom = look.hardShadow),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { o ->
                val on = selected == o.value
                Box(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        // The chosen one is pushed in: no shadow, shifted into it.
                        .retroBox(RoundedCornerShape(look.cornerSmall), if (on) scheme.primary else scheme.surface, pressed = on)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(o.value) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        lookLabel(o.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onPrimary else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
