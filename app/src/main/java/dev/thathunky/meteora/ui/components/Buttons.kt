package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import dev.thathunky.meteora.ui.theme.labelCase

enum class ButtonKind { Filled, Tonal, Outlined, Text }
enum class IconKind { Standard, Filled, Tonal }

/** A label in the look's case, using the app's locale (the app is also in Ukrainian). */
@Composable
fun lookLabel(text: String): String = labelCase(text, LocalLook.current, LocalConfiguration.current.locales[0])

/** Fill, outline and, while not pressed, the hard shadow of a Retro control. Pressed, it sinks into the shadow. */
@Composable
internal fun Modifier.retroBox(shape: Shape, fill: Color, pressed: Boolean = false): Modifier {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val d = look.hardShadow
    return this
        .offset { if (pressed) IntOffset(d.roundToPx(), d.roundToPx()) else IntOffset.Zero }
        .then(if (pressed) Modifier else Modifier.hardShadow(d, shape, scheme.outline))
        .clip(shape)
        .background(fill)
        .border(look.outline, scheme.outline, shape)
}

/** A Terminal control: text in square brackets, inverted when it is the primary action. */
@Composable
private fun TermButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, inverted: Boolean, bracket: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val text = lookLabel(label)
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (inverted) Modifier.background(scheme.onSurface) else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (bracket) "[ $text ]" else text,
            style = MaterialTheme.typography.labelLarge,
            color = if (inverted) scheme.surface else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetroButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, kind: ButtonKind) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(LocalLook.current.cornerSmall)
    val (fill, content) = when (kind) {
        ButtonKind.Filled -> scheme.primary to scheme.onPrimary
        ButtonKind.Tonal -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surface to scheme.onSurface
    }
    Box(
        modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .retroBox(shape, fill, pressed)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(lookLabel(label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** A button with a text label, drawn by the look. Terminal drops the icon and brackets the text. */
@Composable
fun MButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val look = LocalLook.current
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
        }
        Text(lookLabel(text))
    }
    when (look.id) {
        LookId.Terminal -> TermButton(text, onClick, modifier, enabled, inverted = kind == ButtonKind.Filled)
        LookId.Retro -> RetroButton(text, icon, onClick, modifier, enabled, kind)
        LookId.Expressive -> when (kind) {
            ButtonKind.Filled -> Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, content = content)
            ButtonKind.Tonal -> FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, content = content)
            ButtonKind.Outlined -> OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
            ButtonKind.Text -> TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        }
        // Flat: 8 dp, no morph. Soft: fat pills, no morph.
        else -> {
            val shape = if (look.pills) CircleShape else RoundedCornerShape(look.cornerSmall)
            val padding = if (look.id == LookId.Soft) PaddingValues(horizontal = 28.dp, vertical = 14.dp) else ButtonDefaults.ContentPadding
            when (kind) {
                ButtonKind.Filled -> Button(onClick, modifier, enabled, shape = shape, contentPadding = padding, content = content)
                ButtonKind.Tonal -> FilledTonalButton(onClick, modifier, enabled, shape = shape, contentPadding = padding, content = content)
                ButtonKind.Outlined -> OutlinedButton(onClick, modifier, enabled, shape = shape, contentPadding = padding, content = content)
                ButtonKind.Text -> TextButton(onClick, modifier, enabled, shape = shape, content = content)
            }
        }
    }
}

/**
 * An icon button. [text] is the control's text form: Terminal draws it instead of the icon.
 * [contentDescription] labels it for TalkBack in every look.
 */
@Composable
fun MIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IconKind = IconKind.Standard,
    text: String? = null,
    iconSize: Dp? = null,
    enabled: Boolean = true,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val iconContent: @Composable () -> Unit = {
        Icon(icon, contentDescription, if (iconSize != null) Modifier.size(iconSize) else Modifier)
    }
    when (look.id) {
        LookId.Expressive -> when (kind) {
            IconKind.Standard -> IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = iconContent)
            IconKind.Filled -> FilledIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier, enabled = enabled, content = iconContent)
            IconKind.Tonal -> FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier, enabled = enabled, content = iconContent)
        }
        LookId.Terminal -> if (text != null) {
            TermButton(
                text, onClick,
                modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription },
                enabled, inverted = kind == IconKind.Filled,
            )
        } else {
            Box(
                modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .then(
                        when (kind) {
                            IconKind.Filled -> Modifier.background(scheme.onSurface)
                            IconKind.Tonal -> Modifier.border(look.outline, scheme.outline, RectangleShape)
                            IconKind.Standard -> Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides if (kind == IconKind.Filled) scheme.surface else scheme.onSurface) { iconContent() }
            }
        }
        LookId.Retro -> if (kind == IconKind.Standard) {
            IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = iconContent)
        } else {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val fill = if (kind == IconKind.Filled) scheme.primary else scheme.secondaryContainer
            Box(
                modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .retroBox(RoundedCornerShape(look.cornerSmall), fill, pressed)
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides if (kind == IconKind.Filled) scheme.onPrimary else scheme.onSecondaryContainer,
                ) { iconContent() }
            }
        }
        else -> {
            val shape = if (look.pills) CircleShape else RoundedCornerShape(look.cornerSmall)
            when (kind) {
                IconKind.Standard -> IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = iconContent)
                IconKind.Filled -> FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = iconContent)
                IconKind.Tonal -> FilledTonalIconButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = iconContent)
            }
        }
    }
}

/** Like, dislike, shuffle, repeat. Terminal draws [text] when given, inverted while checked. */
@Composable
fun MIconToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    checkedIcon: ImageVector = icon,
    text: String? = null,
) {
    val look = LocalLook.current
    if (look.id == LookId.Terminal && text != null) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier
                .defaultMinSize(48.dp, 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
                .then(if (checked) Modifier.background(scheme.onSurface) else Modifier)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "[ ${lookLabel(text)} ]",
                style = MaterialTheme.typography.labelLarge,
                color = if (checked) scheme.surface else scheme.onSurface,
                maxLines = 1,
            )
        }
    } else {
        IconToggleButton(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier) {
            Icon(if (checked) checkedIcon else icon, contentDescription)
        }
    }
}
