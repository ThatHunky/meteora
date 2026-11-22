package dev.thathunky.meteora.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.downloads.DownloadCollection
import dev.thathunky.meteora.downloads.DownloadEntry
import dev.thathunky.meteora.downloads.DownloadState
import dev.thathunky.meteora.downloads.DownloadStatus
import dev.thathunky.meteora.library.DownloadFilter
import dev.thathunky.meteora.library.DownloadGroup
import dev.thathunky.meteora.library.DownloadRow
import dev.thathunky.meteora.library.DownloadsLibrary
import dev.thathunky.meteora.library.DownloadsQuery
import dev.thathunky.meteora.library.DownloadsView
import dev.thathunky.meteora.library.LetterIndex
import dev.thathunky.meteora.library.LibraryChoices
import dev.thathunky.meteora.library.LibraryCollation
import dev.thathunky.meteora.library.smartOnly
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.GroupHeader
import dev.thathunky.meteora.ui.components.IndexScroller
import dev.thathunky.meteora.ui.components.ListSearchField
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.MenuAction
import dev.thathunky.meteora.ui.components.MenuChoice
import dev.thathunky.meteora.ui.components.OverflowMenu
import dev.thathunky.meteora.ui.components.SelectionBar
import dev.thathunky.meteora.ui.components.SelectionMark
import dev.thathunky.meteora.ui.components.SortGroupBar
import dev.thathunky.meteora.ui.components.indexScrollerGap
import dev.thathunky.meteora.ui.library.filterChoice
import dev.thathunky.meteora.ui.library.groupChoice
import dev.thathunky.meteora.ui.library.labelText
import dev.thathunky.meteora.ui.library.sizeText
import dev.thathunky.meteora.ui.library.sortChoice
import dev.thathunky.meteora.ui.theme.LocalLook
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The search field appears once the list is this long. */
private const val SEARCH_FROM = 30

/** What a confirmation dialog is about. */
private sealed interface Confirm {
    data class Song(val e: DownloadEntry) : Confirm
    data class Collection(val c: DownloadCollection) : Confirm
    data class Bulk(val remove: List<String>, val kept: Int) : Confirm
    data object Kept : Confirm
}

/**
 * The Downloads list, grouped and sorted the way it was left (docs/library-design.md). The rows
 * are built off the main thread by [DownloadsLibrary]; each song row still reads only its own
 * progress. Long-press selects; the bar then offers a bulk Remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadsList(
    songs: Map<String, DownloadEntry>,
    collections: Map<String, DownloadCollection>,
    vm: DownloadsViewModel,
    bottomPadding: Dp,
) {
    val d = vm.downloads
    val prefs = vm.prefs
    val look = LocalLook.current
    val locale = LocalConfiguration.current.locales[0]
    val collation = remember(locale) { LibraryCollation(locale) }
    var group by remember { mutableStateOf(prefs.downloadGroup()) }
    var sort by remember(group) { mutableStateOf(prefs.downloadSort(group)) }
    var filter by remember { mutableStateOf(prefs.downloadFilter()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var collectionsOpen by rememberSaveable { mutableStateOf(true) }
    val field = rememberTextFieldState()
    val query by remember { derivedStateOf { field.text.toString() } }
    val selection = remember { mutableStateSetOf<String>() }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val q = DownloadsQuery(group, sort, filter, query, expanded, collectionsOpen)
    val view by produceState<DownloadsView?>(null, songs, collections, q, collation) {
        value = withContext(Dispatchers.Default) {
            DownloadsLibrary.build(songs.values, collections.values, q, collation, System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }
    val v = view ?: return
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { selection.clear() }

    // Items before the grouped rows: the summary, the search field, the bar.
    val showSearch = v.totalSongs > SEARCH_FROM
    val lead = 2 + (if (showSearch) 1 else 0)
    val showIndex = LetterIndex.worthShowing(v.index, v.rows.size) && !selecting
    val gap = indexScrollerGap(showIndex)

    fun toggle(id: String) {
        if (!selection.remove(id)) selection.add(id)
    }
    fun toggleAll(ids: List<String>) {
        if (ids.all { it in selection }) selection.removeAll(ids.toSet()) else selection.addAll(ids)
    }
    fun startSelect(ids: List<String>) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        toggleAll(ids)
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = pluralStringResource(R.plurals.lib_selected, selection.size, selection.size),
                onClose = { selection.clear() },
                onSelectAll = { selection.addAll(v.shownIds) },
                action = stringResource(R.string.lib_remove),
                onAction = {
                    val (remove, kept) = DownloadsLibrary.removal(songs, selection.toList())
                    confirm = if (remove.isEmpty()) Confirm.Kept else Confirm.Bulk(remove, kept)
                },
            )
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding, end = gap),
            ) {
                item(key = "summary", contentType = "summary") {
                    Text(
                        pluralStringResource(R.plurals.downloads_song_count, v.totalSongs, v.totalSongs) + " · " + sizeText(v.totalBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = look.screenPadding, vertical = 4.dp),
                    )
                }
                if (showSearch) item(key = "search", contentType = "search") {
                    ListSearchField(field, stringResource(R.string.lib_search_downloads))
                }
                item(key = "bar", contentType = "bar") {
                    SortGroupBar {
                        MenuChoice(
                            stringResource(R.string.lib_group),
                            DownloadGroup.entries.map { groupChoice(it) },
                            group,
                            onSelect = {
                                group = it
                                prefs.setDownloadGroup(it)
                            },
                        )
                        val sorts = LibraryChoices.sortsFor(group)
                        if (sorts.isNotEmpty()) {
                            MenuChoice(
                                stringResource(R.string.lib_sort),
                                sorts.map { sortChoice(it, names = group == DownloadGroup.Artists) },
                                sort,
                                onSelect = {
                                    sort = it
                                    prefs.setDownloadSort(group, it)
                                },
                            )
                        }
                        if (v.hasSmart) {
                            MenuChoice(
                                stringResource(R.string.lib_show),
                                DownloadFilter.entries.map { filterChoice(it) },
                                filter,
                                onSelect = {
                                    filter = it
                                    prefs.setDownloadFilter(it)
                                },
                            )
                        }
                    }
                }
                if (v.rows.isEmpty() && query.isNotBlank()) {
                    item(key = "nomatch", contentType = "nomatch") {
                        Text(
                            stringResource(R.string.lib_no_matches, query.trim()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(look.screenPadding),
                        )
                    }
                }
                for (row in v.rows) {
                    when (row) {
                        is DownloadRow.Header -> stickyHeader(key = row.key, contentType = "header") {
                            GroupHeader(
                                labelText(row.label),
                                count = row.count,
                                expanded = if (row.collapsible) row.expanded else null,
                                onToggle = if (row.collapsible) ({ collectionsOpen = !collectionsOpen }) else null,
                            )
                        }
                        is DownloadRow.Collection -> item(key = row.key, contentType = "collection") {
                            CollectionRow(row.c, songs, vm, onRemove = { confirm = Confirm.Collection(row.c) })
                        }
                        is DownloadRow.Group -> item(key = row.key, contentType = "group") {
                            val allSelected by remember(row.songIds) { derivedStateOf { row.songIds.all { it in selection } } }
                            GroupRow(
                                row,
                                isAlbum = group == DownloadGroup.Albums,
                                selecting = selecting,
                                selected = allSelected,
                                onClick = {
                                    if (selecting) toggleAll(row.songIds)
                                    else expanded = if (row.expanded) expanded - row.id else expanded + row.id
                                },
                                onLongClick = { startSelect(row.songIds) },
                            )
                        }
                        is DownloadRow.Song -> item(key = row.key, contentType = "song") {
                            val e = row.e
                            val selected by remember(e.videoId) { derivedStateOf { e.videoId in selection } }
                            SongRow(
                                e,
                                vm,
                                inGroup = row.inGroup,
                                selecting = selecting,
                                selected = selected,
                                onClick = {
                                    when {
                                        selecting -> toggle(e.videoId)
                                        e.state == DownloadState.Done -> vm.actions.playDownloadedList(v.playOrder, e.videoId)
                                        e.state == DownloadState.Failed -> d.retry(e.videoId)
                                    }
                                },
                                onLongClick = { startSelect(listOf(e.videoId)) },
                                onSelect = { toggle(e.videoId) },
                                onRemove = { confirm = if (removableFromList(e)) Confirm.Song(e) else Confirm.Kept },
                            )
                        }
                    }
                }
            }
            if (showIndex) {
                IndexScroller(
                    letters = v.index.letters,
                    current = { v.index.letterAt(listState.firstVisibleItemIndex - lead) },
                    onJump = { i -> scope.launch { listState.scrollToItem(lead + v.index.positions[i]) } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding, top = 8.dp),
                )
            }
        }
    }

    when (val c = confirm) {
        is Confirm.Song -> RemoveDialog(
            title = stringResource(R.string.action_remove_download),
            text = c.e.title + "\n" + stringResource(R.string.download_remove_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { d.removeSong(c.e.videoId) },
            onDismiss = { confirm = null },
        )
        is Confirm.Collection -> RemoveDialog(
            title = stringResource(R.string.action_remove_download),
            text = c.c.title + "\n" + stringResource(R.string.download_remove_collection_text),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { d.removeCollection(c.c.id) },
            onDismiss = { confirm = null },
        )
        is Confirm.Bulk -> RemoveDialog(
            title = pluralStringResource(R.plurals.lib_remove_title, c.remove.size, c.remove.size),
            text = stringResource(R.string.download_remove_text) +
                (if (c.kept > 0) "\n" + pluralStringResource(R.plurals.lib_kept, c.kept, c.kept) else ""),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                c.remove.forEach(d::removeSong)
                selection.clear()
            },
            onDismiss = { confirm = null },
        )
        Confirm.Kept -> AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.download_kept_by_collection)) },
            confirmButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.action_got_it)) } },
        )
        null -> Unit
    }
}

@Composable
private fun CollectionRow(c: DownloadCollection, songs: Map<String, DownloadEntry>, vm: DownloadsViewModel, onRemove: () -> Unit) {
    val look = LocalLook.current
    val tracks = c.videoIds.mapNotNull { songs[it] }
    val status = DownloadStatus.ofCollection(tracks)
    val done = tracks.count { it.state == DownloadState.Done }
    MRow(
        headline = c.title,
        supporting = if (status == DownloadStatus.Done) pluralStringResource(R.plurals.downloads_song_count, done, done) else statusText(status),
        leading = { ArtworkFrame(c.thumbnail, ArtKind.Small, Modifier.size(look.rowArt)) },
        trailing = { OverflowMenu(listOf(MenuAction(stringResource(R.string.action_remove_download), onRemove))) },
        modifier = Modifier.combinedClickable(enabled = done > 0, onClick = { vm.actions.playDownloaded(c.id, null) }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    row: DownloadRow.Group,
    isAlbum: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val look = LocalLook.current
    val count = pluralStringResource(R.plurals.downloads_song_count, row.songIds.size, row.songIds.size)
    MRow(
        headline = row.title,
        supporting = listOfNotNull(row.subtitle, count, sizeText(row.bytes)).joinToString(" · "),
        leading = {
            if (selecting) SelectionMark(selected, look.rowArt)
            else ArtworkFrame(row.artwork, if (isAlbum) ArtKind.Album else ArtKind.Artist, Modifier.size(look.rowArt))
        },
        trailing = {
            if (!selecting) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (look.textControls) Text(if (row.expanded) "[-]" else "[+]", style = MaterialTheme.typography.labelLarge)
                    else Icon(
                        if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null,
                    )
                }
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClickLabel = if (selecting) null else stringResource(if (row.expanded) R.string.lib_collapse else R.string.lib_expand),
                onLongClickLabel = stringResource(R.string.lib_select),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { if (selecting) this.selected = selected },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    e: DownloadEntry,
    vm: DownloadsViewModel,
    inGroup: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val d = vm.downloads
    val look = LocalLook.current
    // This row's progress only; the list above does not read it.
    val status by remember(e.videoId) { d.index.status(e.videoId) }.collectAsStateWithLifecycle(DownloadStatus.of(e, null))
    val smart = stringResource(R.string.lib_smart_marker)
    MRow(
        headline = e.title,
        supporting = listOfNotNull(
            e.artist.ifBlank { null },
            smart.takeIf { e.smartOnly },
            statusText(status).takeIf { status != DownloadStatus.Done },
        ).joinToString(" · "),
        leading = {
            if (selecting) SelectionMark(selected, look.rowArt)
            else ArtworkFrame(e.artworkUrl, ArtKind.Small, Modifier.size(look.rowArt))
        },
        trailing = {
            if (!selecting) {
                OverflowMenu(
                    listOfNotNull(
                        MenuAction(stringResource(R.string.lib_select), onSelect),
                        MenuAction(stringResource(R.string.action_try_again), { d.retry(e.videoId) }).takeIf { e.state == DownloadState.Failed },
                        MenuAction(stringResource(R.string.action_remove_download), onRemove),
                    ),
                )
            }
        },
        modifier = Modifier
            .then(if (inGroup) Modifier.padding(start = look.screenPadding) else Modifier)
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.lib_select),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { if (selecting) this.selected = selected },
    )
}
