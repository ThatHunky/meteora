package dev.thathunky.meteora.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.thathunky.meteora.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Light or dark for the dynamic themes; fixed themes ignore it. */
enum class ThemeMode { System, Light, Dark }

/** How album art is cut: [Rounded] shows the whole cover, [Scalloped] is the cookie. */
enum class ArtworkShape { Rounded, Scalloped }

/** Read by the four artwork call sites, so they need no new parameters. */
val LocalArtworkShape = staticCompositionLocalOf { ArtworkShape.Rounded }

/** The current album seed, for the album card's live preview in Settings. Not static: it changes per track. */
val LocalAlbumSeed = compositionLocalOf<Color?> { null }

/**
 * A hand-picked palette. [raised] holds up to two of the scheme's own raised-surface tones,
 * lowest first; empty means the surfaces are blended from [background] towards [foreground].
 */
data class Palette(
    val background: Color,
    val raised: List<Color>,
    val foreground: Color,
    val dimmed: Color,
    val accent1: Color,
    val accent2: Color,
    val accent3: Color,
    val red: Color,
    val isDark: Boolean,
)

sealed interface AppTheme {
    val id: String

    /** The look this theme brings unless Settings overrides the Style. */
    val look: LookId

    sealed interface Dynamic : AppTheme {
        @get:StringRes val nameRes: Int?
        override val look: LookId get() = LookId.Expressive
    }

    data object Album : Dynamic {
        override val id = "album"
        override val nameRes = R.string.theme_album
    }

    data object System : Dynamic {
        override val id = "system"
        override val nameRes = R.string.theme_system
    }

    /** "Meteora" is a proper name, so it has no string resource. */
    data object Meteora : Dynamic {
        override val id = "meteora"
        override val nameRes: Int? = null
    }

    data class Fixed(
        override val id: String,
        val name: String,
        val palette: Palette,
        override val look: LookId,
        val author: String? = null,
    ) : AppTheme
}

private fun hex(v: Long) = Color(0xFF000000 or v)

// Background, foreground, dimmed foreground and accents exactly as N7Art asked. The red is
// Moonfly's, the scheme N7Art named as the reference, since the request gives none.
val MonoPalette = Palette(
    background = hex(0x000000),
    raised = emptyList(),
    foreground = hex(0xeeeeee),
    dimmed = hex(0xaaaaaa),
    accent1 = hex(0xffffff),
    accent2 = hex(0xeeeeee),
    accent3 = hex(0xaaaaaa),
    red = hex(0xff5d5d),
    isDark = true,
)

// https://github.com/bluz71/vim-moonfly-colors (lua/moonfly/init.lua). Background is #000000
// instead of the scheme's #080808: N7Art wants it on pure black.
val MoonflyPalette = Palette(
    background = hex(0x000000),
    raised = listOf(hex(0x1c1c1c), hex(0x262626)), // grey11, grey15
    foreground = hex(0xc6c6c6), // white
    dimmed = hex(0x9e9e9e), // grey62
    accent1 = hex(0x80a0ff), // blue
    accent2 = hex(0x36c692), // emerald
    accent3 = hex(0xcf87e8), // violet
    red = hex(0xff5d5d), // red
    isDark = true,
)

// https://github.com/catppuccin/palette (palette.json), the official Catppuccin palette.
val CatppuccinMochaPalette = Palette(
    background = hex(0x1e1e2e), // base
    raised = listOf(hex(0x313244), hex(0x45475a)), // surface0, surface1
    foreground = hex(0xcdd6f4), // text
    dimmed = hex(0xa6adc8), // subtext0
    accent1 = hex(0xcba6f7), // mauve
    accent2 = hex(0x89b4fa), // blue
    accent3 = hex(0xa6e3a1), // green
    red = hex(0xf38ba8), // red
    isDark = true,
)

// https://github.com/catppuccin/palette (palette.json). Raised tones are mantle and crust:
// on a light scheme the containers step darker.
val CatppuccinLattePalette = Palette(
    background = hex(0xeff1f5), // base
    raised = listOf(hex(0xe6e9ef), hex(0xdce0e8)), // mantle, crust
    foreground = hex(0x4c4f69), // text
    dimmed = hex(0x5c5f77), // subtext1 (subtext0 is under 4.5 on base)
    accent1 = hex(0x8839ef), // mauve
    accent2 = hex(0x1e66f5), // blue
    accent3 = hex(0x179299), // teal
    red = hex(0xd20f39), // red
    isDark = false,
)

// https://github.com/morhetz/gruvbox (colors/gruvbox.vim), dark mode, medium contrast.
val GruvboxPalette = Palette(
    background = hex(0x282828), // bg0 = dark0
    raised = listOf(hex(0x3c3836), hex(0x504945)), // bg1, bg2
    foreground = hex(0xebdbb2), // fg1 = light1
    dimmed = hex(0xa89984), // fg4 = light4
    accent1 = hex(0xfabd2f), // bright_yellow
    accent2 = hex(0x8ec07c), // bright_aqua
    accent3 = hex(0xfe8019), // bright_orange
    red = hex(0xfb4934), // bright_red
    isDark = true,
)

// https://github.com/nordtheme/nord (src/nord.css). Nord has no dimmed text tone that reads
// on nord0 (nord3 is for comments), so nord6 is the text and nord4 the dimmed text.
val NordPalette = Palette(
    background = hex(0x2e3440), // nord0
    raised = listOf(hex(0x3b4252), hex(0x434c5e)), // nord1, nord2
    foreground = hex(0xeceff4), // nord6
    dimmed = hex(0xd8dee9), // nord4
    accent1 = hex(0x88c0d0), // nord8
    accent2 = hex(0x81a1c1), // nord9
    accent3 = hex(0xb48ead), // nord15
    red = hex(0xbf616a), // nord11
    isDark = true,
)

// https://github.com/rose-pine/palette (palette.json, "main" variant), the official Rosé Pine palette.
val RosePinePalette = Palette(
    background = hex(0x191724), // base
    raised = listOf(hex(0x1f1d2e), hex(0x26233a)), // surface, overlay
    foreground = hex(0xe0def4), // text
    dimmed = hex(0x908caa), // subtle
    accent1 = hex(0xc4a7e7), // iris
    accent2 = hex(0x9ccfd8), // foam
    accent3 = hex(0xebbcba), // rose
    red = hex(0xeb6f92), // love
    isDark = true,
)

// https://github.com/folke/tokyonight.nvim (lua/tokyonight/colors/storm.lua, with night.lua's
// overrides): the "night" style.
val TokyoNightPalette = Palette(
    background = hex(0x1a1b26), // bg (night)
    raised = listOf(hex(0x292e42), hex(0x3b4261)), // bg_highlight, fg_gutter
    foreground = hex(0xc0caf5), // fg
    dimmed = hex(0xa9b1d6), // fg_dark
    accent1 = hex(0x7aa2f7), // blue
    accent2 = hex(0xbb9af7), // magenta
    accent3 = hex(0x9ece6a), // green
    red = hex(0xf7768e), // red
    isDark = true,
)

val FixedThemes: List<AppTheme.Fixed> = listOf(
    AppTheme.Fixed("mono", "Mono", MonoPalette, LookId.Terminal, author = "N7Art"),
    AppTheme.Fixed("moonfly", "Moonfly", MoonflyPalette, LookId.Terminal),
    AppTheme.Fixed("catppuccin", "Catppuccin Mocha", CatppuccinMochaPalette, LookId.Soft),
    AppTheme.Fixed("gruvbox", "Gruvbox", GruvboxPalette, LookId.Retro),
    AppTheme.Fixed("nord", "Nord", NordPalette, LookId.Flat),
    AppTheme.Fixed("rosepine", "Rosé Pine", RosePinePalette, LookId.Soft),
    AppTheme.Fixed("tokyonight", "Tokyo Night", TokyoNightPalette, LookId.Flat),
    AppTheme.Fixed("latte", "Catppuccin Latte", CatppuccinLattePalette, LookId.Soft),
)

val AllThemes: List<AppTheme> = listOf(AppTheme.Album, AppTheme.System, AppTheme.Meteora) + FixedThemes

fun themeById(id: String): AppTheme = AllThemes.firstOrNull { it.id == id } ?: AppTheme.Album

/** Fixed themes are the mode they were made for; dynamic ones follow [mode]. */
fun isDark(theme: AppTheme, mode: ThemeMode, systemDark: Boolean): Boolean = when (theme) {
    is AppTheme.Fixed -> theme.palette.isDark
    else -> when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** Straight sRGB mix, the same as painting [b] at opacity [t] over [a]. */
internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

internal fun relativeLuminance(c: Color): Double =
    0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

/** WCAG contrast ratio, 1 to 21. */
internal fun contrast(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Dynamic dark scheme on pure black: the base surfaces go black, the containers 60 % darker. */
fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = mix(surfaceContainerLow, Color.Black, 0.6f),
    surfaceContainer = mix(surfaceContainer, Color.Black, 0.6f),
    surfaceContainerHigh = mix(surfaceContainerHigh, Color.Black, 0.6f),
    surfaceContainerHighest = mix(surfaceContainerHighest, Color.Black, 0.6f),
    surfaceBright = mix(surfaceBright, Color.Black, 0.6f),
)

/** Maps every fixed palette onto Material roles the same way. */
fun fixedScheme(p: Palette): ColorScheme {
    val bg = p.background
    val fg = p.foreground
    fun on(c: Color) = if (contrast(c, bg) >= contrast(c, fg)) bg else fg
    fun container(c: Color) = mix(bg, c, 0.24f)
    // The accent itself, moved towards the text colour until it reads on its container.
    fun onContainer(c: Color): Color {
        val box = container(c)
        var t = 0f
        var out = c
        while (contrast(out, box) < 4.5 && t < 1f) {
            t = min(1f, t + 0.05f)
            out = mix(c, fg, t)
        }
        return out
    }
    // Towards the background: darker on a dark scheme, lighter on a light one, so it reads on inverseSurface.
    fun inverse(c: Color) = mix(c, bg, 0.4f)
    fun fixedDim(c: Color) = mix(c, bg, 0.2f)
    fun onFixedVariant(c: Color) = mix(on(c), c, 0.25f)

    val steps = when (p.raised.size) {
        0 -> listOf(0.04f, 0.07f, 0.10f, 0.13f, 0.16f).map { mix(bg, fg, it) }
        1 -> {
            val r = p.raised[0]
            listOf(mix(bg, r, 0.33f), mix(bg, r, 0.66f), r, mix(r, fg, 0.04f), mix(r, fg, 0.08f))
        }
        else -> {
            val (r1, r2) = p.raised
            listOf(mix(bg, r1, 0.5f), r1, mix(r1, r2, 0.5f), r2, mix(r2, fg, 0.06f))
        }
    }
    val (low, mid, high, highest, bright) = steps
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent1,
        onPrimary = on(p.accent1),
        primaryContainer = container(p.accent1),
        onPrimaryContainer = onContainer(p.accent1),
        inversePrimary = inverse(p.accent1),
        secondary = p.accent2,
        onSecondary = on(p.accent2),
        secondaryContainer = container(p.accent2),
        onSecondaryContainer = onContainer(p.accent2),
        tertiary = p.accent3,
        onTertiary = on(p.accent3),
        tertiaryContainer = container(p.accent3),
        onTertiaryContainer = onContainer(p.accent3),
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = highest,
        onSurfaceVariant = p.dimmed,
        surfaceTint = p.accent1,
        inverseSurface = fg,
        inverseOnSurface = bg,
        outline = p.dimmed,
        outlineVariant = mix(bg, p.dimmed, 0.5f),
        // On a light scheme the steps go darker, so the brightest surface is the background.
        surfaceBright = if (p.isDark) bright else bg,
        surfaceDim = bg,
        surfaceContainer = mid,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        surfaceContainerLow = low,
        surfaceContainerLowest = bg,
        error = p.red,
        onError = on(p.red),
        errorContainer = container(p.red),
        onErrorContainer = onContainer(p.red),
        scrim = Color.Black,
        primaryFixed = p.accent1,
        primaryFixedDim = fixedDim(p.accent1),
        onPrimaryFixed = on(p.accent1),
        onPrimaryFixedVariant = onFixedVariant(p.accent1),
        secondaryFixed = p.accent2,
        secondaryFixedDim = fixedDim(p.accent2),
        onSecondaryFixed = on(p.accent2),
        onSecondaryFixedVariant = onFixedVariant(p.accent2),
        tertiaryFixed = p.accent3,
        tertiaryFixedDim = fixedDim(p.accent3),
        onTertiaryFixed = on(p.accent3),
        onTertiaryFixedVariant = onFixedVariant(p.accent3),
    )
}
