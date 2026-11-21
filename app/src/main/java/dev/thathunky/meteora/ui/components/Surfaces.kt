package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.thathunky.meteora.ui.theme.HeaderStyle
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.SurfaceStyle

/** Which colour family a container takes. */
enum class Tone { High, Primary, Error }

/**
 * A card or container: a tonal fill (Expressive, Flat, Soft), a 1 dp outline with no fill and
 * inverted when [selected] (Terminal), or fill, outline and hard shadow (Retro). [shape] defaults
 * to the theme's medium shape, which the look already sets.
 */
@Composable
fun MSurface(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.High,
    selected: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer, accent) = when (tone) {
        Tone.High -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, scheme.outline)
        Tone.Primary -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        Tone.Error -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
    }
    when (look.surface) {
        SurfaceStyle.Tonal -> {
            val colors = CardDefaults.cardColors(containerColor = container)
            if (onClick != null) Card(onClick = onClick, shape = shape, colors = colors, modifier = modifier) { content() }
            else Card(shape = shape, colors = colors, modifier = modifier) { content() }
        }
        SurfaceStyle.Outline -> {
            val fg = if (selected) scheme.surface else if (tone == Tone.High) scheme.onSurface else accent
            Box(
                modifier
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .then(if (selected) Modifier.background(scheme.onSurface, shape) else Modifier)
                    .border(look.outline, if (selected) scheme.onSurface else accent, shape),
            ) {
                CompositionLocalProvider(LocalContentColor provides fg) { content() }
            }
        }
        SurfaceStyle.HardShadow -> Box(
            modifier
                .retroBox(shape, container)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            CompositionLocalProvider(LocalContentColor provides onContainer) { content() }
        }
    }
}

/** Colour a [MSurface] gives its content, for text that sets its colour explicitly. */
@Composable
fun surfaceContentColor(tone: Tone): Color {
    val scheme = MaterialTheme.colorScheme
    return when (LocalLook.current.surface) {
        SurfaceStyle.Outline -> when (tone) {
            Tone.High -> scheme.onSurface
            Tone.Primary -> scheme.primary
            Tone.Error -> scheme.error
        }
        else -> when (tone) {
            Tone.High -> scheme.onSurface
            Tone.Primary -> scheme.onPrimaryContainer
            Tone.Error -> scheme.onErrorContainer
        }
    }
}

private fun headerPadding(start: androidx.compose.ui.unit.Dp, top: androidx.compose.ui.unit.Dp) =
    PaddingValues(start = start, end = start, top = top, bottom = 8.dp)

/** A shelf or section title, drawn the look's way. Case and prefixes are added here, never in strings. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, padding: PaddingValues? = null) {
    val look = LocalLook.current
    val type = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val p = look.screenPadding
    when (look.header) {
        HeaderStyle.Emphasized -> Text(text, style = type.titleLargeEmphasized, modifier = modifier.padding(padding ?: PaddingValues(start = p, top = 20.dp, bottom = 8.dp)))
        HeaderStyle.Hash -> Text(
            "# " + lookLabel(text),
            style = type.titleMedium,
            color = scheme.primary,
            modifier = modifier.padding(padding ?: headerPadding(p, 16.dp)),
        )
        HeaderStyle.SmallCaps -> Text(
            text.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
            style = type.labelLarge.copy(letterSpacing = 0.15.em),
            color = scheme.onSurfaceVariant,
            modifier = modifier.padding(padding ?: headerPadding(p, 24.dp)),
        )
        HeaderStyle.SerifRule -> Column(modifier.padding(padding ?: headerPadding(p, 20.dp))) {
            Text(text, style = type.titleLarge)
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(scheme.onSurface))
        }
        HeaderStyle.Large -> Text(text, style = type.headlineSmall, modifier = modifier.padding(padding ?: headerPadding(p, 28.dp)))
    }
}

/** The big title at the top of a tab (Home, Settings). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val type = MaterialTheme.typography
    val p = look.screenPadding
    val pad = PaddingValues(start = p, end = p, top = 24.dp, bottom = 8.dp)
    when (look.header) {
        HeaderStyle.Emphasized -> Text(text, style = type.displaySmallEmphasized, modifier = modifier.padding(start = p, top = 24.dp, bottom = 8.dp))
        HeaderStyle.Hash -> Text("~/" + lookLabel(text), style = type.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(pad))
        HeaderStyle.SmallCaps -> Text(text, style = type.headlineMedium, modifier = modifier.padding(pad))
        HeaderStyle.SerifRule -> Column(modifier.padding(pad)) {
            Text(text, style = type.displaySmall)
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface))
        }
        HeaderStyle.Large -> Text(text, style = type.displaySmall, modifier = modifier.padding(pad))
    }
}

/** A sub-screen's title next to its back button. */
@Composable
fun screenTitleStyle(): TextStyle = MaterialTheme.typography.titleLargeEmphasized
