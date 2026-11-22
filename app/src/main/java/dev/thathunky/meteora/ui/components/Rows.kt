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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
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

/**
 * The height one two-line [MRow] takes, gap included, at the current font scale. For containers
 * that must be given a height (the Home quick-picks grid): the look's row height alone is too
 * small when the two lines of text are taller than it (Terminal's 48 dp, or a large font).
 */
@Composable
fun rowSlotHeight(): androidx.compose.ui.unit.Dp {
    val look = LocalLook.current
    val type = MaterialTheme.typography
    val density = androidx.compose.ui.platform.LocalDensity.current
    val text = with(density) { type.bodyLarge.lineHeight.toDp() + type.bodyMedium.lineHeight.toDp() }
    // ListItem pads 8 dp above and below; the plain row pads its text column by 4 dp.
    val padding = if (look.id == LookId.Expressive) 16.dp else 8.dp
    return maxOf(look.rowHeight, text + padding) + look.rowGap
}

data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * A label that shrinks a little on one line before anything is cut ("Як у системі" in a third
 * of the width) and, if even [minFontSize] does not fit, takes two lines once [twoLines] is set:
 * [onOverflow] asks for that. The floor is in sp, so at font scale 1.3 it is 1.3 times larger,
 * which is why shrinking alone cut "* вподобані пісні" in Terminal on a 360 dp phone.
 */
@Composable
internal fun ShrinkLabel(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    minFontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    twoLines: Boolean = false,
    onOverflow: () -> Unit = {},
) {
    Text(
        text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = if (twoLines) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp,
        ),
        onTextLayout = { if (!twoLines && it.hasVisualOverflow) onOverflow() },
    )
}

/** The height every segment takes once one label needs two lines, so the row stays even. */
@Composable
private fun twoLineHeight(style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() * 2 } + 16.dp

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
    val labelStyle = MaterialTheme.typography.labelLarge
    // One label too long even shrunk: every label may take two lines and every segment grows
    // to that height, so the selected (inverted, filled) one is not shorter than its neighbours.
    var twoLines by androidx.compose.runtime.remember(options.map { it.label }) { androidx.compose.runtime.mutableStateOf(false) }
    val tall = if (twoLines) twoLineHeight(labelStyle) else 0.dp
    val askTwoLines = { twoLines = true }
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
                    val checked = selected == o.value
                    val onChecked: (Boolean) -> Unit = { if (it) onSelect(o.value) }
                    val mod = Modifier.weight(1f).heightIn(min = maxOf(tall, if (look.id == LookId.Soft) 52.dp else 0.dp))
                    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                        if (o.icon != null) {
                            Icon(o.icon, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        ShrinkLabel(o.label, labelStyle, twoLines = twoLines, onOverflow = askTwoLines)
                    }
                    if (options.size > 2) {
                        // The default 16 dp a side leaves too little for a label once there are three or more.
                        ToggleButton(checked = checked, onCheckedChange = onChecked, modifier = mod, enabled = enabled, shapes = shapes, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), content = content)
                    } else {
                        ToggleButton(checked = checked, onCheckedChange = onChecked, modifier = mod, enabled = enabled, shapes = shapes, content = content)
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
                        .defaultMinSize(minHeight = maxOf(48.dp, tall))
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(o.value) }
                        .then(if (on) Modifier.background(scheme.onSurface) else Modifier)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ShrinkLabel(
                        (if (on) "* " else "") + lookLabel(o.label),
                        labelStyle,
                        if (on) scheme.surface else scheme.onSurface,
                        twoLines = twoLines,
                        onOverflow = askTwoLines,
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
                        .defaultMinSize(minHeight = maxOf(48.dp, tall))
                        // The chosen one is pushed in: no shadow, shifted into it.
                        .retroBox(RoundedCornerShape(look.cornerSmall), if (on) scheme.primary else scheme.surface, pressed = on)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(o.value) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ShrinkLabel(lookLabel(o.label), labelStyle, if (on) scheme.onPrimary else scheme.onSurface, twoLines = twoLines, onOverflow = askTwoLines)
                }
            }
        }
    }
}
