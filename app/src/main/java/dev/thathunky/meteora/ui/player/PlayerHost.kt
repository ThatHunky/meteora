package dev.thathunky.meteora.ui.player

import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
            // Thresholds in dp, so a swipe is the same distance on every screen density.
            .pointerInput(Unit) {
                val open = 24.dp.toPx()
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY < -open) onExpand(); dragY = 0f },
                    onDragCancel = { dragY = 0f },
                    onVerticalDrag = { _, d -> dragY += d },
                )
            }
            .pointerInput(Unit) {
                val skip = 48.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -skip) vm.next() else if (dragX > skip) vm.previous()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
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
                    .clip(
                        if (dev.thathunky.meteora.ui.theme.LocalArtworkShape.current == dev.thathunky.meteora.ui.theme.ArtworkShape.Scalloped) MaterialShapes.Cookie9Sided.toShape()
                        else RoundedCornerShape(10.dp),
                    ),
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
            Icon(
                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play),
            )
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
    // Animatables, so a cancelled back gesture or a short drag settles back instead of snapping.
    val backProgress = remember { Animatable(0f) }
    val dragDown = remember { Animatable(0f) }
    val settle: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()
    val scope = rememberCoroutineScope()
    var showQueue by remember { mutableStateOf(false) }

    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress.snapTo(it.progress) }
            onCollapse()
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f, settle) }
            throw e
        }
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // The settle spring may overshoot: keep both inside their range.
                val back = backProgress.value.coerceIn(0f, 1f)
                val k = 1f - 0.1f * back
                scaleX = k; scaleY = k
                translationY = dragDown.value.coerceAtLeast(0f)
                shape = RoundedCornerShape((32 * back).dp)
                clip = back > 0f
            }
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface, scheme.surface)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        // On collapse the offset stays, so the player leaves from where the finger let go.
                        if (dragDown.value > with(density) { 160.dp.toPx() }) onCollapse()
                        else scope.launch { dragDown.animateTo(0f, settle) }
                    },
                    onDragCancel = { scope.launch { dragDown.animateTo(0f, settle) } },
                    // Clamped as it accumulates: dragging up first must not eat the drag back down.
                    onVerticalDrag = { _, d -> scope.launch { dragDown.snapTo((dragDown.value + d).coerceAtLeast(0f)) } },
                )
            },
    ) {
        // Not inside a Surface, so set the content color ourselves (otherwise text is black).
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_collapse)) }
                Spacer(Modifier.weight(1f))
                val pcName = (connection as? ConnectionState.Connected)?.pcName
                val knownPc by vm.pcName.collectAsStateWithLifecycle()
                val target by vm.target.collectAsStateWithLifecycle()
                var targetMenu by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { targetMenu = true },
                        leadingIcon = {
                            Icon(
                                if (connection == ConnectionState.Local) androidx.compose.material.icons.Icons.Outlined.PhoneAndroid
                                else androidx.compose.material.icons.Icons.Outlined.Computer,
                                null,
                                Modifier.size(18.dp),
                            )
                        },
                        label = {
                            Text(
                                when {
                                    connection == ConnectionState.Local -> stringResource(R.string.playing_on_phone)
                                    pcName != null -> stringResource(R.string.playing_on, pcName)
                                    else -> stringResource(R.string.reconnecting)
                                },
                            )
                        },
                    )
                    androidx.compose.material3.DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                        dev.thathunky.meteora.data.PlaybackTarget.entries.forEach { t ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        if (t == dev.thathunky.meteora.data.PlaybackTarget.Phone) stringResource(R.string.target_phone)
                                        else knownPc ?: stringResource(R.string.target_pc),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (t == dev.thathunky.meteora.data.PlaybackTarget.Phone) androidx.compose.material.icons.Icons.Outlined.PhoneAndroid
                                        else androidx.compose.material.icons.Icons.Outlined.Computer,
                                        null,
                                    )
                                },
                                trailingIcon = { if (t == target) Icon(androidx.compose.material.icons.Icons.Outlined.Check, null) },
                                onClick = {
                                    targetMenu = false
                                    vm.setTarget(t)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.queue)) }
            }

            Spacer(Modifier.height(16.dp))
            val morphProgress = animateFloatAsState(
                if (s.trackState == TrackState.Playing) 1f else 0f,
                MaterialTheme.motionScheme.slowSpatialSpec(),
                label = "artMorph",
            )
            val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie12Sided) }
            // Rounded shows the whole cover and marks pause by size instead of by cutting.
            val scalloped = dev.thathunky.meteora.ui.theme.LocalArtworkShape.current == dev.thathunky.meteora.ui.theme.ArtworkShape.Scalloped
            val rounded = remember { RoundedCornerShape(28.dp) }
            // The art takes the space the controls leave, so it shrinks on short or landscape
            // screens instead of pushing the controls off the bottom.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.album,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .sharedElement(rememberSharedContentState("art"), anim)
                        .aspectRatio(1f)
                        // Read in the layer block: the morph redraws the art without recomposing the player.
                        .graphicsLayer {
                            clip = true
                            if (scalloped) {
                                shape = MorphShape(morph, morphProgress.value)
                            } else {
                                shape = rounded
                                val k = 0.88f + 0.12f * morphProgress.value
                                scaleX = k; scaleY = k
                            }
                        },
                )
            }

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

            Spacer(Modifier.height(12.dp))
            // On the phone the hardware keys and system slider already set the volume.
            if (connection != ConnectionState.Local) VolumeRow(s, vm)
            Spacer(Modifier.height(16.dp))
        }
        }
    }

    if (showQueue) QueueSheet(s, vm, onDismiss = { showQueue = false })
}

@Composable
private fun SeekBar(s: PlaybackState, vm: PlayerViewModel) {
    val duration = s.track?.durationSec ?: 0.0
    // `live` changes every frame while playing. It is only read inside the two lambdas below, so
    // a frame redraws the bar (and, once a second, the time label) without recomposing the rest.
    val live = rememberLiveProgress(s)
    val override by vm.seekOverride.collectAsStateWithLifecycle()
    LaunchedEffect(s.progressSec) { vm.onPositionReport(s.progressSec) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val haptics = LocalHapticFeedback.current

    val shown: () -> Float = {
        dragFraction ?: if (duration > 0) ((override ?: live.value) / duration).toFloat().coerceIn(0f, 1f) else 0f
    }
    val shownSeconds by remember(duration, dragFraction, override) {
        derivedStateOf { (if (duration > 0) shown() * duration else live.value).toInt() }
    }
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
                progress = shown,
                amplitude = { amplitude },
                modifier = Modifier.fillMaxWidth().height(14.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shownSeconds.toDouble()), style = MaterialTheme.typography.labelMedium)
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
