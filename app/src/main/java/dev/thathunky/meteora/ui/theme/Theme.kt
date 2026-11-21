package dev.thathunky.meteora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Seed for the "Meteora" theme, and for album-art mode before any art has loaded. */
val MeteoraSeed = Color(0xFF6750A4)

/** The scheme [theme] has right now, before animation. Also used for the previews in Settings. */
@Composable
fun schemeFor(theme: AppTheme, darkTheme: Boolean, pureBlack: Boolean, albumSeed: Color?): ColorScheme {
    val context = LocalContext.current
    val scheme = when (theme) {
        is AppTheme.Fixed -> return fixedScheme(theme.palette)
        AppTheme.System ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        AppTheme.Album, AppTheme.Meteora -> rememberDynamicColorScheme(
            seedColor = if (theme == AppTheme.Album) albumSeed ?: MeteoraSeed else MeteoraSeed,
            isDark = darkTheme,
            style = PaletteStyle.Expressive,
        )
    }
    return if (pureBlack && darkTheme) scheme.pureBlack() else scheme
}

@Composable
fun MeteoraTheme(
    theme: AppTheme = AppTheme.Album,
    mode: ThemeMode = ThemeMode.System,
    pureBlack: Boolean = false,
    albumSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = isDark(theme, mode, isSystemInDarkTheme())
    val target = schemeFor(theme, darkTheme, pureBlack, albumSeed)
    MaterialExpressiveTheme(
        colorScheme = target.animated(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
