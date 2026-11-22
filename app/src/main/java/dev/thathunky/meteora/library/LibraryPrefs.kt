package dev.thathunky.meteora.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The group, sort and filter each library list was left on. The Downloads sort is kept per
 * group, since each group offers different sorts. Parsing is in [LibraryChoices], tested on the JVM.
 */
@Singleton
class LibraryPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun downloadGroup(): DownloadGroup = LibraryChoices.parseGroup(prefs.getString(KEY_GROUP, null))
    fun setDownloadGroup(g: DownloadGroup) = prefs.edit().putString(KEY_GROUP, g.name).apply()

    fun downloadSort(g: DownloadGroup): LibrarySort = LibraryChoices.parseSort(prefs.getString("$KEY_SORT.${g.name}", null), LibraryChoices.sortsFor(g))
    fun setDownloadSort(g: DownloadGroup, s: LibrarySort) = prefs.edit().putString("$KEY_SORT.${g.name}", s.name).apply()

    fun downloadFilter(): DownloadFilter = LibraryChoices.parseFilter(prefs.getString(KEY_FILTER, null))
    fun setDownloadFilter(f: DownloadFilter) = prefs.edit().putString(KEY_FILTER, f.name).apply()

    fun sort(screen: LibraryScreen): LibrarySort = LibraryChoices.parseSort(prefs.getString("sort.${screen.name}", null), LibraryChoices.sortsFor(screen))
    fun setSort(screen: LibraryScreen, s: LibrarySort) = prefs.edit().putString("sort.${screen.name}", s.name).apply()

    private companion object {
        const val KEY_GROUP = "downloads.group"
        const val KEY_SORT = "downloads.sort"
        const val KEY_FILTER = "downloads.filter"
    }
}
