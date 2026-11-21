package dev.thathunky.meteora.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Pure, so a stored value from an older or newer build can be tested without Android.
fun parseThemeId(stored: String?): String = AllThemes.firstOrNull { it.id == stored }?.id ?: AppTheme.Album.id
fun parseThemeMode(stored: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
fun parseArtworkShape(stored: String?): ArtworkShape = ArtworkShape.entries.firstOrNull { it.name == stored } ?: ArtworkShape.Rounded
/** null = the theme's own look. Stored as "" for that, so unknown values mean the same. */
fun parseLookOverride(stored: String?): LookId? = LookId.entries.firstOrNull { it.name == stored }

/** The appearance settings. One instance, so every screen sees a change at once. */
@Singleton
class ThemeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _themeId = MutableStateFlow(parseThemeId(prefs.getString(KEY_THEME, null)))
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    private val _mode = MutableStateFlow(parseThemeMode(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _pureBlack = MutableStateFlow(prefs.getBoolean(KEY_PURE_BLACK, false))
    val pureBlack: StateFlow<Boolean> = _pureBlack.asStateFlow()

    private val _artworkShape = MutableStateFlow(parseArtworkShape(prefs.getString(KEY_ARTWORK, null)))
    val artworkShape: StateFlow<ArtworkShape> = _artworkShape.asStateFlow()

    private val _look = MutableStateFlow(parseLookOverride(prefs.getString(KEY_LOOK, null)))
    val look: StateFlow<LookId?> = _look.asStateFlow()

    fun setLook(l: LookId?) {
        _look.value = l
        prefs.edit().putString(KEY_LOOK, l?.name ?: "").apply()
    }

    fun setThemeId(id: String) {
        val v = parseThemeId(id)
        _themeId.value = v
        prefs.edit().putString(KEY_THEME, v).apply()
    }

    fun setMode(m: ThemeMode) {
        _mode.value = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
    }

    fun setPureBlack(on: Boolean) {
        _pureBlack.value = on
        prefs.edit().putBoolean(KEY_PURE_BLACK, on).apply()
    }

    fun setArtworkShape(s: ArtworkShape) {
        _artworkShape.value = s
        prefs.edit().putString(KEY_ARTWORK, s.name).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_MODE = "mode"
        const val KEY_PURE_BLACK = "pure_black"
        const val KEY_ARTWORK = "artwork_shape"
        const val KEY_LOOK = "look"
    }
}
