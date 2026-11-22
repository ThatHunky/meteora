package dev.thathunky.meteora.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.VerticalAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.domain.LyricLine
import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.LyricsProvider
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.currentLine
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MLoading
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** After the user scrolls the lyrics by hand, they stay where they were put this long. */
private const val FOLLOW_PAUSE_MS = 4_000L

/** Further than this many lines (a seek), the list jumps to the current line instead of sliding. */
private const val SLIDE_MAX_LINES = 6

/** The list item to keep at the reading position: the current line, or the first before it starts. */
internal fun followItem(index: Int): Int = index.coerceAtLeast(0)

/** Whether following from item [from] to [to] slides (a line or two) or jumps (a seek). */
internal fun followSlides(from: Int, to: Int): Boolean = abs(to - from) <= SLIDE_MAX_LINES

/**
 * The lyrics page, over Now Playing. Timed lyrics follow the music (current line in the upper
 * third, past lines dimmed) and a tap on a line seeks there. Works for both targets: the lines
 * come from YouTube by videoId, the position from [s], the seek through [player].
 */
@Composable
fun LyricsScreen(s: PlaybackState, player: PlayerViewModel, onClose: () -> Unit, vm: LyricsViewModel = hiltViewModel()) {
    val track = s.track ?: return
    // The length may arrive just after the track starts; LRCLIB needs it (the view model skips repeats).
    LaunchedEffect(track.videoId, track.durationSec > 0) { vm.load(track) }
    BackHandler(onBack = onClose)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui?.takeIf { it.first == track.videoId }?.second ?: LyricsUi.Loading

    // The position changes every frame while playing; only the derived line index reaches composition.
    val live = rememberLiveProgress(s)
    val override = player.seekOverride.collectAsStateWithLifecycle()
    val positionMs: () -> Long = { ((override.value ?: live.value) * 1000).toLong() }
    // Per track: a new song starts at its own top, not where the last one was scrolled to.
    val follow = remember(track.videoId) { FollowState() }

    val scheme = MaterialTheme.colorScheme
    val look = LocalLook.current
    val synced = (state as? LyricsUi.Found)?.lyrics?.synced == true
    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            // Taps and drags the lines do not take stop here, not on Now Playing underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = look.screenPadding - 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MIconButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_close_lyrics), onClick = onClose)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Always there for timed lyrics, so the header never shifts; usable once scrolled away.
                    if (synced) {
                        MIconButton(
                            Icons.Outlined.VerticalAlignCenter, stringResource(R.string.action_lyrics_follow),
                            onClick = follow::resume, enabled = follow.away,
                        )
                    }
                    MIconButton(
                        if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (s.isPlaying) R.string.action_pause else R.string.action_play),
                        onClick = player::playPause,
                        text = if (s.isPlaying) "‖" else "▶",
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state) {
                        LyricsUi.Loading -> MLoading()
                        LyricsUi.None -> Message(Icons.Outlined.Lyrics, stringResource(R.string.lyrics_none))
                        LyricsUi.Failed -> Message(Icons.Outlined.ErrorOutline, stringResource(R.string.lyrics_error)) {
                            MButton(stringResource(R.string.action_retry), onClick = { vm.load(track, force = true) }, kind = ButtonKind.Tonal)
                        }
                        is LyricsUi.Found -> key(track.videoId) {
                            val lyrics = state.lyrics
                            val index = remember(lyrics) {
                                derivedStateOf { if (lyrics.synced) currentLine(lyrics.lines, positionMs()) else -1 }
                            }
                            LyricsList(lyrics, index, follow, onSeek = { ms -> player.seekTo(ms / 1000.0) })
                        }
                    }
                }
            }
        }
    }
}

/** The Now Playing button that opens [LyricsScreen]. */
@Composable
fun LyricsButton(onClick: () -> Unit) {
    val label = stringResource(R.string.action_lyrics)
    MIconButton(Icons.Outlined.Lyrics, label, onClick = onClick, text = label)
}

/** An empty or error state: an icon, a sentence and, when given, what to do about it. */
@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
        action?.invoke()
    }
}

/**
 * Whether the list follows the current line. A hand scroll pauses it for [FOLLOW_PAUSE_MS];
 * a tap on a line or the header button resumes it at once.
 */
@Stable
private class FollowState {
    /** When the user last scrolled by hand. Programmatic scrolls do not pass through nested scroll. */
    var userScrollAt by mutableLongStateOf(0L)
    /** The user has scrolled the current line away and it has not come back yet. */
    var away by mutableStateOf(false)
    /** Bumped by [resume], so the follower acts even when the line has not changed. */
    var resumes by mutableIntStateOf(0)

    fun scrolledByHand() {
        userScrollAt = SystemClock.uptimeMillis()
        if (!away) away = true
    }

    fun resume() {
        userScrollAt = 0L
        resumes++
    }
}

/** Its own composable: it recomposes when the current line changes, not with every position report. */
@Composable
private fun LyricsList(lyrics: Lyrics, index: State<Int>, follow: FollowState, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val look = LocalLook.current
    val userScroll = remember(follow) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A finger on the list; the fling after it counts through its drag.
                if (source == NestedScrollSource.UserInput) follow.scrolledByHand()
                return Offset.Zero
            }
        }
    }
    if (lyrics.synced) FollowCurrentLine(listState, index, follow)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Timed lines: room above and below so the current one can sit in the upper third.
        val padding = if (lyrics.synced) PaddingValues(top = maxHeight / 3, bottom = maxHeight / 2)
        else PaddingValues(top = 8.dp, bottom = 24.dp)
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(userScroll),
            state = listState,
            contentPadding = padding,
            // Timed rows carry their own spacing inside the tap area, so there are no dead gaps.
            verticalArrangement = Arrangement.spacedBy(if (lyrics.synced) 0.dp else 2.dp),
        ) {
            if (!lyrics.synced) {
                item(key = "note") {
                    Text(
                        stringResource(R.string.lyrics_not_synced),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = look.screenPadding + 8.dp).padding(bottom = 16.dp),
                    )
                }
            }
            itemsIndexed(lyrics.lines) { i, line ->
                if (lyrics.synced) {
                    LyricRow(line, i, index, onSeek = { ms -> follow.resume(); onSeek(ms) })
                } else if (line.text.isBlank()) {
                    // A blank line between verses: a paragraph gap, not an empty row.
                    Spacer(Modifier.height(16.dp))
                } else {
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = look.screenPadding + 8.dp),
                    )
                }
            }
            item(key = "source") {
                val source = when (lyrics.provider) {
                    LyricsProvider.LrcLib -> stringResource(R.string.lyrics_source, "LRCLIB")
                    LyricsProvider.YouTube -> lyrics.source
                }
                if (source != null) {
                    Text(
                        source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = look.screenPadding + 8.dp).padding(top = 24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Keeps the current line at the reading position, except for a few seconds after the user
 * scrolled. Before the first line (a seek back to the start) it brings the first line back.
 */
@Composable
private fun FollowCurrentLine(listState: LazyListState, index: State<Int>, follow: FollowState) {
    LaunchedEffect(listState) {
        var first = true
        // A hand scroll restarts the wait, so the list comes back FOLLOW_PAUSE_MS after the last touch.
        snapshotFlow { Triple(index.value, follow.resumes, follow.userScrollAt) }.collectLatest { (i, _, scrolledAt) ->
            val wait = scrolledAt + FOLLOW_PAUSE_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            // The note row is absent for timed lyrics, so line i is item i.
            val target = followItem(i)
            if (first || !followSlides(listState.firstVisibleItemIndex, target)) listState.scrollToItem(target)
            else listState.animateScrollToItem(target)
            first = false
            follow.away = false
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, i: Int, index: State<Int>, onSeek: (Long) -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val terminal = look.id == LookId.Terminal
    // Each row reads the index itself, so only rows whose state changes recompose.
    val current by remember(i) { derivedStateOf { index.value == i } }
    val past by remember(i) { derivedStateOf { index.value > i } }
    val color by animateColorAsState(
        when {
            current -> if (terminal) scheme.primary else scheme.onSurface
            past -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
            else -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "lyricColor",
    )
    // Emphasis never changes the layout (same weight, same width), so the list does not jump when
    // the line changes. Expressive motion looks also bring the current line up in size, drawn only.
    val scale by animateFloatAsState(
        if (current || !look.expressiveMotion) 1f else 0.94f,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "lyricScale",
    )
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    val start = line.startMs
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (start != null) Modifier.clickable(onClickLabel = stringResource(R.string.action_lyrics_seek), role = Role.Button) { onSeek(start) }
                else Modifier,
            )
            // 8 dp above and below: a one-line row is a 48 dp target.
            .padding(horizontal = look.screenPadding + 8.dp, vertical = 8.dp),
    ) {
        // Terminal marks the current line with a prompt as well as colour; a wrapped line stays
        // indented under its text. The marker is drawing only, not read out.
        if (terminal) Text(if (current) "> " else "  ", style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
        Text(
            line.text.ifBlank { "♪" },
            style = style,
            color = color,
            modifier = Modifier.weight(1f).graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        )
    }
}
