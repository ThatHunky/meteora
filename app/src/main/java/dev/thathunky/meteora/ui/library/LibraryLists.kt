package dev.thathunky.meteora.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.library.LetterIndex
import dev.thathunky.meteora.library.LibraryChoices
import dev.thathunky.meteora.library.LibraryCollation
import dev.thathunky.meteora.library.LibraryPrefs
import dev.thathunky.meteora.library.LibraryScreen
import dev.thathunky.meteora.library.LibrarySort
import dev.thathunky.meteora.library.SectionedList
import dev.thathunky.meteora.ui.browse.MusicCard
import dev.thathunky.meteora.ui.browse.MusicRow
import dev.thathunky.meteora.ui.browse.Opener
import dev.thathunky.meteora.ui.browse.key
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.GroupHeader
import dev.thathunky.meteora.ui.components.IndexScroller
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MenuChoice
import dev.thathunky.meteora.ui.components.SortGroupBar
import dev.thathunky.meteora.ui.components.indexScrollerGap
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryListsViewModel @Inject constructor(val prefs: LibraryPrefs) : ViewModel()

/**
 * Library → Liked songs: YouTube's order (most recently liked first) unless sorted by title,
 * artist or album, which add sticky headers and, when long, the letter index. Play and Shuffle
 * play the whole liked list whatever the sort.
 */
@Composable
fun LikedList(
    page: CollectionPage,
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    onPlayLiked: (shuffle: Boolean, ids: List<String>) -> Unit,
    bottomPadding: Dp,
    vm: LibraryListsViewModel = hiltViewModel(),
) {
    val look = LocalLook.current
    val locale = LocalConfiguration.current.locales[0]
    val collation = remember(locale) { LibraryCollation(locale) }
    var sort by remember { mutableStateOf(vm.prefs.sort(LibraryScreen.Liked)) }
    val view = remember(page.tracks, sort, collation) {
        SectionedList.build(
            page.tracks, sort, collation,
            id = { it.videoId },
            title = { it.title },
            artist = { it.artists.firstOrNull()?.name },
            album = { it.album?.name },
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lead = 2
    val showIndex = LetterIndex.worthShowing(view.index, view.rows.size)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding, end = indexScrollerGap(showIndex))) {
            item(key = "play", contentType = "play") {
                Row(Modifier.fillMaxWidth().padding(look.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton(
                        stringResource(R.string.action_play),
                        onClick = { onPlayLiked(false, page.tracks.map { it.videoId }) },
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1.4f).height(56.dp),
                    )
                    MButton(
                        stringResource(R.string.shuffle),
                        onClick = { onPlayLiked(true, page.tracks.map { it.videoId }) },
                        kind = ButtonKind.Tonal,
                        icon = Icons.Outlined.Shuffle,
                        modifier = Modifier.weight(1f).height(56.dp),
                    )
                }
            }
            item(key = "bar", contentType = "bar") {
                SortGroupBar {
                    MenuChoice(
                        stringResource(R.string.lib_sort),
                        LibraryChoices.sortsFor(LibraryScreen.Liked).map { sortChoice(it, likedDefault = true) },
                        sort,
                        onSelect = {
                            sort = it
                            vm.prefs.setSort(LibraryScreen.Liked, it)
                        },
                    )
                }
            }
            for (row in view.rows) {
                when (row) {
                    is SectionedList.Row.Header -> stickyHeader(key = row.key, contentType = "header") { GroupHeader(labelText(row.label)) }
                    is SectionedList.Row.Item -> item(key = row.key, contentType = "song") {
                        val t = row.item
                        MusicRow(t, { opener.open(t) }, { onLongPress(t) })
                    }
                }
            }
        }
        if (showIndex) {
            IndexScroller(
                letters = view.index.letters,
                current = { view.index.letterAt(listState.firstVisibleItemIndex - lead) },
                onJump = { i -> scope.launch { listState.scrollToItem(lead + view.index.positions[i]) } },
                modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding, top = 8.dp),
            )
        }
    }
}

/** Library → Playlists: YouTube's order or by title. No groups: a few dozen cards need none. */
@Composable
fun PlaylistsGrid(
    items: List<MusicItem>,
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    bottomPadding: Dp,
    vm: LibraryListsViewModel = hiltViewModel(),
) {
    val look = LocalLook.current
    val locale = LocalConfiguration.current.locales[0]
    val collation = remember(locale) { LibraryCollation(locale) }
    var sort by remember { mutableStateOf(vm.prefs.sort(LibraryScreen.Playlists)) }
    val shown = remember(items, sort, collation) {
        val unique = items.distinctBy { it.key() }
        if (sort == LibrarySort.Title) unique.sortedWith(compareBy { collation.key(it.title) }) else unique
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(152.dp),
        contentPadding = PaddingValues(start = look.screenPadding, end = look.screenPadding, top = 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "bar", span = { GridItemSpan(maxLineSpan) }, contentType = "bar") {
            // The grid already pads the sides; the bar's own padding would double it.
            SortGroupBar(edgePadding = 0.dp) {
                MenuChoice(
                    stringResource(R.string.lib_sort),
                    LibraryChoices.sortsFor(LibraryScreen.Playlists).map { sortChoice(it) },
                    sort,
                    onSelect = {
                        sort = it
                        vm.prefs.setSort(LibraryScreen.Playlists, it)
                    },
                )
            }
        }
        items(shown, key = { it.key() }, contentType = { "card" }) { item ->
            MusicCard(item, { opener.open(item) }, { onLongPress(item) })
        }
    }
}
