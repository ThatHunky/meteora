package dev.thathunky.meteora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Where the app's colors come from (PLAN.md Q12). */
enum class ColorSource { AlbumArt, System, Fixed }

/** Seed for "Fixed", and for album-art mode before any art has loaded. */
val MeteoraSeed = Color(0xFF6750A4)

@Composable
fun MeteoraTheme(
    colorSource: ColorSource = ColorSource.AlbumArt,
    albumSeed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val target = when (colorSource) {
        ColorSource.System ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        ColorSource.AlbumArt, ColorSource.Fixed -> rememberDynamicColorScheme(
            seedColor = if (colorSource == ColorSource.AlbumArt) albumSeed ?: MeteoraSeed else MeteoraSeed,
            isDark = darkTheme,
            style = PaletteStyle.Expressive,
        )
    }
    MaterialExpressiveTheme(
        colorScheme = target.animated(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
