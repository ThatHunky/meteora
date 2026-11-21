package dev.thathunky.meteora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.thathunky.meteora.R
import java.util.Locale

/** The second axis of a theme next to its colours: type, shapes, surfaces and component variants. */
enum class LookId { Expressive, Terminal, Flat, Retro, Soft }

/** Which font a look uses. An enum, not a FontFamily, so the table stays testable on the JVM. */
enum class Typeface { System, Mono, SerifTitles }

/** How cards and containers are drawn. */
enum class SurfaceStyle { Tonal, Outline, HardShadow }

enum class ProgressStyle { Wavy, Blocks, ThinLine, ThickBar, WavyThick }
enum class LoadingStyle { Morphing, TextSpinner, ThinCircle, Squares }
enum class HeaderStyle { Emphasized, Hash, SmallCaps, SerifRule, Large }
// The tabs row and the mini player row of the bottom dock.
enum class NavStyle { Tabs, StatusLine, IconsLine, Boxes, TallTabs }
enum class MiniStyle { Docked, StatusLine, FlatBar, Boxed, DockedLarge }

/** What the full player's artwork does on pause. */
enum class PauseArt { Shrink, Nothing, Dim }

data class Look(
    val id: LookId,
    val typeface: Typeface,
    /** Labels (buttons, nav, small headers) drawn in capitals. */
    val capsLabels: Boolean,
    /** Labels and text controls drawn in lower case. */
    val lowerLabels: Boolean,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    /** Buttons and chips are fully rounded. */
    val pills: Boolean,
    /** Outline width on surfaces and artwork; 0 = none. */
    val outline: Dp,
    /** Hard, unblurred shadow offset down-right; 0 = none. */
    val hardShadow: Dp,
    val surface: SurfaceStyle,
    /** Strength of the Now Playing and page header gradients: 0, 0.5 or 1. */
    val gradients: Float,
    /** Height of a two-line list row. */
    val rowHeight: Dp,
    /** Thumbnail size in a list row. */
    val rowArt: Dp,
    /** Extra space between list rows. */
    val rowGap: Dp,
    val screenPadding: Dp,
    val progress: ProgressStyle,
    val loading: LoadingStyle,
    val header: HeaderStyle,
    val nav: NavStyle,
    val mini: MiniStyle,
    /** Height of the docked mini player row; scrolling content leaves this much free above the tabs. */
    val miniSpace: Dp,
    val pauseArt: PauseArt,
    /** Controls with a text form (prev, next, like, queue) are drawn as text. */
    val textControls: Boolean,
    /** Buttons change shape when pressed. */
    val morphs: Boolean,
    val expressiveMotion: Boolean,
    /** The Artwork shape setting applies; otherwise the look fixes the shape. */
    val artworkSetting: Boolean,
)

object Looks {
    val Expressive = Look(
        id = LookId.Expressive, typeface = Typeface.System, capsLabels = false, lowerLabels = false,
        cornerSmall = 10.dp, cornerMedium = 20.dp, cornerLarge = 28.dp, pills = true,
        outline = 0.dp, hardShadow = 0.dp, surface = SurfaceStyle.Tonal, gradients = 1f,
        rowHeight = 72.dp, rowArt = 52.dp, rowGap = 0.dp, screenPadding = 16.dp,
        progress = ProgressStyle.Wavy, loading = LoadingStyle.Morphing, header = HeaderStyle.Emphasized,
        nav = NavStyle.Tabs, mini = MiniStyle.Docked, miniSpace = 64.dp, pauseArt = PauseArt.Shrink,
        textControls = false, morphs = true, expressiveMotion = true, artworkSetting = true,
    )

    val Terminal = Look(
        id = LookId.Terminal, typeface = Typeface.Mono, capsLabels = false, lowerLabels = true,
        cornerSmall = 0.dp, cornerMedium = 0.dp, cornerLarge = 0.dp, pills = false,
        outline = 1.dp, hardShadow = 0.dp, surface = SurfaceStyle.Outline, gradients = 0f,
        rowHeight = 48.dp, rowArt = 36.dp, rowGap = 0.dp, screenPadding = 12.dp,
        progress = ProgressStyle.Blocks, loading = LoadingStyle.TextSpinner, header = HeaderStyle.Hash,
        // A 48 dp status line, not the plan's 32 dp: its tabs must be 48 dp to touch.
        nav = NavStyle.StatusLine, mini = MiniStyle.StatusLine, miniSpace = 48.dp, pauseArt = PauseArt.Nothing,
        textControls = true, morphs = false, expressiveMotion = false, artworkSetting = false,
    )

    val Flat = Look(
        id = LookId.Flat, typeface = Typeface.System, capsLabels = false, lowerLabels = false,
        cornerSmall = 8.dp, cornerMedium = 8.dp, cornerLarge = 8.dp, pills = false,
        outline = 0.dp, hardShadow = 0.dp, surface = SurfaceStyle.Tonal, gradients = 0f,
        rowHeight = 56.dp, rowArt = 40.dp, rowGap = 0.dp, screenPadding = 16.dp,
        progress = ProgressStyle.ThinLine, loading = LoadingStyle.ThinCircle, header = HeaderStyle.SmallCaps,
        nav = NavStyle.IconsLine, mini = MiniStyle.FlatBar, miniSpace = 56.dp, pauseArt = PauseArt.Dim,
        textControls = false, morphs = false, expressiveMotion = false, artworkSetting = false,
    )

    val Retro = Look(
        id = LookId.Retro, typeface = Typeface.SerifTitles, capsLabels = true, lowerLabels = false,
        cornerSmall = 2.dp, cornerMedium = 2.dp, cornerLarge = 2.dp, pills = false,
        outline = 2.dp, hardShadow = 3.dp, surface = SurfaceStyle.HardShadow, gradients = 0f,
        rowHeight = 72.dp, rowArt = 52.dp, rowGap = 0.dp, screenPadding = 16.dp,
        progress = ProgressStyle.ThickBar, loading = LoadingStyle.Squares, header = HeaderStyle.SerifRule,
        nav = NavStyle.Boxes, mini = MiniStyle.Boxed, miniSpace = 68.dp, pauseArt = PauseArt.Nothing,
        textControls = false, morphs = false, expressiveMotion = false, artworkSetting = false,
    )

    val Soft = Look(
        id = LookId.Soft, typeface = Typeface.System, capsLabels = false, lowerLabels = false,
        cornerSmall = 16.dp, cornerMedium = 24.dp, cornerLarge = 32.dp, pills = true,
        outline = 0.dp, hardShadow = 0.dp, surface = SurfaceStyle.Tonal, gradients = 0.5f,
        rowHeight = 76.dp, rowArt = 56.dp, rowGap = 4.dp, screenPadding = 20.dp,
        progress = ProgressStyle.WavyThick, loading = LoadingStyle.Morphing, header = HeaderStyle.Large,
        nav = NavStyle.TallTabs, mini = MiniStyle.DockedLarge, miniSpace = 76.dp, pauseArt = PauseArt.Shrink,
        textControls = false, morphs = false, expressiveMotion = true, artworkSetting = true,
    )

    val all: List<Look> = listOf(Expressive, Terminal, Flat, Retro, Soft)

    fun of(id: LookId): Look = all.first { it.id == id }
}

val LocalLook = staticCompositionLocalOf { Looks.Expressive }

/** The look in force: the Style override if one is set, else the theme's own. */
fun effectiveLook(theme: AppTheme, override: LookId?): Look = Looks.of(override ?: theme.look)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** The font a look sets its titles in, for previews. */
fun titleFontFor(typeface: Typeface): FontFamily = when (typeface) {
    Typeface.System -> FontFamily.Default
    Typeface.Mono -> JetBrainsMono
    Typeface.SerifTitles -> FontFamily.Serif
}

private val base = Typography()

private fun TextStyle.font(f: FontFamily) = copy(fontFamily = f)

/** Every one of the 30 styles, so titles (the Emphasized ones) change with body text. */
private fun Typography.mapAll(f: (TextStyle, isTitle: Boolean, isLabel: Boolean) -> TextStyle) = Typography(
    displayLarge = f(displayLarge, true, false), displayMedium = f(displayMedium, true, false), displaySmall = f(displaySmall, true, false),
    headlineLarge = f(headlineLarge, true, false), headlineMedium = f(headlineMedium, true, false), headlineSmall = f(headlineSmall, true, false),
    titleLarge = f(titleLarge, true, false), titleMedium = f(titleMedium, true, false), titleSmall = f(titleSmall, true, false),
    bodyLarge = f(bodyLarge, false, false), bodyMedium = f(bodyMedium, false, false), bodySmall = f(bodySmall, false, false),
    labelLarge = f(labelLarge, false, true), labelMedium = f(labelMedium, false, true), labelSmall = f(labelSmall, false, true),
    displayLargeEmphasized = f(displayLargeEmphasized, true, false), displayMediumEmphasized = f(displayMediumEmphasized, true, false),
    displaySmallEmphasized = f(displaySmallEmphasized, true, false), headlineLargeEmphasized = f(headlineLargeEmphasized, true, false),
    headlineMediumEmphasized = f(headlineMediumEmphasized, true, false), headlineSmallEmphasized = f(headlineSmallEmphasized, true, false),
    titleLargeEmphasized = f(titleLargeEmphasized, true, false), titleMediumEmphasized = f(titleMediumEmphasized, true, false),
    titleSmallEmphasized = f(titleSmallEmphasized, true, false), bodyLargeEmphasized = f(bodyLargeEmphasized, false, false),
    bodyMediumEmphasized = f(bodyMediumEmphasized, false, false), bodySmallEmphasized = f(bodySmallEmphasized, false, false),
    labelLargeEmphasized = f(labelLargeEmphasized, false, true), labelMediumEmphasized = f(labelMediumEmphasized, false, true),
    labelSmallEmphasized = f(labelSmallEmphasized, false, true),
)

private fun TextUnit.plus(d: Float): TextUnit = if (isSpecified) (value + d).sp else this

fun typographyFor(look: Look): Typography = when (look.id) {
    // Untouched, so the Expressive look is exactly the app as it was.
    LookId.Expressive -> base
    // Mono glyphs are wide: a step smaller keeps titles and rows on one line.
    LookId.Terminal -> base.mapAll { s, title, _ ->
        s.font(JetBrainsMono).copy(
            fontSize = s.fontSize.plus(if (title) -2f else -1f),
            lineHeight = s.lineHeight.plus(if (title) -2f else -1f),
            letterSpacing = 0.sp,
            fontWeight = if (title && s.fontWeight != null && s.fontWeight!! >= FontWeight.Medium) FontWeight.Bold else s.fontWeight,
        )
    }
    // Lighter and tighter: no bold anywhere, titles pulled together.
    LookId.Flat -> base.mapAll { s, title, _ ->
        s.copy(
            fontWeight = when {
                title && (s.fontWeight ?: FontWeight.Normal) >= FontWeight.Medium -> FontWeight.Medium
                title -> FontWeight.Light
                else -> s.fontWeight
            },
            letterSpacing = if (title) (-0.02).em else s.letterSpacing,
        )
    }
    LookId.Retro -> base.mapAll { s, title, label ->
        when {
            title -> s.font(FontFamily.Serif).copy(fontWeight = if ((s.fontWeight ?: FontWeight.Normal) >= FontWeight.Medium) FontWeight.Bold else FontWeight.Normal)
            label -> s.copy(letterSpacing = 0.08.em, fontWeight = FontWeight.Bold)
            else -> s
        }
    }
    // Medium weights and a slightly larger body.
    LookId.Soft -> base.mapAll { s, title, _ ->
        s.copy(
            fontWeight = if (title) FontWeight.Medium else s.fontWeight,
            fontSize = if (title) s.fontSize else s.fontSize.plus(1f),
            lineHeight = if (title) s.lineHeight else s.lineHeight.plus(2f),
        )
    }
}

private val defaultShapes = Shapes()

fun shapesFor(look: Look): Shapes = when (look.id) {
    LookId.Expressive -> defaultShapes
    else -> {
        fun r(d: Dp) = RoundedCornerShape(d)
        val s = look.cornerSmall
        val m = look.cornerMedium
        val l = look.cornerLarge
        Shapes(
            extraSmall = r(minOf(s, 4.dp)),
            small = r(s),
            medium = r(m),
            large = r(l),
            extraLarge = r(l),
            largeIncreased = r(l),
            extraLargeIncreased = r(l),
            extraExtraLarge = r(l),
        )
    }
}

fun motionFor(look: Look): MotionScheme = if (look.expressiveMotion) MotionScheme.expressive() else MotionScheme.standard()

/** A label as the look draws it. Case is presentation, so it changes here and never in strings.xml. */
fun labelCase(text: String, look: Look, locale: Locale): String = when {
    look.lowerLabels -> text.lowercase(locale)
    look.capsLabels -> text.uppercase(locale)
    else -> text
}

/** The Terminal seek bar: [cells] characters wide whatever the progress, so it never jitters. */
fun blockBar(fraction: Float, cells: Int): String {
    if (cells <= 0) return ""
    val filled = (fraction.coerceIn(0f, 1f) * cells).toInt().coerceIn(0, cells)
    return "█".repeat(filled) + "░".repeat(cells - filled)
}
