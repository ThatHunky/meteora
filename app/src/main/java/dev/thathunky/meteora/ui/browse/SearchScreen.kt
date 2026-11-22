package dev.thathunky.meteora.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.MChip
import dev.thathunky.meteora.ui.components.SectionHeader
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient

@Composable
fun SearchScreen(
    opener: Opener,
    onLongPress: (MusicItem) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    vm: SearchViewModel = hiltViewModel(),
) {
    val field = rememberTextFieldState(vm.query.value)
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val look = LocalLook.current
    // The field must not keep focus once the person has moved on: a focused field brings the
    // keyboard back whenever the screen regains focus (back from a page, the player closed).
    val dismissKeyboard: () -> Unit = {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    val open: (MusicItem) -> Unit = { item ->
        dismissKeyboard()
        opener.open(item)
    }

    // The view model decides when there are suggestions to show (none for the submitted query).
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { vm.query.value = it }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            state = field,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = if (look.pills) RoundedCornerShape(28.dp) else RoundedCornerShape(look.cornerSmall),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = {
                dismissKeyboard()
                vm.submit(field.text.toString())
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = look.screenPadding, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = look.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(YtmClient.SearchFilter.entries.toList()) { f ->
                // The look's chip: Material's tonal, checked chip read as Expressive in Terminal and Retro.
                MChip(
                    stringResource(f.label()),
                    selected = f == filter,
                    onClick = {
                        dismissKeyboard()
                        vm.setFilter(f)
                    },
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            val suggestionsState = rememberLazyListState()
            HideKeyboardOnScroll(suggestionsState, dismissKeyboard)
            LazyColumn(state = suggestionsState, contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(suggestions) { s ->
                    ListItem(
                        headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Outlined.Search, null) },
                        trailingContent = { Icon(Icons.Outlined.NorthWest, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            // Submit first, so the text change below is already the submitted query.
                            vm.submit(s)
                            field.setTextAndPlaceCursorAtEnd(s)
                            dismissKeyboard()
                        },
                    )
                }
            }
            return@Column
        }

        when (val r = results) {
            null -> MessageState(stringResource(R.string.search_start), bottomPadding = bottomPadding)
            else -> Loaded(r, onRetry = vm::retry, bottomPadding = bottomPadding) { found ->
                val topKey = found.top?.key()
                val rest = found.items.distinctBy { it.key() }.filter { it.key() != topKey }
                if (found.top == null && rest.isEmpty()) {
                    MessageState(stringResource(R.string.search_no_results, vm.submittedQuery.orEmpty()), bottomPadding = bottomPadding)
                    return@Loaded
                }
                val resultsState = rememberLazyListState()
                HideKeyboardOnScroll(resultsState, dismissKeyboard)
                LazyColumn(state = resultsState, contentPadding = PaddingValues(bottom = bottomPadding)) {
                    found.top?.let { top ->
                        item(key = "top") {
                            SectionHeader(stringResource(R.string.top_result))
                            MusicRow(top, onClick = { open(top) }, onLongClick = { onLongPress(top) }, showType = true)
                        }
                    }
                    items(rest, key = { it.key() }) { item ->
                        MusicRow(item, onClick = { open(item) }, onLongClick = { onLongPress(item) }, modifier = Modifier.animateItem(), showType = true)
                    }
                }
            }
        }
    }
}

/** Scrolling a list means reading it, not typing: put the keyboard away. */
@Composable
private fun HideKeyboardOnScroll(state: LazyListState, hide: () -> Unit) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (it) hide() }
    }
}

private fun YtmClient.SearchFilter.label() = when (this) {
    YtmClient.SearchFilter.All -> R.string.filter_all
    YtmClient.SearchFilter.Songs -> R.string.filter_songs
    YtmClient.SearchFilter.Albums -> R.string.filter_albums
    YtmClient.SearchFilter.Artists -> R.string.filter_artists
    YtmClient.SearchFilter.Playlists -> R.string.filter_playlists
}

fun MusicItem.key(): String = when (this) {
    is MusicItem.Song -> "s:$videoId"
    is MusicItem.Album -> "a:$browseId"
    is MusicItem.Artist -> "r:$browseId"
    is MusicItem.Playlist -> "p:$browseId"
}
