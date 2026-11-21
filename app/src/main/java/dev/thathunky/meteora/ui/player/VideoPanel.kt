package dev.thathunky.meteora.ui.player

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
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
import dev.thathunky.meteora.ui.components.MLoading
import dev.thathunky.meteora.ui.components.artworkShape
import kotlinx.coroutines.delay

/**
 * The Song | Video switch above the artwork in Now Playing; only on the phone target, for a
 * track with a picture (and while on Video, so there is a way back). Drawn by the look.
 */
@UnstableApi
@Composable
fun SongVideoSwitch(modifier: Modifier = Modifier, vm: VideoViewModel = hiltViewModel()) {
    val show by vm.showSwitch.collectAsStateWithLifecycle()
    val video by vm.videoMode.collectAsStateWithLifecycle()
    AnimatedVisibility(show, modifier, enter = fadeIn(), exit = fadeOut()) {
        dev.thathunky.meteora.ui.components.SegmentedChoice(
            listOf(
                dev.thathunky.meteora.ui.components.Choice(false, stringResource(R.string.mode_song)),
                dev.thathunky.meteora.ui.components.Choice(true, stringResource(R.string.mode_video)),
            ),
            video,
            vm::setVideoMode,
            Modifier.padding(bottom = 12.dp),
        )
    }
}

/**
 * The picture in the artwork's place, same corners; Now Playing sizes the slot to the picture.
 * Drawn over the artwork, which keeps the shared-element transition, and see-through until the
 * first frame, so the cover shows (with a loader) meanwhile. A tap shows the gear and the
 * full-screen button for a few seconds; they show once on their own when the picture appears.
 */
@UnstableApi
@Composable
fun VideoPanel(modifier: Modifier = Modifier, vm: VideoViewModel = hiltViewModel()) {
    val show by vm.showVideo.collectAsStateWithLifecycle()
    if (!show) return
    var controls by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf(false) }
    val timeout = controlsTimeout()
    LaunchedEffect(controls, menu) {
        if (controls && !menu) {
            delay(timeout)
            controls = false
        }
    }
    Box(
        modifier
            .clip(artworkShape(ArtKind.Player))
            .then(if (picture) Modifier.background(Color.Black) else Modifier)
            // A click rather than a raw tap detector, so TalkBack can reach the controls too.
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = stringResource(R.string.action_video_controls),
                onClick = { controls = !controls },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(vm, fullscreen = false, onPicture = { picture = it })
        if (!picture) MLoading()
        NerdStatsOverlay(vm, Modifier.align(Alignment.TopStart).padding(12.dp))
        // Shown while the menu is open too: the menu lives in here and would vanish with it.
        // White on a scrim, as in full screen: these sit on the picture, not on a surface of the look.
        AnimatedVisibility(controls || menu, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f)))) {
                Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    VideoOptionsButton(vm, menu, { menu = it }, Color.White)
                    IconButton(onClick = { vm.fullscreen.value = true }) {
                        Icon(Icons.Outlined.Fullscreen, stringResource(R.string.action_fullscreen), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** How long video controls stay up; longer when an accessibility service asks for it. */
@Composable
internal fun controlsTimeout(): Long =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(CONTROLS_MS, containsIcons = true, containsControls = true) ?: CONTROLS_MS

/** Width / height of the picture while [show]; null before the player knows it, and in song mode. */
@UnstableApi
@Composable
internal fun pictureAspect(vm: VideoViewModel, show: Boolean): Float? {
    // Only while showing: reading vm.player builds the phone's player.
    if (!show) return null
    return rememberVideoAspect(vm.player) {}.value
}

/**
 * One view showing the picture: a TextureView inline (it follows the rounded clip and the
 * player's scale and drag), a SurfaceView in full screen (cheaper, nothing to clip there).
 * Attached while the screen is started, so the video track goes off in the background and with
 * the screen off. Hidden until its first frame, so the artwork under it shows meanwhile.
 */
@UnstableApi
@Composable
internal fun VideoSurface(vm: VideoViewModel, fullscreen: Boolean, modifier: Modifier = Modifier, onPicture: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val view = remember(fullscreen) { if (fullscreen) SurfaceView(context) else TextureView(context) }
    var firstFrame by remember(view) { mutableStateOf(false) }
    val aspect by rememberVideoAspect(vm.player) { firstFrame = true }
    val picture by rememberUpdatedState(onPicture)
    LaunchedEffect(firstFrame) { picture(firstFrame) }
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
