package dev.thathunky.meteora.ui.player

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath

/**
 * A [Shape] that is [progress] of the way through [morph] (0 = start polygon, 1 = end).
 * Used to morph the album art between a rounded square (paused) and a cookie (playing).
 */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    private val matrix = Matrix()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // Material shapes are normalized to 0..1; scale to the box.
        matrix.reset()
        matrix.scale(size.width, size.height)
        val path: Path = morph.toPath(progress).asComposePath()
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
