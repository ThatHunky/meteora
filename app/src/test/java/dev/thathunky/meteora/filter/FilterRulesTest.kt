package dev.thathunky.meteora.filter

import dev.thathunky.meteora.filter.Verdict.AlwaysShow
import dev.thathunky.meteora.filter.Verdict.Hide
import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRulesTest {
    private fun song(title: String, vararg artists: Pair<String, String?>) =
        MusicItem.Song("v", title, artists.map { ArtistRef(it.first, it.second) }, null, null, null, isVideo = false)

    private fun decide(s: FilterSettings, item: MusicItem, countries: Map<String, String> = emptyMap()) =
        FilterRules.decide(s, item.subject()) { a -> countries[a.browseId ?: a.name] }

    private val stefania = song("Стефанія", "Kalush Orchestra" to "UCkalush")
    private val zvezda = song("Звезда по имени Солнце", "Кино" to "UCkino")
    private val vykhoda = song("Выхода нет", "Сплин" to "UCsplin")
    private val luda = song("Луда по тебе", "Деси Добрева" to "UCdesi")

    @Test fun `the defaults show everything`() {
        for (item in listOf(stefania, zvezda, vykhoda, luda, song("Stefania", "Kalush" to null))) {
            assertEquals(Decision.Show, decide(FilterSettings(), item))
        }
    }

    @Test fun `Russian hidden leaves Bulgarian, Serbian and undecided Cyrillic`() {
        val s = FilterSettings(languages = mapOf("ru" to Hide))
        assertEquals(Decision(Hide, Level.Language, "ru"), decide(s, vykhoda))
        assertTrue(decide(s, luda).allowed)
        assertTrue(decide(s, zvezda).allowed)
        assertTrue(decide(s, song("Ђурђевдан", "Бијело дугме" to null)).allowed)
        assertTrue(decide(s, song("Българийо", "Тоника" to null)).allowed)
    }

    @Test fun `Cyrillic hidden unless it looks Ukrainian`() {
        val s = FilterSettings(scripts = mapOf(Script.Cyrillic to Hide), languages = mapOf("uk" to AlwaysShow))
        assertEquals(Decision(AlwaysShow, Level.Language, "uk"), decide(s, stefania))
        assertEquals(Decision(Hide, Level.Script, "Cyrillic"), decide(s, zvezda))
        assertFalse(decide(s, vykhoda).allowed)
        assertTrue(decide(s, song("Stefania", "Kalush Orchestra" to null)).allowed)
    }

    @Test fun `a Ukrainian title by an artist with a Russian name is both, and Always show wins`() {
        val s = FilterSettings(languages = mapOf("ru" to Hide, "uk" to AlwaysShow))
        val mixed = song("Обійми", "Тысяча" to null)
        assertEquals(Decision(AlwaysShow, Level.Language, "uk"), decide(s, mixed))
    }

    @Test fun `YouTube's Ukrainian labels in a subtitle trip no script rule`() {
        val s = FilterSettings(scripts = mapOf(Script.Cyrillic to Hide))
        val album = MusicItem.Album("MPREb_1", "OK Computer", "Альбом • Radiohead", null, null)
        assertTrue(decide(s, album).allowed)
        val artist = MusicItem.Artist("UCr", "Radiohead", "1,2 млн передплатників", null)
        assertTrue(decide(s, artist).allowed)
        // An unlinked "Пісня" taken for the artist is not read either.
        assertTrue(decide(s, song("Creep", "Пісня" to null)).allowed)
    }

    @Test fun `artist rules by id, else by name`() {
        val s = FilterSettings(artists = listOf(ArtistRule("UCkino", "Кино", Hide), ArtistRule(null, "сплин", Hide)))
        assertEquals(Decision(Hide, Level.Artist, "Кино"), decide(s, zvezda))
        assertEquals(Decision(Hide, Level.Artist, "Сплин"), decide(s, vykhoda))
        // Same name, another channel: not the hidden one.
        assertTrue(decide(s, song("Песня", "Кино" to "UCother")).allowed)
        // An album or playlist by name in its subtitle.
        assertFalse(decide(s, MusicItem.Album("MPREb_2", "Гранатовый альбом", "Альбом • Сплин • 1998", null, null)).allowed)
        // The artist itself.
        assertFalse(decide(s, MusicItem.Artist("UCkino", "Кино", "", null)).allowed)
    }

    @Test fun `a name with an ampersand or comma matches whole and in pieces`() {
        val album = MusicItem.Album("MPREb_3", "Bookends", "Album • Simon & Garfunkel • 1968", null, null)
        assertFalse(decide(FilterSettings(artists = listOf(ArtistRule(null, "Simon & Garfunkel", Hide))), album).allowed)
        assertFalse(decide(FilterSettings(artists = listOf(ArtistRule(null, "Garfunkel", Hide))), album).allowed)
        val tyler = MusicItem.Album("MPREb_4", "IGOR", "Album • Tyler, The Creator", null, null)
        assertFalse(decide(FilterSettings(artists = listOf(ArtistRule(null, "Tyler, The Creator", Hide))), tyler).allowed)
    }

    @Test fun `custom rules match title or artist, case ignored`() {
        val s = FilterSettings(custom = listOf(CustomRule("REMIX", Hide)))
        assertEquals(Decision(Hide, Level.Custom, "REMIX"), decide(s, song("Song (Club Remix)", "DJ" to null)))
        assertTrue(decide(s, song("Song", "DJ" to null)).allowed)
        assertFalse(decide(FilterSettings(custom = listOf(CustomRule("dj ", Hide))), song("Song", "DJ Snake" to null)).allowed)
    }

    @Test fun `countries apply only when the country is known`() {
        val s = FilterSettings(countries = mapOf("RU" to Hide))
        assertEquals(Decision(Hide, Level.Country, "RU"), decide(s, zvezda, mapOf("UCkino" to "RU")))
        assertTrue(decide(s, zvezda).allowed)
        assertTrue(decide(s, stefania, mapOf("UCkalush" to "UA")).allowed)
    }

    // ---- precedence, each pair both ways ----

    @Test fun `artist beats custom`() {
        val a = song("Remix", "Кино" to "UCkino")
        assertTrue(decide(FilterSettings(artists = listOf(ArtistRule("UCkino", "Кино", AlwaysShow)), custom = listOf(CustomRule("remix", Hide))), a).allowed)
        assertFalse(decide(FilterSettings(artists = listOf(ArtistRule("UCkino", "Кино", Hide)), custom = listOf(CustomRule("remix", AlwaysShow))), a).allowed)
    }

    @Test fun `custom beats country`() {
        val c = mapOf("UCkino" to "RU")
        assertTrue(decide(FilterSettings(custom = listOf(CustomRule("солнце", AlwaysShow)), countries = mapOf("RU" to Hide)), zvezda, c).allowed)
        assertFalse(decide(FilterSettings(custom = listOf(CustomRule("солнце", Hide)), countries = mapOf("RU" to AlwaysShow)), zvezda, c).allowed)
    }

    @Test fun `country beats language`() {
        val c = mapOf("UCsplin" to "RU")
        assertTrue(decide(FilterSettings(countries = mapOf("RU" to AlwaysShow), languages = mapOf("ru" to Hide)), vykhoda, c).allowed)
        assertFalse(decide(FilterSettings(countries = mapOf("RU" to Hide), languages = mapOf("ru" to AlwaysShow)), vykhoda, c).allowed)
    }

    @Test fun `language beats script`() {
        assertTrue(decide(FilterSettings(languages = mapOf("ru" to AlwaysShow), scripts = mapOf(Script.Cyrillic to Hide)), vykhoda).allowed)
        assertFalse(decide(FilterSettings(languages = mapOf("ru" to Hide), scripts = mapOf(Script.Cyrillic to AlwaysShow)), vykhoda).allowed)
    }

    @Test fun `artist beats script, levels skipped`() =
        assertTrue(decide(FilterSettings(artists = listOf(ArtistRule("UCkino", "Кино", AlwaysShow)), scripts = mapOf(Script.Cyrillic to Hide)), zvezda).allowed)

    @Test fun `inside a level Always show beats Hide`() {
        val duet = song("Duet", "A" to "UCa", "B" to "UCb")
        val s = FilterSettings(artists = listOf(ArtistRule("UCa", "A", Hide), ArtistRule("UCb", "B", AlwaysShow)))
        assertEquals(Decision(AlwaysShow, Level.Artist, "B"), decide(s, duet))
        val c = FilterSettings(custom = listOf(CustomRule("du", Hide), CustomRule("et", AlwaysShow)))
        assertTrue(decide(c, duet).allowed)
        val k = FilterSettings(countries = mapOf("RU" to Hide, "UA" to AlwaysShow))
        assertTrue(decide(k, duet, mapOf("UCa" to "RU", "UCb" to "UA")).allowed)
    }

    @Test fun `a Show rule is no opinion and the next level decides`() {
        // Russian has no rule (Show); Cyrillic is hidden: the script level decides.
        val s = FilterSettings(scripts = mapOf(Script.Cyrillic to Hide), languages = mapOf("uk" to AlwaysShow))
        assertEquals(Level.Script, decide(s, vykhoda).level)
    }
}
