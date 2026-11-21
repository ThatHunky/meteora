package dev.thathunky.meteora.ui.theme

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookTest {
    @Test fun `every theme maps to a look`() {
        for (t in AllThemes) assertEquals(t.id, t.look, Looks.of(t.look).id)
        assertEquals(LookId.Terminal, themeById("mono").look)
        assertEquals(LookId.Terminal, themeById("moonfly").look)
        assertEquals(LookId.Flat, themeById("nord").look)
        assertEquals(LookId.Flat, themeById("tokyonight").look)
        assertEquals(LookId.Retro, themeById("gruvbox").look)
        assertEquals(LookId.Soft, themeById("catppuccin").look)
        assertEquals(LookId.Soft, themeById("latte").look)
        assertEquals(LookId.Soft, themeById("rosepine").look)
        assertEquals(LookId.Expressive, AppTheme.Album.look)
        assertEquals(LookId.Expressive, AppTheme.System.look)
        assertEquals(LookId.Expressive, AppTheme.Meteora.look)
    }

    @Test fun `every look is in the table once`() {
        assertEquals(LookId.entries.toList(), Looks.all.map { it.id })
    }

    @Test fun `the Style override parses back`() {
        for (id in LookId.entries) assertEquals(id, parseLookOverride(id.name))
        assertNull(parseLookOverride(""))
        assertNull(parseLookOverride(null))
        assertNull(parseLookOverride("Brutalist"))
        assertNull(parseLookOverride("terminal"))
    }

    @Test fun `the override wins over the theme's look`() {
        assertEquals(LookId.Terminal, effectiveLook(AppTheme.Album, LookId.Terminal).id)
        assertEquals(LookId.Retro, effectiveLook(themeById("gruvbox"), null).id)
    }

    @Test fun `no two looks share font, corner and surface`() {
        val combos = Looks.all.map { Triple(it.typeface, it.cornerMedium, it.surface) }
        assertEquals(combos.size, combos.toSet().size)
    }

    @Test fun `no two looks share a nav bar, mini player or progress bar`() {
        assertEquals(Looks.all.size, Looks.all.map { it.nav }.toSet().size)
        assertEquals(Looks.all.size, Looks.all.map { it.mini }.toSet().size)
        assertEquals(Looks.all.size, Looks.all.map { it.progress }.toSet().size)
        assertEquals(Looks.all.size, Looks.all.map { it.header }.toSet().size)
    }

    @Test fun `block bar at 0, 50 and 100 percent`() {
        assertEquals("░░░░░░░░░░", blockBar(0f, 10))
        assertEquals("█████░░░░░", blockBar(0.5f, 10))
        assertEquals("██████████", blockBar(1f, 10))
        assertEquals("", blockBar(0.5f, 0))
    }

    @Test fun `block bar never changes length`() {
        for (cells in listOf(1, 7, 16, 40)) {
            for (i in -5..105) assertEquals(cells, blockBar(i / 100f, cells).length)
        }
    }

    @Test fun `labels change case with the given locale`() {
        val tr = Locale.forLanguageTag("tr")
        assertEquals("ıd", labelCase("ID", Looks.Terminal, tr))
        assertEquals("відтворити", labelCase("Відтворити", Looks.Terminal, Locale.forLanguageTag("uk")))
        assertEquals("ВІДТВОРИТИ", labelCase("Відтворити", Looks.Retro, Locale.forLanguageTag("uk")))
        assertEquals("Play", labelCase("Play", Looks.Expressive, Locale.ENGLISH))
    }
}
