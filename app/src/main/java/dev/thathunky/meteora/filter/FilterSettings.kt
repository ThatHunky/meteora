package dev.thathunky.meteora.filter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a rule does. A rule that is not set is "Show": it has no opinion and the next level decides. */
@Serializable
enum class Verdict { Hide, AlwaysShow }

/** An artist by YouTube browse id when known, else by name (case ignored). */
@Serializable
data class ArtistRule(val id: String? = null, val name: String, val verdict: Verdict)

/** The user's own word or characters; a title or artist name containing it matches (case ignored). */
@Serializable
data class CustomRule(val text: String, val verdict: Verdict)

/** Where the filter applies, one switch each. Search, the library and pages opened on purpose are never filtered. */
@Serializable
enum class Place { Home, Radio, ArtistShelves, SmartDownloads }

/**
 * Everything in Settings → Filters. The defaults hide nothing: no rule of any kind is set, so
 * every level has no opinion and everything shows.
 */
@Serializable
data class FilterSettings(
    val artists: List<ArtistRule> = emptyList(),
    val custom: List<CustomRule> = emptyList(),
    /** ISO 3166-1 alpha-2 code → verdict. */
    val countries: Map<String, Verdict> = emptyMap(),
    /** [LanguageRule.id] → verdict. */
    val languages: Map<String, Verdict> = emptyMap(),
    /** Only [Verdict.Hide] is offered for scripts. */
    val scripts: Map<Script, Verdict> = emptyMap(),
    /** The places switched off; kept this way round so a place added later starts on. */
    val placesOff: Set<Place> = emptySet(),
    /** Look up artists' countries on MusicBrainz. Only used while a country rule exists. */
    val musicBrainz: Boolean = true,
) {
    /** True when no rule could hide or force anything: filtering can be skipped. */
    val hasRules: Boolean
        get() = artists.isNotEmpty() || custom.isNotEmpty() || countries.isNotEmpty() || languages.isNotEmpty() || scripts.isNotEmpty()

    fun appliesTo(place: Place): Boolean = place !in placesOff

    /** Lookups happen only with the switch on and at least one country rule. */
    val wantsCountries: Boolean get() = musicBrainz && countries.isNotEmpty()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Saved settings, or the defaults when there are none or they cannot be read. */
        fun decode(text: String?): FilterSettings =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: FilterSettings()
    }
}
