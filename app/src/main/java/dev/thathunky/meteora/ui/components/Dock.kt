package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.Look
import dev.thathunky.meteora.ui.theme.LookId
import dev.thathunky.meteora.ui.theme.NavStyle
import dev.thathunky.meteora.ui.theme.SurfaceStyle

/*
 * The bottom dock: one opaque unit across the full width, the mini player row on top (when a
 * track is loaded) and the tabs under it. Nothing floats over content. The topmost row takes
 * the dock's top corners and the hairline that separates it from the content.
 */

@Composable
fun dockColor(): Color =
    if (LocalLook.current.surface == SurfaceStyle.Tonal) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

fun dockCorner(look: Look): Dp = when (look.id) {
    LookId.Expressive -> 20.dp
    LookId.Terminal -> 0.dp
    LookId.Flat -> 8.dp
    LookId.Retro -> 2.dp
    LookId.Soft -> 32.dp
}

/**
 * Background of a dock row; with [top] also the dock's top corners and its hairline. [overlay]
 * draws after the hairline (the mini player's progress line), with the corner size and line width.
 */
@Composable
fun Modifier.dockRow(top: Boolean, overlay: DrawScope.(corner: Float, line: Float) -> Unit = { _, _ -> }): Modifier {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val color = dockColor()
    if (!top) return background(color).drawWithContent { drawContent(); overlay(0f, 0f) }
    val corner = dockCorner(look)
    val shape = RoundedCornerShape(topStart = corner, topEnd = corner)
    val (lineColor, lineWidth) = when (look.surface) {
        SurfaceStyle.Tonal -> scheme.outlineVariant to 1.dp
        else -> scheme.outline to look.outline
    }
    return clip(shape).background(color).drawWithContent {
        drawContent()
        val w = lineWidth.toPx()
        if (corner == 0.dp) {
            drawLine(lineColor, Offset(0f, w / 2), Offset(size.width, w / 2), w)
        } else {
            // The curved corners and the top edge only, not the sides.
            clipRect(bottom = corner.toPx() + w) {
                drawOutline(shape.createOutline(size, layoutDirection, this), lineColor, style = Stroke(w * 2))
            }
        }
        overlay(corner.toPx(), w)
    }
}

data class NavItem(val label: String, val icon: ImageVector)

/** The tabs row of the dock, padded for the system navigation bar. */
@Composable
fun MeteoraNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, top: Boolean) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val height = when (look.nav) {
        NavStyle.Tabs, NavStyle.IconsLine -> 56.dp
        NavStyle.StatusLine -> 48.dp
        NavStyle.Boxes -> 60.dp
        NavStyle.TallTabs -> 68.dp
    }
    Column(Modifier.fillMaxWidth().dockRow(top).windowInsetsPadding(WindowInsets.navigationBars)) {
        Row(
            Modifier.fillMaxWidth().height(height).selectableGroup()
                .then(if (look.nav == NavStyle.Boxes) Modifier.padding(horizontal = 6.dp, vertical = 6.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val tint = if (on) scheme.primary else scheme.onSurfaceVariant
                val cell = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                when (look.nav) {
                    NavStyle.Tabs -> Column(cell, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        Icon(item.icon, null, tint = tint)
                        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // A short line under the label, not a filled pill.
                        Box(Modifier.padding(top = 3.dp).size(16.dp, 3.dp).clip(CircleShape).background(if (on) scheme.primary else Color.Transparent))
                    }
                    NavStyle.TallTabs -> Column(cell, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        Box(
                            Modifier.size(56.dp, 32.dp).clip(CircleShape).background(if (on) scheme.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) { Icon(item.icon, null, tint = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant) }
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Icons only, so each carries its label for TalkBack; a 2 dp line above the selected one.
                    NavStyle.IconsLine -> Box(cell.semantics { contentDescription = item.label }, contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.align(Alignment.TopCenter).size(32.dp, 2.dp).background(scheme.primary))
                        Icon(item.icon, null, tint = tint)
                    }
                    NavStyle.Boxes -> Box(cell.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .retroBox(RoundedCornerShape(look.cornerSmall), if (on) scheme.primary else scheme.surface, pressed = on)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            val c = if (on) scheme.onPrimary else scheme.onSurface
                            Icon(item.icon, null, Modifier.size(18.dp), tint = c)
                            Spacer(Modifier.width(4.dp))
                            NavLabel(lookLabel(item.label), c)
                        }
                    }
                    // tmux-like: "1:home", the selected one inverted. No icons.
                    NavStyle.StatusLine -> Box(cell.then(if (on) Modifier.background(scheme.onSurface) else Modifier), contentAlignment = Alignment.Center) {
                        NavLabel("${i + 1}:${lookLabel(item.label)}", if (on) scheme.surface else scheme.onSurfaceVariant, Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}

/**
 * A tab label that shrinks a little before it ellipsizes: the capitals of Retro and the mono
 * font of Terminal do not fit "Налаштування" into a quarter of a phone's width at full size.
 */
@Composable
private fun NavLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        modifier = modifier,
    )
}
