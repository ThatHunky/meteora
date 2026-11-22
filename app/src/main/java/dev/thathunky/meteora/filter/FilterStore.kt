package dev.thathunky.meteora.filter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.ytm.ArtistRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Settings → Filters, kept as one JSON value in the `filters` preferences file. */
@Singleton
class FilterStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(FilterSettings.decode(prefs.getString(KEY, null)))
    val settings: StateFlow<FilterSettings> = _settings.asStateFlow()

    fun update(change: (FilterSettings) -> FilterSettings) {
        _settings.update(change)
        prefs.edit().putString(KEY, _settings.value.encode()).apply()
    }

    fun setArtist(artist: ArtistRef, verdict: Verdict?) = update { it.withArtist(artist, verdict) }

    private companion object {
        const val KEY = "settings"
    }
}

/** [artist]'s rule replaced by [verdict], or removed when it is null. One rule per artist. */
fun FilterSettings.withArtist(artist: ArtistRef, verdict: Verdict?): FilterSettings {
    val name = artist.name.trim()
    val others = artists.filterNot { r ->
        if (r.id != null && artist.browseId != null) r.id == artist.browseId else r.name.equals(name, ignoreCase = true)
    }
    return copy(artists = if (verdict == null) others else others + ArtistRule(artist.browseId, name, verdict))
}
