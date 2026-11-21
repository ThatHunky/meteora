package dev.thathunky.meteora.ui.player

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.R
import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.PlaybackState
import kotlinx.coroutines.delay

/**
 * The picture edge to edge, in landscape with the system bars hidden, over Now Playing. A tap
 * shows play/pause, the seek bar and the way back; back returns to Now Playing.
 */
@UnstableApi
@Composable
fun FullscreenVideo(s: PlaybackState, player: PlayerViewModel, vm: VideoViewModel = hiltViewModel()) {
    val on by vm.fullscreen.collectAsStateWithLifecycle()
    val show by vm.showVideo.collectAsStateWithLifecycle()
    // The track changed to one without a picture, or the switch went back to Song.
    LaunchedEffect(show) { if (!show) vm.fullscreen.value = false }
    if (!on || !show) return

    BackHandler { vm.fullscreen.value = false }
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // Turning to landscape recreates the activity: that is not leaving full screen.
            if (activity?.isChangingConfigurations != true) {
                bars?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                vm.fullscreen.value = false
            }
        }
    }

    var controls by remember { mutableStateOf(true) }
    val current by rememberUpdatedState(s)
    val jump = { by: Double ->
        val duration = current.track?.durationSec ?: 0.0
        if (duration > 0) {
            player.seekTo((current.progressAt(SystemClock.elapsedRealtime()) + by).coerceIn(0.0, duration))
        }
    }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(controls, s.isPlaying, menu) {
        if (controls && s.isPlaying && !menu) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Taps toggle the controls; drags stop here instead of moving Now Playing under it.
            // Double tap on the left or right half jumps 10 s, as video players do.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { at -> jump(if (at.x < size.width / 2) -JUMP_SEC else JUMP_SEC) },
                )
            }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } },
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(vm, fullscreen = true)
        NerdStatsOverlay(vm, Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 24.dp, top = 64.dp))
        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.track?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.track?.artist.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val liked = s.track?.like == LikeStatus.Like
                    IconButton(onClick = player::toggleLike) {
                        Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, stringResource(R.string.like), tint = Color.White)
                    }
                    VideoOptionsButton(vm, menu, { menu = it }, Color.White)
                    IconButton(onClick = { vm.fullscreen.value = false }) {
                        Icon(Icons.Outlined.FullscreenExit, stringResource(R.string.action_exit_fullscreen), tint = Color.White)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    FullscreenSeek(s, player)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = player::previous) {
                            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_previous), tint = Color.White)
                        }
                        IconButton(onClick = { jump(-JUMP_SEC) }) {
                            Icon(Icons.Filled.Replay10, stringResource(R.string.action_rewind), tint = Color.White)
                        }
                        FilledTonalIconButton(onClick = player::playPause, Modifier.size(56.dp)) {
                            Icon(
                                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play),
                            )
                        }
                        IconButton(onClick = { jump(JUMP_SEC) }) {
                            Icon(Icons.Filled.Forward10, stringResource(R.string.action_forward), tint = Color.White)
                        }
                        IconButton(onClick = player::next) {
                            Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Its own composable: the position changes every frame, and only this needs to follow it. */
@Composable
private fun FullscreenSeek(s: PlaybackState, vm: PlayerViewModel) {
    val duration = s.track?.durationSec ?: 0.0
    val live = rememberLiveProgress(s)
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (duration > 0) (live.value / duration).toFloat().coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(fraction * duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = fraction,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { if (duration > 0) vm.seekTo(duration * it) }
                drag = null
            },
            enabled = duration > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private const val CONTROLS_MS = 3_000L
private const val JUMP_SEC = 10.0
