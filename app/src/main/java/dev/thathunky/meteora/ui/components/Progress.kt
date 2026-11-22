package dev.thathunky.meteora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.thathunky.meteora.ui.theme.LoadingStyle
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.ProgressStyle
import dev.thathunky.meteora.ui.theme.blockBar
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * The seek bar's track. [progress] and [amplitude] are lambdas read at draw time, so the bar
 * redraws every frame without recomposing anything (CLAUDE.md, per-frame reads).
 */
@Composable
fun MProgress(progress: () -> Float, amplitude: () -> Float, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    when (look.progress) {
        ProgressStyle.Wavy -> LinearWavyProgressIndicator(
            progress = progress,
            amplitude = { amplitude() },
            modifier = modifier.fillMaxWidth().height(14.dp),
        )
        ProgressStyle.WavyThick -> {
            val stroke = with(LocalDensity.current) { Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round) }
            LinearWavyProgressIndicator(
                progress = progress,
                amplitude = { amplitude() },
                stroke = stroke,
                trackStroke = stroke,
                wavelength = 44.dp,
                modifier = modifier.fillMaxWidth().height(22.dp),
            )
        }
        ProgressStyle.ThinLine -> Canvas(modifier.fillMaxWidth().height(14.dp)) {
            val p = progress().coerceIn(0f, 1f)
            val y = size.height / 2
            val w = 2.dp.toPx()
            val x = size.width * p
            drawLine(scheme.surfaceContainerHighest, Offset(0f, y), Offset(size.width, y), w)
            drawLine(scheme.primary, Offset(0f, y), Offset(x, y), w)
            val r = 5.dp.toPx()
            // maxOf: coerceIn throws when the range is empty, as it is while the bar is narrower than the thumb.
            drawCircle(scheme.primary, r, Offset(x.coerceIn(r, maxOf(r, size.width - r)), y))
        }
        ProgressStyle.ThickBar -> Canvas(modifier.fillMaxWidth().height(20.dp)) {
            val p = progress().coerceIn(0f, 1f)
            val bar = 8.dp.toPx()
            val top = (size.height - bar) / 2
            val line = 2.dp.toPx()
            val thumb = 16.dp.toPx()
            drawRect(scheme.primary, Offset(0f, top), Size(size.width * p, bar))
            drawRect(scheme.outline, Offset(line / 2, top), Size(size.width - line, bar), style = Stroke(line))
            val tx = (size.width * p - thumb / 2).coerceIn(0f, maxOf(0f, size.width - thumb))
            val ty = (size.height - thumb) / 2
            drawRect(scheme.onSurface, Offset(tx, ty), Size(thumb, thumb))
            drawRect(scheme.outline, Offset(tx, ty), Size(thumb, thumb), style = Stroke(line))
        }
        ProgressStyle.Blocks -> BlockProgress(progress, modifier)
    }
}

/**
 * `████░░░░` in the mono font. The two full rows are laid out once per width, then the filled
 * row is clipped to the progress, so a frame measures no text and the string never changes length.
 */
@Composable
private fun BlockProgress(progress: () -> Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().height(24.dp).drawWithCache {
            val cell = measurer.measure("█", style).size.width.coerceAtLeast(1)
            val cells = (size.width / cell).toInt()
            val full = measurer.measure(blockBar(1f, cells), style.copy(color = scheme.primary))
            val empty = measurer.measure(blockBar(0f, cells), style.copy(color = scheme.onSurfaceVariant))
            val y = (size.height - full.size.height) / 2
            onDrawBehind {
                // Whole cells only, as blockBar() fills them; counted without building the string each frame.
                val filled = (progress().coerceIn(0f, 1f) * cells).toInt().coerceIn(0, cells)
                drawText(empty, topLeft = Offset(0f, y))
                // From the laid-out row, so a fractional glyph advance cannot drift the clip.
                clipRect(right = full.size.width.toFloat() * filled / cells.coerceAtLeast(1)) { drawText(full, topLeft = Offset(0f, y)) }
            }
        },
    )
}

/**
 * A slider (Now Playing's PC volume). The wavy looks keep Material's slider; Terminal, Flat and
 * Retro draw their own seek bar's track ([MProgress]) so the volume under the seek bar is not a
 * rounded Material pill. A tap or a drag sets the value; TalkBack sets and steps it as a slider.
 */
@Composable
fun MSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    if (look.progress == ProgressStyle.Wavy || look.progress == ProgressStyle.WavyThick) {
        androidx.compose.material3.Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, modifier = modifier)
        return
    }
    val change by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val shown by androidx.compose.runtime.rememberUpdatedState(value)
    val start = valueRange.start
    val span = (valueRange.endInclusive - start).takeIf { it > 0f } ?: 1f
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                setProgress { v -> change(v.coerceIn(valueRange)); true }
            }
            .pointerInput(valueRange) {
                detectTapGestures { o -> change(start + span * (o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { c, _ -> change(start + span * (c.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.Center,
    ) {
        MProgress(progress = { ((shown - start) / span).coerceIn(0f, 1f) }, amplitude = { 0f })
    }
}

/** An indeterminate loader, in the look's form. [modifier] sets the size where the caller needs one. */
@Composable
fun MLoading(modifier: Modifier = Modifier, contained: Boolean = false) {
    val look = LocalLook.current
    when (look.loading) {
        LoadingStyle.Morphing -> if (contained) ContainedLoadingIndicator(modifier) else LoadingIndicator(modifier)
        LoadingStyle.TextSpinner -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) { TextSpinner() }
        LoadingStyle.ThinCircle -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        LoadingStyle.Squares -> BouncingSquares(modifier)
    }
}

/** A thin bar for page loads (the sign-in WebView). Terminal's is one line of text, not a headline. */
@Composable
fun MLinearLoading(modifier: Modifier = Modifier) {
    when (LocalLook.current.loading) {
        LoadingStyle.Morphing -> LinearWavyProgressIndicator(modifier)
        LoadingStyle.TextSpinner -> Box(modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
            TextSpinner(MaterialTheme.typography.labelLarge)
        }
        else -> LinearProgressIndicator(modifier)
    }
}

private const val SPINNER = "|/-\\"

@Composable
private fun TextSpinner(style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            i = (i + 1) % SPINNER.length
        }
    }
    Text(SPINNER[i].toString(), style = style, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BouncingSquares(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "squares").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val scheme = MaterialTheme.colorScheme
    // The phase is read in the draw block only.
    Canvas(modifier.size(width = 56.dp, height = 40.dp)) {
        val s = 10.dp.toPx()
        val gap = (size.width - 3 * s) / 2
        for (k in 0..2) {
            val phase = ((t - k * 0.15f) * 2 * Math.PI).toFloat()
            val lift = (sin(phase).coerceAtLeast(0f)) * (size.height - s)
            drawRect(scheme.primary, Offset(k * (s + gap), size.height - s - lift), Size(s, s))
            drawRect(scheme.outline, Offset(k * (s + gap), size.height - s - lift), Size(s, s), style = Stroke(1.5.dp.toPx()))
        }
    }
}
