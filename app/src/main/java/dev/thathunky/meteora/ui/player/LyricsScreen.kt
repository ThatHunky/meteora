package dev.thathunky.meteora.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** After the user scrolls the lyrics by hand, they stay where they were put this long. */
private const val FOLLOW_PAUSE_MS = 4_000L

/**
 * The lyrics page, over Now Playing. Timed lyrics follow the music (current line in the upper
 * third, past lines dimmed) and a tap on a line seeks there. Works for both targets: the lines
 * come from YouTube by videoId, the position from [s], the seek through [player].
 */
@Composable
fun LyricsScreen(s: PlaybackState, player: PlayerViewModel, onClose: () -> Unit, vm: LyricsViewModel = hiltViewModel()) {
    val track = s.track ?: return
    LaunchedEffect(track.videoId) { vm.load(track) }
    BackHandler(onBack = onClose)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui?.takeIf { it.first == track.videoId }?.second ?: LyricsUi.Loading

    // The position changes every frame while playing; only the derived line index reaches composition.
    val live = rememberLiveProgress(s)
    val override = player.seekOverride.collectAsStateWithLifecycle()
    val positionMs: () -> Long = { ((override.value ?: live.value) * 1000).toLong() }

    val scheme = MaterialTheme.colorScheme
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
                    Modifier.fillMaxWidth().padding(horizontal = LocalLook.current.screenPadding, vertical = 8.dp),
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
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state) {
                        LyricsUi.Loading -> MLoading()
                        LyricsUi.None -> Message(stringResource(R.string.lyrics_none))
                        LyricsUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Message(stringResource(R.string.lyrics_error))
                            MButton(stringResource(R.string.action_retry), onClick = { vm.load(track, force = true) }, kind = ButtonKind.Tonal)
                        }
                        is LyricsUi.Found -> {
                            val lyrics = state.lyrics
                            val index = remember(lyrics) {
                                derivedStateOf { if (lyrics.synced) currentLine(lyrics.lines, positionMs()) else -1 }
                            }
                            LyricsList(lyrics, index, onSeek = { ms -> player.seekTo(ms / 1000.0) })
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

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/** Its own composable: it recomposes when the current line changes, not with every position report. */
@Composable
private fun LyricsList(lyrics: Lyrics, index: State<Int>, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val look = LocalLook.current
    // When the user last scrolled by hand. Programmatic scrolls do not pass through nested scroll.
    val userScrollAt = remember { mutableLongStateOf(0L) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                userScrollAt.longValue = SystemClock.uptimeMillis()
                return Offset.Zero
            }
        }
    }
    if (lyrics.synced) FollowCurrentLine(listState, index, userScrollAt)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Timed lines: room above and below so the current one can sit in the upper third.
        val padding = if (lyrics.synced) PaddingValues(top = maxHeight / 3, bottom = maxHeight / 2)
        else PaddingValues(vertical = 16.dp)
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(userScroll),
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(if (lyrics.synced) 14.dp else 4.dp),
        ) {
            if (!lyrics.synced) {
                item(key = "note") {
                    Text(
                        stringResource(R.string.lyrics_not_synced),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = look.screenPadding + 8.dp).padding(bottom = 12.dp),
                    )
                }
            }
            itemsIndexed(lyrics.lines) { i, line ->
                LyricRow(line, i, index, lyrics.synced, onSeek)
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

/** Keeps the current line in view, except for a few seconds after the user scrolled. */
@Composable
private fun FollowCurrentLine(listState: LazyListState, index: State<Int>, userScrollAt: State<Long>) {
    LaunchedEffect(listState) {
        var first = true
        snapshotFlow { index.value }.collectLatest { i ->
            if (i < 0) return@collectLatest
            while (true) {
                val wait = userScrollAt.value + FOLLOW_PAUSE_MS - SystemClock.uptimeMillis()
                if (wait <= 0) break
                delay(wait)
            }
            // The note row is absent for timed lyrics, so line i is item i.
            if (first) listState.scrollToItem(i) else listState.animateScrollToItem(i)
            first = false
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, i: Int, index: State<Int>, synced: Boolean, onSeek: (Long) -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val terminal = look.id == LookId.Terminal
    // Each row reads the index itself, so only rows whose state changes recompose.
    val current by remember(i) { derivedStateOf { index.value == i } }
    val past by remember(i) { derivedStateOf { index.value > i } }
    val color by animateColorAsState(
        when {
            !synced -> scheme.onSurface
            current -> scheme.primary.takeIf { terminal } ?: scheme.onSurface
            past -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
            else -> scheme.onSurfaceVariant
        },
        label = "lyricColor",
    )
    val text = line.text.ifBlank { if (synced) "♪" else "" }
    val start = line.startMs
    Text(
        // Terminal marks the current line with a prompt as well as colour; the others keep its width.
        if (terminal && synced) (if (current) "> " else "  ") + text else text,
        style = if (synced) MaterialTheme.typography.headlineSmall.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold)
        else MaterialTheme.typography.titleLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (synced && start != null) Modifier.clickable { onSeek(start) } else Modifier)
            .padding(horizontal = look.screenPadding + 8.dp, vertical = 2.dp),
    )
}
