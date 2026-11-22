package dev.thathunky.meteora.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.MusicActions
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.downloads.DownloadCollection
import dev.thathunky.meteora.downloads.DownloadEntry
import dev.thathunky.meteora.downloads.DownloadManager
import dev.thathunky.meteora.downloads.DownloadState
import dev.thathunky.meteora.downloads.DownloadStatus
import dev.thathunky.meteora.playback.LocalSong
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.SectionHeader
import dev.thathunky.meteora.ui.components.screenTitleStyle
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    val downloads: DownloadManager,
    val actions: MusicActions,
    val smart: dev.thathunky.meteora.downloads.SmartDownloads,
) : ViewModel()

/** Asks the shell to open the Downloads page (from Home's offline note). */
object DownloadsNav {
    private val _open = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val open = _open.asSharedFlow()
    fun request() {
        _open.tryEmit(Unit)
    }
}

/** In the shell: opens the Downloads page when [DownloadsNav] asks. */
@Composable
fun OpenDownloadsEffect(open: () -> Unit) {
    LaunchedEffect(Unit) { DownloadsNav.open.collect { open() } }
}

/** Songs in the Downloads list: by title, in the language's own alphabet (і, ї, є with their letters, not after я). */
internal fun sortedSongs(songs: Collection<DownloadEntry>, locale: Locale): List<DownloadEntry> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return songs.sortedWith { a, b -> collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.videoId.compareTo(b.videoId) }
}

/**
 * Whether removing [e] from the song list really deletes it. A track of a downloaded album or
 * playlist stays for that collection ([dev.thathunky.meteora.downloads.DownloadRules.removeSingle]),
 * so its row offers no delete that would do nothing; it goes with its collection.
 */
internal fun removableFromList(e: DownloadEntry): Boolean = e.collections.isEmpty()

/** For the long-press sheet: whether [item] is in the downloads. */
@Composable
fun rememberDownloaded(item: MusicItem, vm: DownloadsViewModel = hiltViewModel()): Boolean {
    val index = vm.downloads.index
    val flow = remember(item) {
        when (item) {
            is MusicItem.Song -> index.status(item.videoId)
            is MusicItem.Album -> index.collectionFor(item.playlistId, item.browseId)?.let { index.collectionStatus(it.id) } ?: flowOf(DownloadStatus.None)
            is MusicItem.Playlist -> index.collectionFor(item.playlistId, item.browseId)?.let { index.collectionStatus(it.id) } ?: flowOf(DownloadStatus.None)
            is MusicItem.Artist -> flowOf(DownloadStatus.None)
        }
    }
    val status by flow.collectAsStateWithLifecycle(DownloadStatus.None)
    return status != DownloadStatus.None
}

/**
 * The download button: not downloaded, queued, downloading with a percentage, downloaded, failed
 * (tap retries). Tapping a queued or downloading one asks before stopping it; a downloaded one,
 * before removing it. Every state is 48 dp, so the buttons beside it never move.
 */
@Composable
fun DownloadButton(status: DownloadStatus, onDownload: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    val textControls = LocalLook.current.textControls
    val ask = { confirm = true }
    when (status) {
        DownloadStatus.None -> MIconButton(Icons.Outlined.Download, stringResource(R.string.action_download), onDownload, modifier, text = stringResource(R.string.action_download))
        DownloadStatus.Queued -> MIconButton(Icons.Outlined.Schedule, stringResource(R.string.download_queued), ask, modifier, text = stringResource(R.string.download_short_queued))
        is DownloadStatus.Downloading -> {
            val pct = (status.fraction * 100).toInt().coerceIn(0, 100)
            val label = stringResource(R.string.download_progress, pct)
            if (textControls) {
                MIconButton(Icons.Outlined.Download, label, ask, modifier, text = "$pct%")
            } else {
                Box(
                    modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable(onClickLabel = stringResource(R.string.action_stop_download), role = Role.Button, onClick = ask)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(progress = { status.fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("$pct", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        DownloadStatus.Done -> MIconButton(Icons.Filled.DownloadDone, stringResource(R.string.download_done), ask, modifier, text = stringResource(R.string.download_short_done))
        DownloadStatus.Failed -> MIconButton(Icons.Outlined.ErrorOutline, stringResource(R.string.download_failed), onRetry, modifier, text = stringResource(R.string.download_short_failed))
    }
    // Only while there is something to stop or remove: a download removed elsewhere closes it.
    val stopping = status == DownloadStatus.Queued || status is DownloadStatus.Downloading
    if (confirm && (stopping || status == DownloadStatus.Done)) {
        RemoveDialog(
            title = stringResource(if (stopping) R.string.download_stop_title else R.string.action_remove_download),
            text = if (stopping) null else stringResource(R.string.download_remove_text),
            confirm = stringResource(if (stopping) R.string.action_stop_download else R.string.action_delete),
            onConfirm = onRemove,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun RemoveDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Now Playing, next to like: the track playing now. */
@Composable
fun TrackDownloadButton(track: Track, vm: DownloadsViewModel = hiltViewModel()) {
    val d = vm.downloads
    val status by remember(track.videoId) { d.index.status(track.videoId) }.collectAsStateWithLifecycle(DownloadStatus.None)
    DownloadButton(
        status,
        onDownload = { d.downloadSong(LocalSong(track.videoId, track.title, track.artist, track.album, track.albumId, track.artistChannelId, track.durationSec, track.artworkUrl)) },
        onRemove = { d.removeSong(track.videoId) },
        onRetry = { d.retry(track.videoId) },
    )
}

/** Album and playlist page headers, next to Play and Shuffle. [playlistId]: the collection's id; [browseId]: its page. */
@Composable
fun CollectionDownloadButton(page: CollectionPage, playlistId: String, browseId: String, isAlbum: Boolean, vm: DownloadsViewModel = hiltViewModel()) {
    val d = vm.downloads
    val collections by d.index.collections.collectAsStateWithLifecycle()
    val id = d.index.collectionFor(playlistId, browseId)?.id ?: playlistId
    val status by remember(id, collections.containsKey(id)) {
        if (collections.containsKey(id)) d.index.collectionStatus(id) else flowOf(DownloadStatus.None)
    }.collectAsStateWithLifecycle(DownloadStatus.None)
    DownloadButton(
        status,
        onDownload = { d.downloadCollection(page, playlistId, browseId, isAlbum) },
        onRemove = { d.removeCollection(id) },
        // Asking again queues the tracks that failed.
        onRetry = { d.downloadCollection(page, playlistId, browseId, isAlbum) },
    )
}

/** In the shell: a short sheet when a download was asked for without Premium. */
@Composable
fun DownloadsPremiumHost(vm: DownloadsViewModel = hiltViewModel()) {
    var shown by remember { mutableStateOf<DownloadManager.Blocked?>(null) }
    LaunchedEffect(Unit) { vm.downloads.blocked.collect { shown = it } }
    val why = shown ?: return
    AlertDialog(
        onDismissRequest = { shown = null },
        icon = { Icon(Icons.Outlined.Download, null) },
        title = { Text(stringResource(R.string.premium_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(if (why == DownloadManager.Blocked.SignedOut) R.string.premium_signed_out else R.string.premium_text)) },
        confirmButton = { TextButton({ shown = null }) { Text(stringResource(R.string.action_got_it)) } },
    )
}

/** Home with no network: a note and a way to the downloads instead of an error. True if it was shown. */
@Composable
fun OfflineHomeNote(vm: DownloadsViewModel = hiltViewModel()): Boolean {
    if (!remember { vm.downloads.offline() }) return false
    // Scrollable, so Home's pull to refresh still works here once the network is back.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(Icons.Outlined.CloudOff, stringResource(R.string.offline_title), stringResource(R.string.offline_text)) {
            MButton(stringResource(R.string.action_open_downloads), DownloadsNav::request, kind = ButtonKind.Tonal)
        }
    }
    return true
}

/** An icon, a title, a sentence and an action, centred: empty pages and the offline note. */
@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        action?.invoke()
    }
}

/** The Downloads page (its own route, from the offline note): the tab under a title. */
@Composable
fun DownloadsScreen(bottomPadding: Dp) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.library_downloads),
            style = screenTitleStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LocalLook.current.screenPadding, vertical = 16.dp),
        )
        DownloadsTab(bottomPadding)
    }
}

/** What the Downloads list asks the user to confirm. */
private sealed interface Removal {
    data class Song(val e: DownloadEntry) : Removal
    data class Collection(val c: DownloadCollection) : Removal
}

/**
 * Library's Downloads tab: collections, then songs, from the index. Tap plays from the files; a
 * failed song's tap tries again. Removing asks first. Each row reads its own song's progress, so
 * a download in progress redraws its row, not the list.
 */
@Composable
fun DownloadsTab(bottomPadding: Dp, vm: DownloadsViewModel = hiltViewModel()) {
    val d = vm.downloads
    val songs by d.index.songs.collectAsStateWithLifecycle()
    val collections by d.index.collections.collectAsStateWithLifecycle()
    val look = LocalLook.current
    val locale = LocalConfiguration.current.locales[0]
    var removing by remember { mutableStateOf<Removal?>(null) }
    if (songs.isEmpty() && collections.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp).padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.Download, stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_hint))
        }
        return
    }
    val sortedCollections = remember(collections) { collections.values.sortedByDescending { it.addedAtMs } }
    val sortedSongs = remember(songs, locale) { sortedSongs(songs.values, locale) }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (sortedCollections.isNotEmpty()) {
            item(key = "h:c") { SectionHeader(stringResource(R.string.downloads_collections)) }
            items(sortedCollections, key = { "c:${it.id}" }) { c ->
                val tracks = c.videoIds.mapNotNull { songs[it] }
                val status = DownloadStatus.ofCollection(tracks)
                val done = tracks.count { it.state == DownloadState.Done }
                MRow(
                    headline = c.title,
                    supporting = if (status == DownloadStatus.Done) pluralStringResource(R.plurals.downloads_song_count, done, done) else statusText(status),
                    leading = { ArtworkFrame(c.thumbnail, ArtKind.Small, Modifier.size(look.rowArt)) },
                    trailing = { MIconButton(Icons.Outlined.Delete, stringResource(R.string.action_remove_download), { removing = Removal.Collection(c) }) },
                    modifier = Modifier.clickable(enabled = done > 0) { vm.actions.playDownloaded(c.id, null) },
                )
            }
        }
        if (sortedSongs.isNotEmpty()) {
            item(key = "h:s") { SectionHeader(stringResource(R.string.downloads_songs) + " · " + sortedSongs.size) }
            items(sortedSongs, key = { "s:${it.videoId}" }) { e -> SongRow(e, vm, onRemove = { removing = Removal.Song(e) }) }
        }
    }
    when (val r = removing) {
        is Removal.Song -> RemoveDialog(
            title = stringResource(R.string.action_remove_download),
            text = r.e.title + "\n" + stringResource(R.string.download_remove_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { d.removeSong(r.e.videoId) },
            onDismiss = { removing = null },
        )
        is Removal.Collection -> RemoveDialog(
            title = stringResource(R.string.action_remove_download),
            text = r.c.title + "\n" + stringResource(R.string.download_remove_collection_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { d.removeCollection(r.c.id) },
            onDismiss = { removing = null },
        )
        null -> Unit
    }
}

@Composable
private fun SongRow(e: DownloadEntry, vm: DownloadsViewModel, onRemove: () -> Unit) {
    val d = vm.downloads
    val look = LocalLook.current
    // This row's progress only; the list above does not read it.
    val status by remember(e.videoId) { d.index.status(e.videoId) }.collectAsStateWithLifecycle(DownloadStatus.of(e, null))
    MRow(
        headline = e.title,
        supporting = listOfNotNull(e.artist.ifBlank { null }, statusText(status).takeIf { status != DownloadStatus.Done }).joinToString(" · "),
        leading = { ArtworkFrame(e.artworkUrl, ArtKind.Small, Modifier.size(look.rowArt)) },
        trailing = {
            if (removableFromList(e)) MIconButton(Icons.Outlined.Delete, stringResource(R.string.action_remove_download), onRemove)
        },
        modifier = Modifier.clickable(enabled = e.state == DownloadState.Done || e.state == DownloadState.Failed) {
            when (e.state) {
                DownloadState.Done -> vm.actions.playDownloaded(null, e.videoId)
                DownloadState.Failed -> d.retry(e.videoId)
                else -> Unit
            }
        },
    )
}

@Composable
private fun statusText(s: DownloadStatus): String = when (s) {
    DownloadStatus.None -> ""
    DownloadStatus.Queued -> stringResource(R.string.download_queued)
    is DownloadStatus.Downloading -> stringResource(R.string.download_progress, (s.fraction * 100).toInt())
    DownloadStatus.Done -> stringResource(R.string.download_done)
    DownloadStatus.Failed -> stringResource(R.string.download_failed)
}
