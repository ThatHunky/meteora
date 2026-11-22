package dev.thathunky.meteora.ui.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.filter.ContentFilter
import dev.thathunky.meteora.filter.Dismissals
import dev.thathunky.meteora.filter.withoutDismissed
import dev.thathunky.meteora.filter.Place
import dev.thathunky.meteora.ytm.ArtistPage
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import javax.inject.Inject

/** The content filter for the one-line hooks in browse screens. */
@HiltViewModel
class FilterHookViewModel @Inject constructor(val filter: ContentFilter, val dismissals: Dismissals) : ViewModel()

/**
 * Home's shelves as the filter leaves them. Filtered again when a rule changes (Hide artist from
 * a long-press shows at once), not when an artist's country arrives: that applies from the next
 * load, so cards do not vanish under a finger. Items removed with Not interested are left out
 * whatever the rules and places say, until the next load.
 */
@Composable
fun rememberFilteredShelves(shelves: List<Shelf>, place: Place = Place.Home, vm: FilterHookViewModel = hiltViewModel()): List<Shelf> {
    val settings by vm.filter.settings.collectAsStateWithLifecycle()
    val dismissed by vm.dismissals.tokens.collectAsStateWithLifecycle()
    return remember(shelves, settings, dismissed) { withoutDismissed(vm.filter.shelves(shelves, place, settings), dismissed) }
}

/**
 * An artist page with only its "more like this" shelves filtered: the ones made of other
 * artists ("Fans might also like"). The artist's own songs, albums and videos were opened on
 * purpose. Picked by what the shelf holds, not its title, which is translated under hl=uk.
 */
@Composable
fun rememberFilteredArtistPage(page: ArtistPage, vm: FilterHookViewModel = hiltViewModel()): ArtistPage {
    val settings by vm.filter.settings.collectAsStateWithLifecycle()
    val dismissed by vm.dismissals.tokens.collectAsStateWithLifecycle()
    return remember(page, settings, dismissed) {
        page.copy(shelves = withoutDismissed(vm.filter.shelves(page.shelves, Place.ArtistShelves, settings, ::isRelatedShelf), dismissed))
    }
}

internal fun isRelatedShelf(shelf: Shelf): Boolean = shelf.items.isNotEmpty() && shelf.items.all { it is MusicItem.Artist }
