package dev.thathunky.meteora.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.MusicActions
import dev.thathunky.meteora.domain.Track
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
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
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
 * (tap retries). Tapping a downloaded one asks before removing it.
 */
@Composable
fun DownloadButton(status: DownloadStatus, onDownload: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    when (status) {
        DownloadStatus.None -> MIconButton(Icons.Outlined.Download, stringResource(R.string.action_download), onDownload, modifier, text = stringResource(R.string.action_download))
        DownloadStatus.Queued -> MIconButton(Icons.Outlined.Schedule, stringResource(R.string.download_queued), { confirm = true }, modifier, text = stringResource(R.string.download_queued))
        is DownloadStatus.Downloading -> {
            val pct = (status.fraction * 100).toInt().coerceIn(0, 100)
            Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { status.fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(32.dp))
                Text("$pct", style = MaterialTheme.typography.labelSmall)
            }
        }
        DownloadStatus.Done -> MIconButton(Icons.Filled.DownloadDone, stringResource(R.string.download_done), { confirm = true }, modifier, text = stringResource(R.string.download_done))
        DownloadStatus.Failed -> MIconButton(Icons.Outlined.ErrorOutline, stringResource(R.string.download_failed), onRetry, modifier, text = stringResource(R.string.download_failed))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.action_remove_download)) },
            confirmButton = { TextButton({ confirm = false; onRemove() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
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
        title = { Text(stringResource(R.string.premium_title)) },
        text = { Text(stringResource(if (why == DownloadManager.Blocked.SignedOut) R.string.premium_signed_out else R.string.premium_text)) },
        confirmButton = { TextButton({ shown = null }) { Text(stringResource(R.string.action_got_it)) } },
    )
}

/** Home with no network: a note and a way to the downloads instead of an error. True if it was shown. */
@Composable
fun OfflineHomeNote(vm: DownloadsViewModel = hiltViewModel()): Boolean {
    if (!remember { vm.downloads.offline() }) return false
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(48.dp))
        Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.offline_text), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        MButton(stringResource(R.string.action_open_downloads), DownloadsNav::request, kind = ButtonKind.Tonal)
    }
    return true
}

/** The Downloads page (its own route, from the offline note). */
@Composable
fun DownloadsScreen(bottomPadding: Dp) = DownloadsTab(bottomPadding)

/** Library's Downloads tab: collections, then songs, from the index. Tap plays from the files. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsTab(bottomPadding: Dp, vm: DownloadsViewModel = hiltViewModel()) {
    val d = vm.downloads
    val songs by d.index.songs.collectAsStateWithLifecycle()
    val collections by d.index.collections.collectAsStateWithLifecycle()
    val progress by d.index.progress.collectAsStateWithLifecycle()
    val look = LocalLook.current
    if (songs.isEmpty() && collections.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.downloads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (collections.isNotEmpty()) {
            item { Header(stringResource(R.string.downloads_collections)) }
            items(collections.values.sortedByDescending { it.addedAtMs }, key = { "c:${it.id}" }) { c ->
                val status = DownloadStatus.ofCollection(c.videoIds.mapNotNull { songs[it] })
                MRow(
                    headline = c.title,
                    supporting = statusText(status),
                    leading = { ArtworkFrame(c.thumbnail, ArtKind.Small, Modifier.size(look.rowArt)) },
                    trailing = { MIconButton(Icons.Outlined.Delete, stringResource(R.string.action_remove_download), { d.removeCollection(c.id) }) },
                    modifier = Modifier.combinedClickable(onClick = { vm.actions.playDownloaded(c.id, null) }),
                )
            }
        }
        item { Header(stringResource(R.string.downloads_songs)) }
        items(songs.values.sortedBy { it.title.lowercase() }, key = { "s:${it.videoId}" }) { e ->
            val status = DownloadStatus.of(e, progress[e.videoId])
            MRow(
                headline = e.title,
                supporting = listOfNotNull(e.artist, statusText(status).takeIf { e.state != DownloadState.Done }).joinToString(" · "),
                leading = { ArtworkFrame(e.artworkUrl, ArtKind.Small, Modifier.size(look.rowArt)) },
                trailing = {
                    if (e.state == DownloadState.Failed) MIconButton(Icons.Outlined.ErrorOutline, stringResource(R.string.download_failed), { d.retry(e.videoId) })
                    else MIconButton(Icons.Outlined.Delete, stringResource(R.string.action_remove_download), { d.removeSong(e.videoId) })
                },
                modifier = Modifier.combinedClickable(onClick = { if (e.state == DownloadState.Done) vm.actions.playDownloaded(null, e.videoId) }),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = LocalLook.current.screenPadding, vertical = 12.dp),
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
