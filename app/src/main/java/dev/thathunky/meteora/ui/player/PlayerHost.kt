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
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.share.ShareLink
import dev.thathunky.meteora.share.ShareTarget
import dev.thathunky.meteora.share.shareLink
import dev.thathunky.meteora.share.shareTitle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import dev.thathunky.meteora.ui.components.MLoading
import dev.thathunky.meteora.ui.components.ActionRow
import dev.thathunky.meteora.ui.components.RowAction
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.IconKind
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MIconToggle
import dev.thathunky.meteora.ui.components.MProgress
import dev.thathunky.meteora.ui.components.dockRow
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import dev.thathunky.meteora.ui.theme.MiniStyle
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
    onOpenAlbum: (browseId: String) -> Unit,
    onOpenArtist: (browseId: String) -> Unit,
    onRadio: (videoId: String) -> Unit,
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
                FullPlayer(
                    s, connection, vm, onCollapse = { onExpandedChange(false) },
                    links = TrackLinks(onOpenAlbum, onOpenArtist, onRadio),
                    scope = this@SharedTransitionLayout, anim = this,
                )
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
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val duration = track.durationSec ?: 0.0
    // Changes every frame while playing; read only in the draw lambda and, once a second, the time text.
    val live = rememberLiveProgress(s)
    val terminal = look.mini == MiniStyle.StatusLine
    Row(
        Modifier
            .fillMaxWidth()
            .height(look.miniSpace)
            // The top of the dock: its corners and hairline, the tabs right below.
            // A 2 dp progress line just under the hairline, between the rounded corners;
            // decorative, so no semantics. live is read here only, at draw time.
            .dockRow(top = true) { corner, line ->
                if (!terminal && duration > 0) {
                    val w = (if (look.id == LookId.Retro) 3.dp else 2.dp).toPx()
                    val f = (live.value / duration).toFloat().coerceIn(0f, 1f)
                    val span = size.width - 2 * corner
                    drawRect(scheme.primary, topLeft = Offset(corner, line), size = Size(span * f, w))
                }
            }
            .clickable(onClickLabel = stringResource(R.string.action_open_player), onClick = onExpand)
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
            }
            // The dock is drawn edge to edge; its content keeps clear of a side navigation bar or cutout in landscape.
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
            .padding(horizontal = if (terminal) 0.dp else look.screenPadding - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            val playLabel = stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play)
            if (terminal) {
                // A status line: "▶ title - artist" and "0:25/4:20", no artwork (Now Playing fades its art in).
                Box(
                    Modifier.size(48.dp).clickable(onClickLabel = playLabel, onClick = vm::playPause).semantics { contentDescription = playLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (s.isPlaying) "▶" else "‖", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                }
                Text(
                    "${track.title} - ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).sharedBounds(rememberSharedContentState("title"), anim),
                )
                StatusTime(live, duration, Modifier.padding(horizontal = 12.dp))
            } else {
                ArtworkFrame(
                    track.artworkUrl,
                    ArtKind.Mini,
                    Modifier.sharedElement(rememberSharedContentState("art"), anim).size(if (look.mini == MiniStyle.DockedLarge) 52.dp else 44.dp),
                )
                // All the width the controls leave; the title scrolls when it does not fit.
                Column(
                    Modifier.weight(1f).padding(horizontal = 12.dp)
                        .sharedBounds(rememberSharedContentState("title"), anim),
                ) {
                    Text(track.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MIconButton(
                    if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    playLabel,
                    onClick = vm::playPause,
                    kind = IconKind.Filled,
                )
                MIconButton(Icons.Filled.SkipNext, stringResource(R.string.action_next), onClick = vm::next)
            }
        }
    }
}

/** "0:25/4:20", recomposed once a second, not every frame. */
@Composable
private fun StatusTime(live: State<Double>, duration: Double, modifier: Modifier) {
    val sec by remember(live) { derivedStateOf { live.value.toInt() } }
    Text(
        "${formatTime(sec.toDouble())}/${formatTime(duration)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** What Now Playing can open or start from the current track; the shell owns navigation. */
private class TrackLinks(
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val radio: (String) -> Unit,
)

/** "Playing on …" with the PC / phone menu, centred in the width the top row leaves it. */
@Composable
private fun TargetChip(connection: ConnectionState, vm: PlayerViewModel) {
    Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        val pcName = (connection as? ConnectionState.Connected)?.pcName
        val knownPc by vm.pcName.collectAsStateWithLifecycle()
        val target by vm.target.collectAsStateWithLifecycle()
        var targetMenu by remember { mutableStateOf(false) }
        Box {
            AssistChip(
                onClick = { targetMenu = true },
                leadingIcon = {
                    Icon(
                        if (connection == ConnectionState.Local) Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                dev.thathunky.meteora.data.PlaybackTarget.entries.forEach { t ->
                    val phone = t == dev.thathunky.meteora.data.PlaybackTarget.Phone
                    DropdownMenuItem(
                        text = { Text(if (phone) stringResource(R.string.target_phone) else knownPc ?: stringResource(R.string.target_pc)) },
                        leadingIcon = { Icon(if (phone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer, null) },
                        trailingIcon = { if (t == target) Icon(Icons.Outlined.Check, null) },
                        onClick = {
                            targetMenu = false
                            vm.setTarget(t)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullPlayer(
    s: PlaybackState,
    connection: ConnectionState,
    vm: PlayerViewModel,
    onCollapse: () -> Unit,
    links: TrackLinks,
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
    // Saveable, so the queue and the lyrics stay open through a rotation.
    var showQueue by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showLyrics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

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
    val look = LocalLook.current
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
            // Full strength in Expressive, half in Soft, none (a flat background) in the others.
            .then(
                if (look.gradients > 0f) Modifier.background(Brush.verticalGradient(listOf(lerp(scheme.surface, scheme.primaryContainer, look.gradients), scheme.surface, scheme.surface)))
                else Modifier.background(scheme.surface),
            )
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
            // The bars and the cutout on every side: in landscape a cutout or a side navigation bar is horizontal.
            // Not the keyboard, which may still be closing after Search.
            Modifier.fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars.union(androidx.compose.foundation.layout.WindowInsets.displayCutout))
                .padding(horizontal = look.screenPadding + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The chip gets what the buttons leave and ellipsizes, so a long PC name (or the
            // Ukrainian label) cannot push the queue and More buttons off the row. In Terminal,
            // "[ lyrics ]" and "[ queue ]" left the chip about 44 dp on a 360 dp phone: they turn
            // into icons when the chip would get less than 120 dp.
            ActionRow(
                listOf(
                    RowAction(compactable = false) {
                        MIconButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_collapse), onClick = onCollapse)
                    },
                    RowAction(weight = 1f, minWidth = 120.dp) { TargetChip(connection, vm) },
                    RowAction(priority = 2) { LyricsButton(onClick = { showLyrics = true }) },
                    RowAction(priority = 1) {
                        MIconButton(
                            Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.queue),
                            onClick = { showQueue = true }, text = stringResource(R.string.queue),
                        )
                    },
                    RowAction(compactable = false) { TrackMenu(track, links) },
                ),
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                arrangement = Arrangement.Start,
                wrap = false,
            )

            Spacer(Modifier.height(16.dp))
            SongVideoSwitch()
            val morphProgress = animateFloatAsState(
                if (s.trackState == TrackState.Playing) 1f else 0f,
                MaterialTheme.motionScheme.slowSpatialSpec(),
                label = "artMorph",
            )
            // The slot is square for the cover and takes the picture's shape in video mode (16:9
            // until the player knows the size; vertical videos stay square). The ratio animates
            // and is read in the layout block only, so the animation relayouts without recomposing.
            val videoVm: VideoViewModel = hiltViewModel()
            val videoOn by videoVm.showVideo.collectAsStateWithLifecycle()
            val picture = pictureAspect(videoVm, videoOn)
            val slotRatio = animateFloatAsState(
                if (videoOn) (picture ?: (16f / 9f)).coerceIn(1f, 2.4f) else 1f,
                MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "slotRatio",
            )
            val slot = Modifier.layout { m, c ->
                // As aspectRatio(): the full width, unless that is too tall for the space.
                val r = slotRatio.value.coerceAtLeast(0.5f)
                var w = c.maxWidth
                var h = (w / r).roundToInt()
                if (h > c.maxHeight) {
                    h = c.maxHeight
                    w = (h * r).roundToInt()
                }
                val p = m.measure(Constraints.fixed(w.coerceAtLeast(0), h.coerceAtLeast(0)))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            // The art takes the space the controls leave, so it shrinks on short or landscape
            // screens instead of pushing the controls off the bottom.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ArtworkFrame(
                    track.artworkUrl,
                    ArtKind.Player,
                    // Terminal's mini player has no art to fly from, so the art just fades in with the player.
                    (if (look.mini == MiniStyle.StatusLine) Modifier else Modifier.sharedElement(rememberSharedContentState("art"), anim))
                        .then(slot),
                    contentDescription = track.album,
                    // Read in the layer block: pause redraws the art without recomposing the player.
                    // Not under the picture: the scalloped morph would be stretched to its shape.
                    playing = if (videoOn) null else ({ morphProgress.value }),
                )
                VideoPanel(slot)
            }

            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth().sharedBounds(rememberSharedContentState("title"), anim)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                ArtistAlbumLine(track, links)
            }

            Spacer(Modifier.height(12.dp))
            // "[ завантажити ] [ не подобається ] [ подобається ]" is about 410 dp in Terminal:
            // Download, then Dislike, turn into icons when the words do not fit.
            ActionRow(
                listOf(
                    RowAction(priority = 2) { dev.thathunky.meteora.ui.downloads.TrackDownloadButton(track) },
                    RowAction(priority = 1) {
                        MIconToggle(
                            checked = track.like == LikeStatus.Dislike,
                            onCheckedChange = { vm.toggleDislike() },
                            icon = Icons.Outlined.ThumbDown,
                            checkedIcon = Icons.Filled.ThumbDown,
                            contentDescription = stringResource(R.string.dislike),
                            text = stringResource(R.string.dislike),
                        )
                    },
                    RowAction(priority = 0) {
                        MIconToggle(
                            checked = track.like == LikeStatus.Like,
                            onCheckedChange = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                vm.toggleLike()
                            },
                            icon = Icons.Outlined.ThumbUp,
                            checkedIcon = Icons.Filled.ThumbUp,
                            contentDescription = stringResource(R.string.like),
                            text = stringResource(R.string.like),
                        )
                    },
                ),
                Modifier.fillMaxWidth(),
                arrangement = Arrangement.End,
                wrap = false,
            )

            SeekBar(s, vm)

            // 8, not 12: the seek bar's 48 dp touch area already leaves room under its times.
            Spacer(Modifier.height(8.dp))
            // Shuffle and repeat keep their icons in Terminal too: their words would not fit the row.
            // Previous and Next are words where they fit ("[ previous ]" alone is 106 dp) and
            // icons, both together, where they do not. On one line whatever happens.
            val side = if (look.textControls) Modifier.height(56.dp) else Modifier.size(64.dp)
            ActionRow(
                listOf(
                    RowAction(priority = 2, compactable = false) {
                        MIconToggle(s.shuffle, { vm.toggleShuffle() }, Icons.Outlined.Shuffle, stringResource(R.string.shuffle))
                    },
                    RowAction(priority = 1) {
                        MIconButton(
                            Icons.Filled.SkipPrevious, stringResource(R.string.action_previous), onClick = vm::previous,
                            kind = IconKind.Tonal, text = stringResource(R.string.action_previous), iconSize = 32.dp,
                            // The icon form of a text control is square, as in the other looks.
                            modifier = if (look.textControls) side.widthIn(min = 56.dp) else side,
                        )
                    },
                    RowAction(priority = 0, compactable = false) {
                        MIconButton(
                            if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play),
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                vm.playPause()
                            },
                            kind = IconKind.Filled,
                            text = if (s.isPlaying) "‖" else "▶",
                            iconSize = 40.dp,
                            modifier = if (look.textControls) Modifier.height(56.dp) else Modifier.size(width = 112.dp, height = 80.dp),
                        )
                    },
                    RowAction(priority = 1) {
                        MIconButton(
                            Icons.Filled.SkipNext, stringResource(R.string.action_next), onClick = vm::next,
                            kind = IconKind.Tonal, text = stringResource(R.string.action_next), iconSize = 32.dp,
                            modifier = if (look.textControls) side.widthIn(min = 56.dp) else side,
                        )
                    },
                    RowAction(priority = 2, compactable = false) {
                        MIconToggle(
                            s.repeat == RepeatMode.All || s.repeat == RepeatMode.One, { vm.cycleRepeat() },
                            if (s.repeat == RepeatMode.One) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            stringResource(R.string.repeat),
                        )
                    },
                ),
                Modifier.fillMaxWidth(),
                arrangement = Arrangement.SpaceBetween,
                wrap = false,
            )

            Spacer(Modifier.height(12.dp))
            // On the phone the hardware keys and system slider already set the volume.
            if (connection != ConnectionState.Local) VolumeRow(s, vm)
            Spacer(Modifier.height(16.dp))
        }
        }
        FullscreenVideo(s, vm)
    }

    if (showLyrics) LyricsScreen(s, vm, onClose = { showLyrics = false })
    if (showQueue) QueueSheet(s, vm, onDismiss = { showQueue = false })
}

/** Start radio, Share, Go to album, Go to artist. The last two only when the track carries the id. */
@Composable
private fun TrackMenu(track: Track, links: TrackLinks) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        MIconButton(Icons.Outlined.MoreVert, stringResource(R.string.action_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_radio)) },
                leadingIcon = { Icon(Icons.Outlined.Radio, null) },
                onClick = { open = false; links.radio(track.videoId) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = {
                    open = false
                    context.shareLink(ShareLink.url(ShareTarget.Video(track.videoId, null)), shareTitle(track.title, track.artist))
                },
            )
            track.albumId?.let { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_go_album)) },
                    leadingIcon = { Icon(Icons.Outlined.Album, null) },
                    onClick = { open = false; links.openAlbum(id) },
                )
            }
            track.artistChannelId?.let { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_go_artist)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    onClick = { open = false; links.openArtist(id) },
                )
            }
            dev.thathunky.meteora.ui.filter.HideArtistMenuItem(track.artist, track.artistChannelId) { open = false }
        }
    }
}

/**
 * "Artist · Album", each part tappable when its page is known. Two texts, each with its own
 * marquee: the short one keeps its full width and the long one scrolls in what is left.
 */
@Composable
private fun ArtistAlbumLine(track: Track, links: TrackLinks) {
    val album = track.album?.takeIf { it.isNotBlank() }
    Layout(
        content = {
            LinePart(track.artist, track.artistChannelId?.let { id -> { links.openArtist(id) } }, stringResource(R.string.action_go_artist))
            if (album != null) {
                LinePart(" · ", null)
                LinePart(album, track.albumId?.let { id -> { links.openAlbum(id) } }, stringResource(R.string.action_go_album))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val max = constraints.maxWidth
        val widths = if (measurables.size < 3 || !constraints.hasBoundedWidth) {
            measurables.map { minOf(it.maxIntrinsicWidth(Constraints.Infinity), max) }
        } else {
            val sep = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
            val a = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
            val b = measurables[2].maxIntrinsicWidth(Constraints.Infinity)
            val room = (max - sep).coerceAtLeast(0)
            val (wa, wb) = when {
                a + b <= room -> a to b
                a <= room / 2 -> a to room - a
                b <= room / 2 -> room - b to b
                else -> room / 2 to room - room / 2
            }
            listOf(wa, sep, wb)
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i].coerceAtLeast(0))) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(if (constraints.hasBoundedWidth) max else placeables.sumOf { it.width }, height) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width
            }
        }
    }
}

@Composable
private fun LinePart(text: String, onClick: (() -> Unit)?, clickLabel: String? = null) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = (if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier).basicMarquee(),
    )
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

    // For TalkBack: the position as a value it can set and step, and ±10 s actions.
    val seekLabel = stringResource(R.string.seek_bar)
    val seekState = stringResource(R.string.seek_state, formatTime(shownSeconds.toDouble()), formatTime(duration))
    val back10 = stringResource(R.string.action_rewind)
    val forward10 = stringResource(R.string.action_forward)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                // 48 dp: the touch target; the bar is drawn smaller in the middle.
                .height(48.dp)
                .clearAndSetSemantics {
                    contentDescription = seekLabel
                    stateDescription = seekState
                    if (duration > 0) {
                        val at = shownSeconds.toFloat().coerceIn(0f, duration.toFloat())
                        progressBarRangeInfo = ProgressBarRangeInfo(at, 0f..duration.toFloat())
                        setProgress { v -> vm.seekTo(v.toDouble().coerceIn(0.0, duration)); true }
                        customActions = listOf(
                            CustomAccessibilityAction(back10) { vm.seekTo((at - 10.0).coerceIn(0.0, duration)); true },
                            CustomAccessibilityAction(forward10) { vm.seekTo((at + 10.0).coerceIn(0.0, duration)); true },
                        )
                    }
                }
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
            MProgress(progress = shown, amplitude = { amplitude })
        }
        // Pulled up into the touch area's spare height, so the bar keeps its old distance to the times.
        Row(Modifier.fillMaxWidth().offset(y = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shownSeconds.toDouble()), style = MaterialTheme.typography.labelMedium)
            // The loader overflows this fixed-height box instead of growing the row, so nothing below moves.
            Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.Center) {
                // Only after a moment of buffering, so a quick seek does not flash it.
                var waiting by remember { mutableStateOf(false) }
                LaunchedEffect(s.trackState) {
                    waiting = false
                    if (s.trackState == TrackState.Buffering) {
                        kotlinx.coroutines.delay(400)
                        waiting = true
                    }
                }
                if (waiting) MLoading(Modifier.requiredSize(32.dp))
            }
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VolumeRow(s: PlaybackState, vm: PlayerViewModel) {
    val override by vm.volumeOverride.collectAsStateWithLifecycle()
    val volumeLabel = stringResource(R.string.volume)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        dev.thathunky.meteora.ui.components.MSlider(
            value = (override ?: s.volume).toFloat(),
            onValueChange = { vm.setVolume(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = volumeLabel },
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
