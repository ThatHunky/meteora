package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import coil3.compose.AsyncImage
import dev.thathunky.meteora.ui.player.MorphShape
import dev.thathunky.meteora.ui.theme.ArtworkShape
import dev.thathunky.meteora.ui.theme.LocalArtworkShape
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.Look
import dev.thathunky.meteora.ui.theme.LookId
import dev.thathunky.meteora.ui.theme.PauseArt

/** Where a cover is shown; each place has its own size and so its own corner. */
enum class ArtKind { Song, Album, Artist, Card, Hero, Mini, Player, Sheet, Queue, Small }

/** The clip for [kind] in [look]. [current] marks the playing row in the queue. */
@Composable
fun artworkShape(kind: ArtKind, current: Boolean = false): Shape {
    val look = LocalLook.current
    val scalloped = look.artworkSetting && LocalArtworkShape.current == ArtworkShape.Scalloped
    return shapeFor(look, kind, scalloped, current)
}

// Composable because MaterialShapes' toShape() is.
@Composable
private fun shapeFor(look: Look, kind: ArtKind, scalloped: Boolean, current: Boolean): Shape = when (look.id) {
    // Exactly the shapes the app had before looks.
    LookId.Expressive -> when (kind) {
        ArtKind.Song -> RoundedCornerShape(10.dp)
        ArtKind.Album, ArtKind.Mini -> if (scalloped) MaterialShapes.Cookie9Sided.toShape() else RoundedCornerShape(10.dp)
        ArtKind.Artist -> CircleShape
        ArtKind.Card -> RoundedCornerShape(20.dp)
        ArtKind.Hero -> if (scalloped) MaterialShapes.Cookie12Sided.toShape() else RoundedCornerShape(28.dp)
        ArtKind.Player -> RoundedCornerShape(28.dp)
        ArtKind.Sheet -> RoundedCornerShape(12.dp)
        ArtKind.Queue -> RoundedCornerShape(if (current) 24.dp else 8.dp)
        ArtKind.Small -> RoundedCornerShape(8.dp)
    }
    LookId.Terminal -> RectangleShape
    LookId.Flat -> when (kind) {
        ArtKind.Artist -> CircleShape
        ArtKind.Mini -> RectangleShape
        else -> RoundedCornerShape(8.dp)
    }
    LookId.Retro -> RoundedCornerShape(2.dp)
    // From the look's tokens, so Soft's corners follow them.
    LookId.Soft -> when (kind) {
        ArtKind.Artist -> CircleShape
        ArtKind.Album, ArtKind.Mini -> if (scalloped) MaterialShapes.Cookie9Sided.toShape() else RoundedCornerShape(look.cornerSmall)
        ArtKind.Hero -> if (scalloped) MaterialShapes.Cookie12Sided.toShape() else RoundedCornerShape(look.cornerLarge)
        ArtKind.Card, ArtKind.Player -> RoundedCornerShape(look.cornerLarge)
        ArtKind.Queue -> RoundedCornerShape(if (current) look.cornerMedium else look.cornerSmall)
        else -> RoundedCornerShape(look.cornerSmall)
    }
}

/**
 * A cover with the look's clip and outline. [modifier] comes first, so a caller's sharedElement
 * stays outermost. For [ArtKind.Player], [playing] (0 paused, 1 playing) is read in the layer
 * block, so the pause treatment animates without recomposing the player.
 */
@Composable
fun ArtworkFrame(
    model: Any?,
    kind: ArtKind,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    current: Boolean = false,
    playing: (() -> Float)? = null,
) {
    val look = LocalLook.current
    val outline = look.outline
    val outlineColor = MaterialTheme.colorScheme.outline
    val scalloped = look.artworkSetting && LocalArtworkShape.current == ArtworkShape.Scalloped
    val shape = shapeFor(look, kind, scalloped, current)
    val framed = if (kind == ArtKind.Player && playing != null) {
        val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie12Sided) }
        modifier.graphicsLayer {
            val p = playing()
            clip = true
            if (scalloped) {
                this.shape = MorphShape(morph, p)
            } else {
                this.shape = shape
                when (look.pauseArt) {
                    PauseArt.Shrink -> {
                        val k = 0.88f + 0.12f * p
                        scaleX = k; scaleY = k
                    }
                    PauseArt.Dim -> alpha = 0.6f + 0.4f * p
                    PauseArt.Nothing -> Unit
                }
            }
        }
    } else {
        modifier.clip(shape)
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        // Inside the clip and the layer, so the outline scales with the art and stays in bounds.
        modifier = if (outline > 0.dp) framed.border(outline, outlineColor, shape) else framed,
    )
}
