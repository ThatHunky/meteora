package dev.thathunky.meteora.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The "Autoplay" setting: keep playing similar music when a collection ends on the phone. On by default. */
@Singleton
class AutoplayStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val KEY = "autoplay"
    }
}
