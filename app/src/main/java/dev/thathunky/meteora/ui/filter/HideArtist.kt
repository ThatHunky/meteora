package dev.thathunky.meteora.ui.filter

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.NotInterested
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.filter.Dismissals
import dev.thathunky.meteora.filter.FilterStore
import dev.thathunky.meteora.filter.HideOutcome
import dev.thathunky.meteora.filter.YouTubeFeedback
import dev.thathunky.meteora.filter.Verdict
import dev.thathunky.meteora.filter.subtitleParts
import dev.thathunky.meteora.ui.browse.ItemAction
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Hide artist and Not interested. The calls to YouTube run here, so they finish after the sheet
 * or dialog that started them is gone; the local part is done before the first suspension.
 */
@HiltViewModel
class HideArtistViewModel @Inject constructor(
    private val store: FilterStore,
    private val ytm: YtmClient,
    private val dismissals: Dismissals,
) : ViewModel() {
    fun hide(artist: ArtistRef, token: String?, onOutcome: (HideOutcome) -> Unit) {
        viewModelScope.launch {
            onOutcome(YouTubeFeedback.hideArtist(artist, token, store.settings.value.tellYouTube, { store.setArtist(it, Verdict.Hide) }, ytm::feedback))
        }
    }

    fun notInterested(token: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(YouTubeFeedback.notInterested(token, dismissals::dismiss, ytm::feedback)) }
    }
}

/** Hide artist as the shell calls it: the artists to offer, and the item's "Don't recommend artist" token if any. */
class HideArtist internal constructor(private val run: (List<ArtistRef>, String?) -> Unit) {
    operator fun invoke(refs: List<ArtistRef>, token: String? = null) = run(refs, token)
}

/** The long-press sheet's Not interested icon (MusicRows' action list names it in one line). */
val NotInterestedIcon: ImageVector get() = Icons.Outlined.NotInterested

/** Not interested for an item with that token: off the screen at once, then YouTube is told; a toast says which happened. */
@Composable
fun rememberNotInterested(vm: HideArtistViewModel = hiltViewModel()): (MusicItem) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(vm, context) {
        { item ->
            item.feedback?.notInterested?.let { token ->
                vm.notInterested(token) { told ->
                    val text = if (told) R.string.filters_not_interested_toast else R.string.filters_not_interested_failed_toast
                    Toast.makeText(context, context.getString(text), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** The artists Hide artist can offer for [item]. A playlist has none of its own. */
fun hideableArtists(item: MusicItem): List<ArtistRef> = when (item) {
    is MusicItem.Song -> item.artists.filter { it.name.isNotBlank() }
    is MusicItem.Album -> item.artists.filter { it.name.isNotBlank() }
    is MusicItem.Artist -> listOf(ArtistRef(item.title, item.browseId))
    is MusicItem.Playlist -> emptyList()
}

/** The long-press sheet's filter entries, shown after its own. Not interested only when YouTube offered it for the item. */
fun filterActions(item: MusicItem): List<ItemAction> = buildList {
    if (hideableArtists(item).isNotEmpty()) add(ItemAction.HideArtist)
    if (item.feedback?.notInterested != null) add(ItemAction.NotInterested)
}

/**
 * Now Playing's artist line as refs: the whole line first, with the channel id (it may be one
 * name with "&" in it), then its pieces by name when it has several.
 */
internal fun refsFrom(artist: String, channelId: String?): List<ArtistRef> =
    subtitleParts(artist).mapIndexed { i, name -> ArtistRef(name, if (i == 0) channelId else null) }

/**
 * Hide artist: one artist is hidden at once; with several, a dialog asks which. The item's
 * "Don't recommend artist" token goes to YouTube too when Settings → Filters allows it and there
 * was only one artist to offer (see [YouTubeFeedback.tokenFor]). The toast says which happened.
 * [onFinished] runs once it is done or cancelled. Compose this where it outlives the menu that
 * calls it. The token is only passed through, never kept in composable state.
 */
@Composable
fun rememberHideArtist(onFinished: () -> Unit = {}, vm: HideArtistViewModel = hiltViewModel()): HideArtist {
    var choosing by remember { mutableStateOf<List<ArtistRef>?>(null) }
    val context = LocalContext.current.applicationContext
    val finished by rememberUpdatedState(onFinished)
    val hideOne: (ArtistRef, String?) -> Unit = remember(vm, context) {
        { a, token ->
            vm.hide(a, token) { outcome ->
                val text = when (outcome) {
                    HideOutcome.Local -> R.string.filters_hidden_toast
                    HideOutcome.Told -> R.string.filters_hidden_told_toast
                    HideOutcome.NotTold -> R.string.filters_hidden_not_told_toast
                }
                Toast.makeText(context, context.getString(text, a.name), Toast.LENGTH_LONG).show()
            }
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
                            modifier = Modifier.fillMaxWidth().clickable { hideOne(a, null); close() }.heightIn(min = 48.dp).padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { MButton(stringResource(R.string.filters_cancel), close, kind = ButtonKind.Text) },
        )
    }
    return remember(hideOne) {
        HideArtist { refs, token ->
            when (refs.size) {
                0 -> finished()
                1 -> { hideOne(refs[0], YouTubeFeedback.tokenFor(refs, token)); finished() }
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
