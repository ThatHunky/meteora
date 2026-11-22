package dev.thathunky.meteora.ui.library

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.thathunky.meteora.R
import dev.thathunky.meteora.library.DownloadFilter
import dev.thathunky.meteora.library.DownloadGroup
import dev.thathunky.meteora.library.Label
import dev.thathunky.meteora.library.LibrarySort
import dev.thathunky.meteora.library.date
import dev.thathunky.meteora.ui.components.Choice
import java.time.format.DateTimeFormatter

/** A section header's text. Months are named in the app's language ("листопад 2026"). */
@Composable
fun labelText(l: Label): String = when (l) {
    is Label.Letter -> l.letter
    Label.Today -> stringResource(R.string.lib_today)
    Label.Yesterday -> stringResource(R.string.lib_yesterday)
    Label.ThisWeek -> stringResource(R.string.lib_this_week)
    Label.ThisMonth -> stringResource(R.string.lib_this_month)
    is Label.Month -> {
        val locale = LocalConfiguration.current.locales[0]
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(l.date()).replaceFirstChar { it.titlecase(locale) }
    }
    Label.Earlier -> stringResource(R.string.lib_earlier)
    Label.NoAlbum -> stringResource(R.string.lib_no_album)
    Label.Various -> stringResource(R.string.lib_various)
    Label.Collections -> stringResource(R.string.downloads_collections)
}

/** [likedDefault]: the Liked list calls YouTube's order "Recently liked". [names]: artists are sorted by name, not title. */
@Composable
fun sortChoice(s: LibrarySort, likedDefault: Boolean = false, names: Boolean = false): Choice<LibrarySort> = Choice(
    s,
    stringResource(
        when (s) {
            LibrarySort.Default -> if (likedDefault) R.string.lib_sort_liked else R.string.lib_sort_youtube
            LibrarySort.Title -> if (names) R.string.lib_sort_name else R.string.lib_sort_title
            LibrarySort.Artist -> R.string.lib_sort_artist
            LibrarySort.Album -> R.string.lib_sort_album
            LibrarySort.Count -> R.string.lib_sort_count
            LibrarySort.Size -> R.string.lib_sort_size
        },
    ),
)

@Composable
fun groupChoice(g: DownloadGroup): Choice<DownloadGroup> = Choice(
    g,
    stringResource(
        when (g) {
            DownloadGroup.Songs -> R.string.lib_group_songs
            DownloadGroup.Artists -> R.string.lib_group_artists
            DownloadGroup.Albums -> R.string.lib_group_albums
            DownloadGroup.Recent -> R.string.lib_group_recent
        },
    ),
)

@Composable
fun filterChoice(f: DownloadFilter): Choice<DownloadFilter> = Choice(
    f,
    stringResource(
        when (f) {
            DownloadFilter.All -> R.string.lib_filter_all
            DownloadFilter.Mine -> R.string.lib_filter_mine
            DownloadFilter.Smart -> R.string.lib_filter_smart
        },
    ),
)

/** "84 MB", in the system's own units and language. */
@Composable
fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
