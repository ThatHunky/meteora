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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showSuggestions by remember { mutableStateOf(true) }

    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect {
            vm.query.value = it
            showSuggestions = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            state = field,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = {
                showSuggestions = false
                focus.clearFocus()
                vm.submit(field.text.toString())
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(YtmClient.SearchFilter.entries.toList()) { f ->
                FilterChip(
                    selected = f == filter,
                    onClick = { vm.setFilter(f) },
                    label = { Text(stringResource(f.label())) },
                )
            }
        }

        if (showSuggestions && suggestions.isNotEmpty()) {
            LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(suggestions) { s ->
                    ListItem(
                        headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Outlined.Search, null) },
                        trailingContent = { Icon(Icons.Outlined.NorthWest, null) },
                        modifier = Modifier.clickable {
                            field.setTextAndPlaceCursorAtEnd(s)
                            showSuggestions = false
                            focus.clearFocus()
                            vm.submit(s)
                        },
                    )
                }
            }
            return@Column
        }

        when (val r = results) {
            null -> Unit
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            is Load.Failed -> Text(
                stringResource(R.string.load_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                r.value.top?.let { top ->
                    item(key = "top") {
                        Text(
                            stringResource(R.string.top_result),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        )
                        MusicRow(top, onClick = { opener.open(top) }, onLongClick = { onLongPress(top) })
                    }
                }
                val topKey = r.value.top?.key()
                items(r.value.items.distinctBy { it.key() }.filter { it.key() != topKey }, key = { it.key() }) { item ->
                    MusicRow(item, onClick = { opener.open(item) }, onLongClick = { onLongPress(item) }, modifier = Modifier.animateItem())
                }
            }
        }
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
