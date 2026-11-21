package dev.thathunky.meteora.ui.player

import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.thathunky.meteora.R
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.TrackState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Mini player (floating toolbar above the nav bar) and full Now Playing, in one
 * [SharedTransitionLayout] so art and title fly between them. Not a ModalBottomSheet:
 * that is a separate window and shared elements can't cross windows (PLAN.md §3).
 */
@Composable
fun PlayerHost(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    miniBottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val s = state ?: return
    if (s.track == null) return

    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "player",
        ) { isExpanded ->
            if (isExpanded) {
                FullPlayer(s, connection, vm, onCollapse = { onExpandedChange(false) }, scope = this@SharedTransitionLayout, anim = this)
            } else {
                Box(Modifier.fillMaxSize().padding(bottom = miniBottomPadding), contentAlignment = Alignment.BottomCenter) {
                    MiniPlayer(s, vm, onExpand = { onExpandedChange(true) }, scope = this@SharedTransitionLayout, anim = this@AnimatedContent)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    s: PlaybackState,
    vm: PlayerViewModel,
    onExpand: () -> Unit,
    scope: SharedTransitionScope,
    anim: AnimatedVisibilityScope,
) = with(scope) {
    val track = s.track ?: return@with
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY < -60) onExpand(); dragY = 0f },
                    onVerticalDrag = { _, d -> dragY += d },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -120) vm.next() else if (dragX > 120) vm.previous()
                        dragX = 0f
                    },
                    onHorizontalDrag = { _, d -> dragX += d },
                )
            },
        leadingContent = {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .sharedElement(rememberSharedContentState("art"), anim)
                    .size(44.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape()),
            )
        },
        trailingContent = {
            IconButton(onClick = vm::next) { Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next)) }
        },
    ) {
        Column(
            Modifier.widthIn(max = 180.dp).padding(horizontal = 8.dp)
                .sharedBounds(rememberSharedContentState("title"), anim),
        ) {
            Text(track.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledIconButton(onClick = vm::playPause, shapes = IconButtonDefaults.shapes()) {
            Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
        }
    }
}

@Composable
private fun FullPlayer(
    s: PlaybackState,
    connection: ConnectionState,
    vm: PlayerViewModel,
    onCollapse: () -> Unit,
    scope: SharedTransitionScope,
    anim: AnimatedVisibilityScope,
) = with(scope) {
    val track = s.track ?: return@with
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    var dragDown by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }

    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            onCollapse()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val k = 1f - 0.1f * backProgress
                scaleX = k; scaleY = k
                translationY = dragDown.coerceAtLeast(0f)
                shape = RoundedCornerShape((32 * backProgress).dp)
                clip = backProgress > 0f
            }
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface, scheme.surface)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragDown > with(density) { 160.dp.toPx() }) onCollapse()
                        dragDown = 0f
                    },
                    onDragCancel = { dragDown = 0f },
                    onVerticalDrag = { _, d -> dragDown += d },
                )
            },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Outlined.KeyboardArrowDown, null) }
                Spacer(Modifier.weight(1f))
                val pcName = (connection as? ConnectionState.Connected)?.pcName
                AssistChip(onClick = {}, label = { Text(pcName?.let { stringResource(R.string.playing_on, it) } ?: stringResource(R.string.reconnecting)) })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.queue)) }
            }

            Spacer(Modifier.height(16.dp))
            val morphProgress by animateFloatAsState(
                if (s.trackState == TrackState.Playing) 1f else 0f,
                MaterialTheme.motionScheme.slowSpatialSpec(),
                label = "artMorph",
            )
            val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie12Sided) }
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = track.album,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .sharedElement(rememberSharedContentState("art"), anim)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MorphShape(morph, morphProgress)),
            )

            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth().sharedBounds(rememberSharedContentState("title"), anim)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconToggleButton(checked = track.like == LikeStatus.Dislike, onCheckedChange = { vm.toggleDislike() }) {
                    Icon(if (track.like == LikeStatus.Dislike) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, stringResource(R.string.dislike))
                }
                IconToggleButton(
                    checked = track.like == LikeStatus.Like,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.toggleLike()
                    },
                ) {
                    Icon(if (track.like == LikeStatus.Like) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, stringResource(R.string.like))
                }
            }

            SeekBar(s, vm)

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(checked = s.shuffle, onCheckedChange = { vm.toggleShuffle() }) {
                    Icon(Icons.Outlined.Shuffle, stringResource(R.string.shuffle))
                }
                FilledTonalIconButton(onClick = vm::previous, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_previous), Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        vm.playPause()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(width = 112.dp, height = 80.dp),
                ) {
                    Icon(
                        if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play),
                        Modifier.size(40.dp),
                    )
                }
                FilledTonalIconButton(onClick = vm::next, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next), Modifier.size(32.dp))
                }
                IconToggleButton(checked = s.repeat == RepeatMode.All || s.repeat == RepeatMode.One, onCheckedChange = { vm.cycleRepeat() }) {
                    Icon(if (s.repeat == RepeatMode.One) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, stringResource(R.string.repeat))
                }
            }

            Spacer(Modifier.weight(1f))
            VolumeRow(s, vm)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showQueue) QueueSheet(s, vm, onDismiss = { showQueue = false })
}

@Composable
private fun SeekBar(s: PlaybackState, vm: PlayerViewModel) {
    val duration = s.track?.durationSec ?: 0.0
    val live by rememberLiveProgress(s)
    val override by vm.seekOverride.collectAsStateWithLifecycle()
    LaunchedEffect(s.progressSec) { vm.onPositionReport(s.progressSec) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current

    val shown = dragFraction
        ?: if (duration > 0) ((override ?: live) / duration).toFloat().coerceIn(0f, 1f) else 0f
    val amplitude by animateFloatAsState(
        if (s.trackState == TrackState.Playing && dragFraction == null) 1f else 0f,
        label = "waveAmp",
    )

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(duration) {
                    widthPx = size.width.toFloat()
                    detectTapGestures { o ->
                        if (duration > 0) vm.seekTo(duration * (o.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (duration > 0) vm.seekTo(duration * it) }
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { _, d -> dragFraction = ((dragFraction ?: 0f) + d / size.width).coerceIn(0f, 1f) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { shown },
                amplitude = { amplitude },
                modifier = Modifier.fillMaxWidth().height(14.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (duration > 0) shown * duration else live), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VolumeRow(s: PlaybackState, vm: PlayerViewModel) {
    val override by vm.volumeOverride.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = (override ?: s.volume).toFloat(),
            onValueChange = { vm.setVolume(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.Outlined.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Position interpolated every frame while playing (the PC reports a few times a second). */
@Composable
fun rememberLiveProgress(s: PlaybackState): State<Double> {
    val p = remember { mutableDoubleStateOf(s.progressSec) }
    LaunchedEffect(s) {
        p.doubleValue = s.progressAt(SystemClock.elapsedRealtime())
        while (s.trackState == TrackState.Playing) {
            withFrameMillis { }
            p.doubleValue = s.progressAt(SystemClock.elapsedRealtime())
        }
    }
    return p
}

fun formatTime(seconds: Double): String {
    val t = seconds.coerceAtLeast(0.0).toInt()
    val h = t / 3600
    val m = (t % 3600) / 60
    val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
