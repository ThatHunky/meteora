package dev.thathunky.meteora.ui.player

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.artworkShape
import kotlinx.coroutines.delay

/**
 * The Song | Video switch above the artwork in Now Playing; only on the phone target, for a
 * track with a picture (and while on Video, so there is a way back).
 * Plain Material connected toggle buttons for now; swap for the look-aware `SegmentedChoice`
 * (ui/components) once the looks work is merged.
 */
@UnstableApi
@Composable
fun SongVideoSwitch(modifier: Modifier = Modifier, vm: VideoViewModel = hiltViewModel()) {
    val show by vm.showSwitch.collectAsStateWithLifecycle()
    val video by vm.videoMode.collectAsStateWithLifecycle()
    AnimatedVisibility(show, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = !video,
                onCheckedChange = { if (it) vm.setVideoMode(false) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            ) { Text(stringResource(R.string.mode_song), maxLines = 1) }
            ToggleButton(
                checked = video,
                onCheckedChange = { if (it) vm.setVideoMode(true) },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) { Text(stringResource(R.string.mode_video), maxLines = 1) }
        }
    }
}

/**
 * The picture in the artwork's place, same size and corners, letterboxed on the theme
 * background. Drawn over the artwork, which keeps the shared-element transition. A tap shows
 * the full-screen button for a few seconds.
 */
@UnstableApi
@Composable
fun VideoPanel(modifier: Modifier = Modifier, vm: VideoViewModel = hiltViewModel()) {
    val show by vm.showVideo.collectAsStateWithLifecycle()
    if (!show) return
    var controls by remember { mutableStateOf(false) }
    LaunchedEffect(controls) {
        if (controls) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    Box(
        modifier
            .clip(artworkShape(ArtKind.Player))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) { detectTapGestures { controls = !controls } },
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(vm, fullscreen = false)
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomEnd).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            FilledTonalIconButton(onClick = { vm.fullscreen.value = true }) {
                Icon(Icons.Outlined.Fullscreen, stringResource(R.string.action_fullscreen))
            }
        }
    }
}

/**
 * One view showing the picture: a TextureView inline (it follows the rounded clip and the
 * player's scale and drag), a SurfaceView in full screen (cheaper, nothing to clip there).
 * Attached while the screen is started, so the video track goes off in the background and with
 * the screen off. Hidden until its first frame, so the artwork under it shows meanwhile.
 */
@UnstableApi
@Composable
internal fun VideoSurface(vm: VideoViewModel, fullscreen: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(fullscreen) { if (fullscreen) SurfaceView(context) else TextureView(context) }
    var firstFrame by remember(view) { mutableStateOf(false) }
    val aspect by rememberVideoAspect(vm.player) { firstFrame = true }
    LifecycleStartEffect(view) {
        vm.attach(view)
        onStopOrDispose {
            vm.detach(view)
            firstFrame = false
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier
            .aspectRatio(aspect ?: (16f / 9f))
            .graphicsLayer { alpha = if (firstFrame) 1f else 0f },
    )
}

/** Width / height of the picture as it should be shown; null until the player knows it. */
@Composable
private fun rememberVideoAspect(player: Player, onFirstFrame: () -> Unit): State<Float?> {
    val aspect = remember(player) { mutableStateOf(player.videoSize.aspect()) }
    val firstFrame by rememberUpdatedState(onFirstFrame)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.aspect()?.let { aspect.value = it }
            }

            override fun onRenderedFirstFrame() = firstFrame()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return aspect
}

private fun VideoSize.aspect(): Float? = if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null

private const val CONTROLS_MS = 3_000L
