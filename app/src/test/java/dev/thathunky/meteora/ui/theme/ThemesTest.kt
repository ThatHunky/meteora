package dev.thathunky.meteora.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    // Independent of the app's own helper, straight from the ARGB int (WCAG 2 formula).
    private fun lum(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xff) + 0.7152 * ch(argb shr 8 and 0xff) + 0.0722 * ch(argb and 0xff)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = lum(a.toArgb())
        val lb = lum(b.toArgb())
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertReads(theme: String, role: String, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        assertTrue("$theme $role contrast $r", r >= 4.5)
    }

    @Test fun `every fixed theme reads`() {
        for (t in FixedThemes) {
            val s = fixedScheme(t.palette)
            assertReads(t.id, "onPrimary/primary", s.onPrimary, s.primary)
            assertReads(t.id, "onSurface/surface", s.onSurface, s.surface)
            assertReads(t.id, "onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
            assertReads(t.id, "onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
        }
    }

    @Test fun `mono has exactly the requested colours`() {
        val s = fixedScheme(themeById("mono").let { (it as AppTheme.Fixed).palette })
        assertEquals(0xFF000000.toInt(), s.background.toArgb())
        assertEquals(0xFF000000.toInt(), s.surface.toArgb())
        assertEquals(0xFFEEEEEE.toInt(), s.onSurface.toArgb())
        assertEquals(0xFFEEEEEE.toInt(), s.onBackground.toArgb())
        assertEquals(0xFFAAAAAA.toInt(), s.onSurfaceVariant.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), s.primary.toArgb())
    }

    @Test fun `moonfly is on pure black`() {
        assertEquals(0xFF000000.toInt(), fixedScheme(MoonflyPalette).background.toArgb())
    }

    @Test fun `fixed roles are not the library purple`() {
        for (t in FixedThemes) {
            val s = fixedScheme(t.palette)
            assertEquals(t.id, s.primary, s.primaryFixed)
            assertEquals(t.id, s.tertiary, s.tertiaryFixed)
        }
    }

    @Test fun `theme ids are unique`() = assertEquals(AllThemes.size, AllThemes.map { it.id }.toSet().size)

    @Test fun `fixed themes ignore the mode`() {
        val latte = themeById("latte")
        assertEquals(false, isDark(latte, ThemeMode.Dark, systemDark = true))
        assertEquals(true, isDark(themeById("nord"), ThemeMode.Light, systemDark = false))
        assertEquals(true, isDark(AppTheme.Album, ThemeMode.System, systemDark = true))
        assertEquals(false, isDark(AppTheme.Album, ThemeMode.Light, systemDark = true))
    }
}

class ThemeStoreParsingTest {
    @Test fun `known values parse`() {
        assertEquals("nord", parseThemeId("nord"))
        assertEquals(ThemeMode.Dark, parseThemeMode("Dark"))
        assertEquals(ArtworkShape.Scalloped, parseArtworkShape("Scalloped"))
    }

    @Test fun `unknown or missing values fall back to the defaults`() {
        assertEquals("album", parseThemeId(null))
        assertEquals("album", parseThemeId("solarized"))
        assertEquals(ThemeMode.System, parseThemeMode(null))
        assertEquals(ThemeMode.System, parseThemeMode("dark"))
        assertEquals(ArtworkShape.Rounded, parseArtworkShape(null))
        assertEquals(ArtworkShape.Rounded, parseArtworkShape("Cookie"))
    }
}
