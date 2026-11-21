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
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ytm.MusicItem

@Composable
fun <T> Loaded(state: Load<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        is Load.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.load_failed), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        is Load.Ready -> content(state.value)
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
    LaunchedEffect(id) { vm.load(id, isAlbum) }
    val page by vm.page.collectAsStateWithLifecycle()
    val actions = actionsVm.actions
    Loaded(page) { p ->
        val pid = p.playlistId ?: if (!isAlbum) id else null
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item {
                Hero(p.thumbnail, p.title, p.subtitle, p.secondSubtitle, round = false)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { pid?.let { actions.playCollectionTrack(it, p.tracks.firstOrNull()?.videoId ?: return@let) } },
                        enabled = pid != null && p.tracks.isNotEmpty(),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1.4f).height(56.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_play))
                    }
                    FilledTonalButton(
                        onClick = { pid?.let { actions.shuffleCollection(it, p.tracks.map { t -> t.videoId }) } },
                        enabled = pid != null && p.tracks.isNotEmpty(),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Outlined.Shuffle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shuffle))
                    }
                }
            }
            itemsIndexed(p.tracks, key = { i, t -> "$i:${t.videoId}" }) { i, t ->
                ListItem(
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(t.subtitleText(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        if (isAlbum) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            AsyncImage(t.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.combinedClickable(
                        onClick = { if (pid != null) actions.playCollectionTrack(pid, t.videoId) else opener.open(t) },
                        onLongClick = { onLongPress(t) },
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
    LaunchedEffect(browseId) { vm.load(browseId) }
    val page by vm.page.collectAsStateWithLifecycle()
    Loaded(page) { p ->
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            item {
                Hero(p.thumbnail, p.name, null, null, round = true)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    p.shufflePlaylistId?.let { pid ->
                        Button(
                            onClick = { actionsVm.actions.play(MusicItem.Playlist("VL$pid", p.name, "", null, pid)) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1.2f).height(56.dp),
                        ) {
                            Icon(Icons.Outlined.Shuffle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                    p.radioPlaylistId?.let { pid ->
                        FilledTonalButton(
                            onClick = { actionsVm.actions.play(MusicItem.Playlist("VL$pid", p.name, "", null, pid)) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Icon(Icons.Outlined.Radio, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_radio))
                        }
                    }
                }
            }
            p.shelves.forEachIndexed { si, shelf ->
                item(key = "h$si") {
                    Text(
                        shelf.title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
                if (shelf.items.all { it is MusicItem.Song }) {
                    items(shelf.items.take(5), key = { "s$si:${it.key()}" }) { item ->
                        MusicRow(item, onClick = { opener.open(item) }, onLongClick = { onLongPress(item) })
                    }
                } else {
                    item(key = "c$si") { ShelfRow(shelf.items, opener, onLongPress) }
                }
            }
        }
    }
}

@Composable
fun ShelfRow(items: List<MusicItem>, opener: Opener, onLongPress: (MusicItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.distinctBy { it.key() }, key = { it.key() }) { item ->
            MusicCard(item, onClick = { opener.open(item) }, onLongClick = { onLongPress(item) })
        }
    }
}

@Composable
private fun Hero(thumbnail: String?, title: String, subtitle: String?, second: String?, round: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, Color.Transparent))),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f)
                    .clip(if (round) CircleShape else MaterialShapes.Cookie12Sided.toShape()),
            )
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
