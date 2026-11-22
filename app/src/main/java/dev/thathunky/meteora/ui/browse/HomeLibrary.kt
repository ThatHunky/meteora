package dev.thathunky.meteora.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.PageTitle
import dev.thathunky.meteora.ui.components.SectionHeader
import dev.thathunky.meteora.ui.components.rowSlotHeight
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.SegmentedChoice
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import dev.thathunky.meteora.ytm.YtmAuthStore
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(private val ytm: YtmClient, auth: YtmAuthStore) : ViewModel() {
    private val _home = MutableStateFlow<Load<List<Shelf>>>(Load.Loading)
    val home: StateFlow<Load<List<Shelf>>> = _home.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    val signedIn: StateFlow<Boolean> = auth.signedIn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Reload when the account changes (sign in / out).
        viewModelScope.launch { auth.signedIn.distinctUntilChanged().collect { load() } }
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        // A failed pull-to-refresh keeps the shelves that are showing instead of replacing them with an error.
        val old = _home.value
        load()
        if (_home.value is Load.Failed && old is Load.Ready) _home.value = old
        _refreshing.value = false
    }

    /** From the failed state: show the loader again rather than a pull-to-refresh spinner. */
    fun retry() = viewModelScope.launch {
        _home.value = Load.Loading
        load()
    }

    private suspend fun load() {
        _home.value = runCatching { ytm.home() }.fold({ Load.Ready(it) }, { Load.Failed(it) })
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(private val ytm: YtmClient, auth: YtmAuthStore) : ViewModel() {
    val signedIn: StateFlow<Boolean> = auth.signedIn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _liked = MutableStateFlow<Load<CollectionPage>>(Load.Loading)
    val liked: StateFlow<Load<CollectionPage>> = _liked.asStateFlow()
    private val _playlists = MutableStateFlow<Load<List<MusicItem>>>(Load.Loading)
    val playlists: StateFlow<Load<List<MusicItem>>> = _playlists.asStateFlow()

    init {
        viewModelScope.launch {
            auth.signedIn.distinctUntilChanged().collect { yes -> if (yes) reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _liked.value = Load.Loading
            _liked.value = runCatching { ytm.likedSongs() }.fold({ Load.Ready(it) }, { Load.Failed(it) })
        }
        viewModelScope.launch {
            _playlists.value = Load.Loading
            _playlists.value = runCatching { ytm.libraryPlaylists() }.fold({ Load.Ready(it) }, { Load.Failed(it) })
        }
    }
}

@Composable
fun HomeScreen(
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    onSignIn: () -> Unit,
    bottomPadding: Dp,
    header: @Composable () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val home by vm.home.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val look = LocalLook.current
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        if (home is Load.Failed && dev.thathunky.meteora.ui.downloads.OfflineHomeNote()) return@PullToRefreshBox
        Loaded(home, onRetry = { vm.retry() }, bottomPadding = bottomPadding) { shelves ->
            @Suppress("NAME_SHADOWING") val shelves = dev.thathunky.meteora.ui.filter.rememberFilteredShelves(shelves)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
                item(key = "header") { header() }
                item(key = "update") { dev.thathunky.meteora.ui.update.UpdateCard() }
                item(key = "title") { PageTitle(stringResource(R.string.nav_home)) }
                if (!signedIn) item(key = "signin") { SignInPrompt(onSignIn) }
                if (shelves.isEmpty()) {
                    item(key = "empty") {
                        // Inside the list, so pull-to-refresh still works from here.
                        MessageState(
                            stringResource(R.string.empty_home),
                            Modifier.height(320.dp),
                            action = stringResource(R.string.action_try_again) to { vm.retry() },
                        )
                    }
                }
                shelves.forEachIndexed { i, shelf ->
                    item(key = "h$i:${shelf.title}") { SectionHeader(shelf.title) }
                    item(key = "s$i:${shelf.title}") {
                        if (shelf.items.all { it is MusicItem.Song } && shelf.items.size > 4) {
                            // "Quick picks": a 4-row grid of songs that scrolls sideways.
                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(4),
                                // Four rows as tall as a row really is: the look's row height
                                // alone cut off the fourth row in Terminal and at large font sizes.
                                modifier = Modifier.height(rowSlotHeight() * 4),
                                contentPadding = PaddingValues(horizontal = look.screenPadding / 2),
                            ) {
                                gridItems(shelf.items.distinctBy { it.key() }, key = { it.key() }) { item ->
                                    MusicRow(item, { opener.open(item) }, { onLongPress(item) }, Modifier.width(320.dp))
                                }
                            }
                        } else {
                            ShelfRow(shelf.items, opener, onLongPress)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    onSignIn: () -> Unit,
    onPlayLiked: (shuffle: Boolean, ids: List<String>) -> Unit,
    bottomPadding: Dp,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomPadding).padding(24.dp), contentAlignment = Alignment.Center) { SignInPrompt(onSignIn) }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val liked by vm.liked.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val look = LocalLook.current
    Column(Modifier.fillMaxSize()) {
        // The same title as Home and Settings, and the look's own toggle row instead of a
        // Material tab row, which drew the same in every look.
        PageTitle(stringResource(R.string.nav_library))
        SegmentedChoice(
            options = listOf(
                Choice(0, stringResource(R.string.library_liked)),
                Choice(1, stringResource(R.string.filter_playlists)),
                Choice(2, stringResource(R.string.library_downloads)),
            ),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = look.screenPadding, vertical = 8.dp),
        )
        when (tab) {
            0 -> Loaded(liked, onRetry = vm::reload, bottomPadding = bottomPadding) { page ->
                if (page.tracks.isEmpty()) {
                    MessageState(stringResource(R.string.empty_liked), bottomPadding = bottomPadding)
                    return@Loaded
                }
                LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(LocalLook.current.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    itemsIndexed(page.tracks, key = { i, t -> "$i:${t.videoId}" }) { _, t ->
                        MusicRow(t, { opener.open(t) }, { onLongPress(t) })
                    }
                }
            }
            2 -> dev.thathunky.meteora.ui.downloads.DownloadsTab(bottomPadding)
            else -> Loaded(playlists, onRetry = vm::reload, bottomPadding = bottomPadding) { items ->
                if (items.isEmpty()) {
                    MessageState(stringResource(R.string.empty_playlists), bottomPadding = bottomPadding)
                    return@Loaded
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(152.dp),
                    contentPadding = PaddingValues(start = LocalLook.current.screenPadding, end = LocalLook.current.screenPadding, top = 16.dp, bottom = bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    gridItems(items.distinctBy { it.key() }, key = { it.key() }) { item ->
                        MusicCard(item, { opener.open(item) }, { onLongPress(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.signin_prompt),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        MButton(stringResource(R.string.signin_action), onSignIn)
    }
}
