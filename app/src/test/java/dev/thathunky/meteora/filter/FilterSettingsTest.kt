package dev.thathunky.meteora.filter

import dev.thathunky.meteora.ytm.ArtistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSettingsTest {
    @Test fun `defaults hide nothing and look nothing up`() {
        val s = FilterSettings()
        assertFalse(s.hasRules)
        assertFalse(s.wantsCountries)
        Place.entries.forEach { assertTrue(s.appliesTo(it)) }
        assertTrue(s.musicBrainz)
    }

    @Test fun `round trip`() {
        val s = FilterSettings(
            artists = listOf(ArtistRule("UC1", "Кино", Verdict.Hide)),
            custom = listOf(CustomRule("remix", Verdict.AlwaysShow)),
            countries = mapOf("RU" to Verdict.Hide),
            languages = mapOf("uk" to Verdict.AlwaysShow),
            scripts = mapOf(Script.Cyrillic to Verdict.Hide),
            placesOff = setOf(Place.Home),
            musicBrainz = false,
        )
        assertEquals(s, FilterSettings.decode(s.encode()))
    }

    @Test fun `unreadable or missing settings are the defaults`() {
        assertEquals(FilterSettings(), FilterSettings.decode(null))
        assertEquals(FilterSettings(), FilterSettings.decode("{not json"))
        assertEquals(FilterSettings(), FilterSettings.decode("{}"))
    }

    @Test fun `countries are looked up only with a country rule and the switch on`() {
        assertTrue(FilterSettings(countries = mapOf("RU" to Verdict.Hide)).wantsCountries)
        assertFalse(FilterSettings(countries = mapOf("RU" to Verdict.Hide), musicBrainz = false).wantsCountries)
    }

    @Test fun `one rule per artist, replaced or removed`() {
        val kino = ArtistRef("Кино", "UC1")
        val s = FilterSettings().withArtist(kino, Verdict.Hide).withArtist(kino, Verdict.AlwaysShow)
        assertEquals(listOf(ArtistRule("UC1", "Кино", Verdict.AlwaysShow)), s.artists)
        assertEquals(emptyList<ArtistRule>(), s.withArtist(kino, null).artists)
        val byName = FilterSettings().withArtist(ArtistRef("Сплин", null), Verdict.Hide).withArtist(ArtistRef("сплин", null), null)
        assertEquals(emptyList<ArtistRule>(), byName.artists)
    }
}
