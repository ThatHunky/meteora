package dev.thathunky.meteora.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp

/** A 90s drop shadow: the shape itself, solid, offset down-right, no blur. Drawn outside the bounds. */
fun Modifier.hardShadow(offset: Dp, shape: Shape, color: Color): Modifier = drawBehind {
    val o = offset.toPx()
    translate(o, o) { drawOutline(shape.createOutline(size, layoutDirection, this), color) }
}
