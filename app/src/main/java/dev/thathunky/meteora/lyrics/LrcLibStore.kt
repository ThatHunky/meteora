package dev.thathunky.meteora.lyrics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Find synced lyrics on LRCLIB". Off by default: when on, a track's title, artist, album and
 * length go to lrclib.net whenever YouTube has no timed lyrics for it.
 */
@Singleton
class LrcLibStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val KEY = "lrclib"
    }
}
