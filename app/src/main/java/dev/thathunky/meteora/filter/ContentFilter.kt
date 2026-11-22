package dev.thathunky.meteora.filter

import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import dev.thathunky.meteora.ytm.WatchQueue
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** The rule levels, strongest first: an artist rule beats a custom rule, and so on down to script. */
enum class Level { Artist, Custom, Country, Language, Script }

/**
 * What the filter decided and which rule decided it. [verdict] null: no rule had an opinion and
 * the item shows. [reason] is the rule's subject: the artist's name, the custom text, the ISO
 * country code, the language id or the [Script] name.
 */
data class Decision(val verdict: Verdict?, val level: Level? = null, val reason: String? = null) {
    val allowed: Boolean get() = verdict != Verdict.Hide

    companion object {
        val Show = Decision(null)
    }
}

/**
 * What the filter reads from an item. Language and script are guessed from [title] and the
 * [artists]' names only, never from subtitles: under `hl=uk` YouTube's own labels there
 * ("Альбом", "Сингл") are Cyrillic and would trip a script rule on every album. [otherNames]
 * (subtitle pieces) are only compared with artist rules by exact name.
 */
data class Subject(val title: String, val artists: List<ArtistRef>, val otherNames: List<String> = emptyList())

fun MusicItem.subject(): Subject = when (this) {
    is MusicItem.Song -> Subject(title, artists)
    is MusicItem.Album -> Subject(title, artists, subtitleParts(subtitle))
    is MusicItem.Artist -> Subject(title, listOf(ArtistRef(title, browseId)))
    is MusicItem.Playlist -> Subject(title, emptyList(), subtitleParts(subtitle))
}

/**
 * Names a subtitle or an artist line may hold: each " • " segment whole ("Simon & Garfunkel",
 * "Tyler, The Creator"), then its pieces split at ", " and " & ".
 */
internal fun subtitleParts(subtitle: String): List<String> {
    val segments = subtitle.split(" • ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
    val pieces = segments.flatMap { s -> s.split(", ", " & ").map { it.trim() } }.filter { it.isNotEmpty() }
    return (segments + pieces).distinct()
}

/** The one pure function that decides. Precedence: [Level] order; inside a level Always show beats Hide. */
object FilterRules {

    /**
     * Type labels the parser can take for an artist when the subtitle links none (it only knows
     * the English ones). Ignored as artist names when they come without a browse id.
     */
    private val UI_LABELS = setOf(
        "song", "video", "album", "single", "ep", "artist", "playlist", "episode", "podcast", "profile",
        "пісня", "відео", "альбом", "сингл", "міні-альбом", "виконавець", "плейлист", "епізод", "подкаст", "профіль",
    )

    fun decide(s: FilterSettings, subject: Subject, countryOf: (ArtistRef) -> String? = { null }): Decision {
        if (!s.hasRules) return Decision.Show
        val artists = subject.artists.filter { a ->
            a.name.isNotBlank() && !(a.browseId == null && a.name.trim().lowercase(Locale.ROOT) in UI_LABELS)
        }
        val pieces = listOf(subject.title) + artists.map { it.name }

        // 1. Artists: by id when both sides have one, else by name.
        pick(Level.Artist, s.artists.mapNotNull { r ->
            val hit = artists.firstOrNull { matches(r, it) }?.name
                ?: subject.otherNames.firstOrNull { it.equals(r.name.trim(), ignoreCase = true) }
            hit?.let { r.verdict to it }
        })?.let { return it }

        // 2. Custom words, in the title or an artist's name.
        val lowered = pieces.map(::fold)
        pick(Level.Custom, s.custom.mapNotNull { r ->
            val t = fold(r.text.trim())
            if (t.isNotEmpty() && lowered.any { t in it }) r.verdict to r.text.trim() else null
        })?.let { return it }

        // 3. Countries, when the artist's is known.
        if (s.countries.isNotEmpty()) {
            pick(Level.Country, artists.mapNotNull(countryOf).distinct().mapNotNull { code ->
                s.countries[code]?.let { it to code }
            })?.let { return it }
        }

        // 4. Languages, guessed per piece (a Ukrainian title by an artist with a Russian name is both).
        if (s.languages.isNotEmpty()) {
            val langs = pieces.flatMap { LanguageGuess.languages(it) }.distinct()
            pick(Level.Language, langs.mapNotNull { id -> s.languages[id]?.let { it to id } })?.let { return it }
        }

        // 5. Scripts, for whatever no language rule decided.
        if (s.scripts.isNotEmpty()) {
            val scripts = pieces.flatMap { LanguageGuess.scripts(it) }.distinct().sorted()
            pick(Level.Script, scripts.mapNotNull { sc -> s.scripts[sc]?.let { it to sc.name } })?.let { return it }
        }
        return Decision.Show
    }

    private fun matches(rule: ArtistRule, artist: ArtistRef): Boolean =
        if (rule.id != null && artist.browseId != null) rule.id == artist.browseId
        else rule.name.trim().equals(artist.name.trim(), ignoreCase = true)

    private fun pick(level: Level, hits: List<Pair<Verdict, String>>): Decision? {
        if (hits.isEmpty()) return null
        val win = hits.firstOrNull { it.first == Verdict.AlwaysShow } ?: hits.first()
        return Decision(win.first, level, win.second)
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

/** Filtering shelves and queues with a given test. Pure, for the JVM tests. */
object FilterApply {
    /** A shelf the filter has thinned to fewer than this many items is dropped. */
    const val MIN_SHELF = 3

    /** Radio playlist ids: a song's (RDAMVM), a collection's (RDAMPL), an artist's (RDEM), automix (RDAT). */
    private val RADIO_PREFIXES = listOf("RDAMVM", "RDAMPL", "RDEM", "RDAT")

    /**
     * [which] picks the shelves to filter; others are left whole. A shelf that loses nothing is
     * kept as it came, even when it was short to begin with.
     */
    fun shelves(shelves: List<Shelf>, allows: (MusicItem) -> Boolean, which: (Shelf) -> Boolean = { true }): List<Shelf> =
        shelves.mapNotNull { shelf ->
            if (!which(shelf)) return@mapNotNull shelf
            val kept = shelf.items.filter(allows)
            when {
                kept.size == shelf.items.size -> shelf
                kept.size < MIN_SHELF -> null
                else -> shelf.copy(items = kept)
            }
        }

    /**
     * [q] without what [allows] refuses, except [keepId] (the song the user tapped). The start
     * stays on the same song, or on the next one kept when that one went.
     */
    fun queue(q: WatchQueue, keepId: String?, allows: (MusicItem.Song) -> Boolean): WatchQueue {
        val keep = q.songs.map { it.videoId == keepId || allows(it) }
        if (keep.all { it }) return q
        val kept = q.songs.filterIndexed { i, _ -> keep[i] }
        val start = keep.take(q.startIndex.coerceAtLeast(0)).count { it }.coerceAtMost((kept.size - 1).coerceAtLeast(0))
        return q.copy(songs = kept, startIndex = start)
    }

    fun isRadioPlaylist(playlistId: String?): Boolean =
        playlistId != null && RADIO_PREFIXES.any { playlistId.removePrefix("VL").startsWith(it) }

    /** A song tap (its radio) and the radio actions; not albums, playlists or the artist's own shuffle. */
    fun isRadio(intent: PlayIntent): Boolean = when (intent) {
        is PlayIntent.SongRadio -> true
        is PlayIntent.Collection -> isRadioPlaylist(intent.playlistId)
        PlayIntent.LikedSongs -> false
    }

    /** The song a play intent starts on because the user chose it: never filtered out. */
    fun tapped(intent: PlayIntent): String? = when (intent) {
        is PlayIntent.SongRadio -> intent.videoId
        is PlayIntent.Collection -> intent.startVideoId
        PlayIntent.LikedSongs -> null
    }
}

/**
 * The content filter as the app uses it: the current [FilterStore] settings and the artist
 * country cache. [allows] is the entry point for anything that builds lists by itself (smart
 * downloads, when they come: `allows(song, Place.SmartDownloads)`).
 */
@Singleton
class ContentFilter @Inject constructor(private val store: FilterStore, private val countries: ArtistCountries) {
    val settings: StateFlow<FilterSettings> get() = store.settings

    /** The artist's country when looked up and known; null otherwise (and then queued for a lookup). */
    private val countryOf: (ArtistRef) -> String? = { countries.countryOf(it) }

    fun decide(subject: Subject): Decision = FilterRules.decide(store.settings.value, subject, countryOf)

    /** For the Test field: like [decide], but countries come from the cache only, so typing asks MusicBrainz nothing. */
    fun test(subject: Subject): Decision = FilterRules.decide(store.settings.value, subject) { countries.cached(it) }

    fun allows(item: MusicItem, place: Place, settings: FilterSettings = store.settings.value): Boolean {
        if (!settings.hasRules || !settings.appliesTo(place)) return true
        return FilterRules.decide(settings, item.subject(), countryOf).allowed
    }

    /** Home and artist page shelves. [which]: the shelves that may be filtered. */
    fun shelves(
        shelves: List<Shelf>,
        place: Place,
        settings: FilterSettings = store.settings.value,
        which: (Shelf) -> Boolean = { true },
    ): List<Shelf> {
        if (!settings.hasRules || !settings.appliesTo(place)) return shelves
        return FilterApply.shelves(shelves, { allows(it, place, settings) }, which)
    }

    /** The first page of a queue on the phone: filtered only when it is a radio, never losing the tapped song. */
    fun startQueue(q: WatchQueue, intent: PlayIntent): WatchQueue =
        if (!FilterApply.isRadio(intent)) q
        else FilterApply.queue(q, FilterApply.tapped(intent)) { allows(it, Place.Radio) }

    /**
     * A further page of a radio, or autoplay's first. A page the filter empties is followed by
     * up to [MAX_EMPTY_PAGES] more, so a radio does not run dry on a run of hidden tracks.
     */
    suspend fun radioPage(page: WatchQueue, next: suspend (String) -> WatchQueue): WatchQueue {
        var q = FilterApply.queue(page, null) { allows(it, Place.Radio) }
        var tries = 0
        while (q.songs.isEmpty() && q.continuation != null && tries < MAX_EMPTY_PAGES) {
            val more = runCatching { next(q.continuation!!) }.getOrNull() ?: break
            q = FilterApply.queue(more, null) { allows(it, Place.Radio) }.copy(likes = q.likes + more.likes, automix = q.automix ?: more.automix)
            tries++
        }
        return q
    }

    private companion object {
        const val MAX_EMPTY_PAGES = 3
    }
}
