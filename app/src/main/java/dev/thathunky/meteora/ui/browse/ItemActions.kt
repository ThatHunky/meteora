package dev.thathunky.meteora.ui.browse

import dev.thathunky.meteora.ytm.MusicItem

sealed interface ItemAction {
    data object Play : ItemAction
    data object Shuffle : ItemAction
    data object PlayNext : ItemAction
    data object AddToQueue : ItemAction
    data object Radio : ItemAction
    data object GoToAlbum : ItemAction
    data object GoToArtist : ItemAction
    data object Share : ItemAction
}

/** What the long-press sheet offers for [item], in the official app's order. */
fun itemActions(item: MusicItem): List<ItemAction> = when (item) {
    is MusicItem.Song -> buildList {
        add(ItemAction.PlayNext)
        add(ItemAction.AddToQueue)
        add(ItemAction.Radio)
        if (item.album?.browseId != null) add(ItemAction.GoToAlbum)
        if (item.artists.any { it.browseId != null }) add(ItemAction.GoToArtist)
        add(ItemAction.Share)
    }
    is MusicItem.Album, is MusicItem.Playlist -> listOf(
        ItemAction.Play, ItemAction.Shuffle, ItemAction.PlayNext, ItemAction.AddToQueue, ItemAction.Radio, ItemAction.Share,
    )
    is MusicItem.Artist -> listOf(ItemAction.Shuffle, ItemAction.Radio, ItemAction.Share)
}
