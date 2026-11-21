package dev.thathunky.meteora.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colorSpring = spring<Color>(stiffness = Spring.StiffnessVeryLow)

@Composable
private fun Color.anim(): Color = animateColorAsState(this, colorSpring, label = "scheme").value

/** Springs every role so the whole app re-tints smoothly when the album art changes. */
@Composable
fun ColorScheme.animated(): ColorScheme = copy(
    primary = primary.anim(),
    onPrimary = onPrimary.anim(),
    primaryContainer = primaryContainer.anim(),
    onPrimaryContainer = onPrimaryContainer.anim(),
    inversePrimary = inversePrimary.anim(),
    secondary = secondary.anim(),
    onSecondary = onSecondary.anim(),
    secondaryContainer = secondaryContainer.anim(),
    onSecondaryContainer = onSecondaryContainer.anim(),
    tertiary = tertiary.anim(),
    onTertiary = onTertiary.anim(),
    tertiaryContainer = tertiaryContainer.anim(),
    onTertiaryContainer = onTertiaryContainer.anim(),
    background = background.anim(),
    onBackground = onBackground.anim(),
    surface = surface.anim(),
    onSurface = onSurface.anim(),
    surfaceVariant = surfaceVariant.anim(),
    onSurfaceVariant = onSurfaceVariant.anim(),
    surfaceTint = surfaceTint.anim(),
    inverseSurface = inverseSurface.anim(),
    inverseOnSurface = inverseOnSurface.anim(),
    outline = outline.anim(),
    outlineVariant = outlineVariant.anim(),
    surfaceBright = surfaceBright.anim(),
    surfaceDim = surfaceDim.anim(),
    surfaceContainer = surfaceContainer.anim(),
    surfaceContainerHigh = surfaceContainerHigh.anim(),
    surfaceContainerHighest = surfaceContainerHighest.anim(),
    surfaceContainerLow = surfaceContainerLow.anim(),
    surfaceContainerLowest = surfaceContainerLowest.anim(),
)
