package dev.thathunky.meteora.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.IconKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MLoading
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.SectionHeader
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import dev.thathunky.meteora.R
import dev.thathunky.meteora.share.share
import dev.thathunky.meteora.ytm.MusicItem

/**
 * Loading, failed (with Try again) or the content. The loading and failed states are centred in
 * the space above the dock, [bottomPadding], so they are not hidden under the mini player.
 */
@Composable
fun <T> Loaded(state: Load<T>, onRetry: (() -> Unit)? = null, bottomPadding: Dp = 0.dp, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) { MLoading() }
        // Without Try again a failed page stays failed until the app restarts.
        is Load.Failed -> MessageState(
            stringResource(R.string.load_failed),
            error = true,
            action = onRetry?.let { stringResource(R.string.action_try_again) to it },
            bottomPadding = bottomPadding,
        )
        is Load.Ready -> content(state.value)
    }
}

/** A centred line of text, and a button under it if there is something to do: empty and failed states. */
@Composable
fun MessageState(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
    bottomPadding: Dp = 0.dp,
) {
    Column(
        modifier.fillMaxSize().padding(bottom = bottomPadding).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) MButton(action.first, action.second, kind = ButtonKind.Tonal)
    }
}

/** Album (browseId MPREb_…) or playlist (playlistId) page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(
    id: String,
    isAlbum: Boolean,
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    bottomPadding: Dp,
    vm: CollectionViewModel = hiltViewModel(),
    actionsVm: ActionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(id, vm) { vm.load(id, isAlbum) }
    val page by vm.page.collectAsStateWithLifecycle()
    val actions = actionsVm.actions
    val haptics = LocalHapticFeedback.current
    Loaded(page, onRetry = { vm.load(id, isAlbum, force = true) }) { p ->
        val pid = p.playlistId ?: if (!isAlbum) id else null
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item {
                Hero(p.thumbnail, p.title, p.subtitle, p.secondSubtitle, round = false)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = LocalLook.current.screenPadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MButton(
                        stringResource(R.string.action_play),
                        onClick = { pid?.let { actions.playCollectionTrack(it, p.tracks.firstOrNull()?.videoId ?: return@let) } },
                        enabled = pid != null && p.tracks.isNotEmpty(),
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1.4f).height(56.dp),
                    )
                    MButton(
                        stringResource(R.string.shuffle),
                        onClick = { pid?.let { actions.shuffleCollection(it, p.tracks.map { t -> t.videoId }) } },
                        enabled = pid != null && p.tracks.isNotEmpty(),
                        kind = ButtonKind.Tonal,
                        icon = Icons.Outlined.Shuffle,
                        modifier = Modifier.weight(1f).height(56.dp),
                    )
                    ShareButton {
                        if (isAlbum) MusicItem.Album(id, p.title, p.subtitle, p.thumbnail, p.playlistId)
                        else MusicItem.Playlist("VL${id.removePrefix("VL")}", p.title, p.subtitle, p.thumbnail, id.removePrefix("VL"))
                    }
                }
            }
            itemsIndexed(p.tracks, key = { i, t -> "$i:${t.videoId}" }) { i, t ->
                MRow(
                    headline = t.title,
                    supporting = t.subtitleText(),
                    leading = {
                        if (isAlbum) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            ArtworkFrame(t.thumbnail, ArtKind.Small, Modifier.size(if (LocalLook.current.id == LookId.Expressive) 48.dp else LocalLook.current.rowArt))
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { if (pid != null) actions.playCollectionTrack(pid, t.videoId) else opener.open(t) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(t)
                        },
                    ),
                )
            }
        }
    }
}

@Composable
fun ArtistScreen(
    browseId: String,
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    bottomPadding: Dp,
    vm: ArtistViewModel = hiltViewModel(),
    actionsVm: ActionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(browseId, vm) { vm.load(browseId) }
    val page by vm.page.collectAsStateWithLifecycle()
    Loaded(page, onRetry = { vm.load(browseId, force = true) }) { p ->
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item {
                Hero(p.thumbnail, p.name, null, null, round = true)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = LocalLook.current.screenPadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    p.shufflePlaylistId?.let { pid ->
                        MButton(
                            stringResource(R.string.shuffle),
                            onClick = { actionsVm.actions.play(MusicItem.Playlist("VL$pid", p.name, "", null, pid)) },
                            icon = Icons.Outlined.Shuffle,
                            modifier = Modifier.weight(1.2f).height(56.dp),
                        )
                    }
                    p.radioPlaylistId?.let { pid ->
                        MButton(
                            stringResource(R.string.action_radio),
                            onClick = { actionsVm.actions.play(MusicItem.Playlist("VL$pid", p.name, "", null, pid)) },
                            kind = ButtonKind.Tonal,
                            icon = Icons.Outlined.Radio,
                            modifier = Modifier.weight(1f).height(56.dp),
                        )
                    }
                    ShareButton { MusicItem.Artist(browseId, p.name, "", p.thumbnail) }
                }
            }
            p.shelves.forEachIndexed { si, shelf ->
                item(key = "h$si") { SectionHeader(shelf.title) }
                if (shelf.items.all { it is MusicItem.Song }) {
                    // distinctBy as in every other list: a repeated song would reuse a lazy key and crash.
                    items(shelf.items.distinctBy { it.key() }.take(5), key = { "s$si:${it.key()}" }) { item ->
                        MusicRow(item, onClick = { opener.open(item) }, onLongClick = { onLongPress(item) })
                    }
                } else {
                    item(key = "c$si") { ShelfRow(shelf.items, opener, onLongPress) }
                }
            }
        }
    }
}

/** The share icon at the end of a page's button row. [item] is built only on tap. */
@Composable
private fun ShareButton(item: () -> MusicItem) {
    val context = LocalContext.current
    MIconButton(
        Icons.Outlined.Share,
        stringResource(R.string.action_share),
        onClick = { context.share(item()) },
        kind = IconKind.Tonal,
        text = stringResource(R.string.action_share),
        modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 56.dp),
    )
}

@Composable
fun ShelfRow(items: List<MusicItem>, opener: Opener, onLongPress: (MusicItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = LocalLook.current.screenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.distinctBy { it.key() }, key = { it.key() }) { item ->
            MusicCard(item, onClick = { opener.open(item) }, onLongClick = { onLongPress(item) })
        }
    }
}

@Composable
private fun Hero(thumbnail: String?, title: String, subtitle: String?, second: String?, round: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val look = LocalLook.current
    Box(
        Modifier.fillMaxWidth()
            // Flat, Terminal and Retro draw no gradient; Soft draws it at half strength.
            .then(
                if (look.gradients > 0f) Modifier.background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = look.gradients), Color.Transparent)))
                else Modifier,
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.fillMaxWidth().padding(look.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            ArtworkFrame(thumbnail, if (round) ArtKind.Artist else ArtKind.Hero, Modifier.fillMaxWidth(0.72f).aspectRatio(1f))
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            second?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
