package dev.thathunky.meteora.ui.filter

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.filter.FilterStore
import dev.thathunky.meteora.filter.Verdict
import dev.thathunky.meteora.filter.subtitleParts
import dev.thathunky.meteora.ui.browse.ItemAction
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import javax.inject.Inject

@HiltViewModel
class HideArtistViewModel @Inject constructor(private val store: FilterStore) : ViewModel() {
    fun hide(artist: ArtistRef) = store.setArtist(artist, Verdict.Hide)
}

/** The artists Hide artist can offer for [item]. A playlist has none of its own. */
fun hideableArtists(item: MusicItem): List<ArtistRef> = when (item) {
    is MusicItem.Song -> item.artists.filter { it.name.isNotBlank() }
    is MusicItem.Album -> item.artists.filter { it.name.isNotBlank() }
    is MusicItem.Artist -> listOf(ArtistRef(item.title, item.browseId))
    is MusicItem.Playlist -> emptyList()
}

/** The long-press sheet's filter entries, shown after its own. */
fun filterActions(item: MusicItem): List<ItemAction> =
    if (hideableArtists(item).isNotEmpty()) listOf(ItemAction.HideArtist) else emptyList()

/**
 * Now Playing's artist line as refs: the whole line first, with the channel id (it may be one
 * name with "&" in it), then its pieces by name when it has several.
 */
internal fun refsFrom(artist: String, channelId: String?): List<ArtistRef> =
    subtitleParts(artist).mapIndexed { i, name -> ArtistRef(name, if (i == 0) channelId else null) }

/**
 * Hide artist: one artist is hidden at once, with a toast that says where to undo it; with
 * several, a dialog asks which. [onFinished] runs once it is done or cancelled. Compose this
 * where it outlives the menu that calls it.
 */
@Composable
fun rememberHideArtist(onFinished: () -> Unit = {}, vm: HideArtistViewModel = hiltViewModel()): (List<ArtistRef>) -> Unit {
    var choosing by remember { mutableStateOf<List<ArtistRef>?>(null) }
    val context = LocalContext.current
    val finished by rememberUpdatedState(onFinished)
    val hideOne: (ArtistRef) -> Unit = remember(vm, context) {
        { a ->
            vm.hide(a)
            Toast.makeText(context, context.getString(R.string.filters_hidden_toast, a.name), Toast.LENGTH_LONG).show()
        }
    }
    choosing?.let { list ->
        val close = { choosing = null; finished() }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.filters_hide_which)) },
            text = {
                Column {
                    list.forEach { a ->
                        Text(
                            a.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { hideOne(a); close() }.heightIn(min = 48.dp).padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { MButton(stringResource(R.string.filters_cancel), close, kind = ButtonKind.Text) },
        )
    }
    return remember(hideOne) {
        { refs ->
            when (refs.size) {
                0 -> finished()
                1 -> { hideOne(refs[0]); finished() }
                else -> choosing = refs
            }
        }
    }
}

/** Hide artist in Now Playing's menu. The menu stays open behind the dialog until a choice is made. */
@Composable
fun HideArtistMenuItem(artist: String, channelId: String?, onDone: () -> Unit) {
    if (artist.isBlank()) return
    val hide = rememberHideArtist(onFinished = onDone)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.filters_hide_artist)) },
        leadingIcon = { Icon(Icons.Outlined.Block, null) },
        onClick = { hide(refsFrom(artist, channelId)) },
    )
}
