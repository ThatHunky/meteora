package dev.thathunky.meteora.downloads

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.stream.StreamQuality
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Download quality, Wi-Fi only, the remembered Premium answer, and the smart downloads settings. */
@Singleton
class DownloadSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** Normal (140, about 130 kbps) or High (141, 256 kbps, Premium). */
    private val _quality = MutableStateFlow(StreamQuality.fromStored(prefs.getString(KEY_QUALITY, null))?.takeIf { it != StreamQuality.Low } ?: StreamQuality.High)
    val quality: StateFlow<StreamQuality> = _quality.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _smartEnabled = MutableStateFlow(prefs.getBoolean(KEY_SMART, false))
    val smartEnabled: StateFlow<Boolean> = _smartEnabled.asStateFlow()

    private val _smartSize = MutableStateFlow(prefs.getInt(KEY_SMART_SIZE, SMART_DEFAULT))
    val smartSize: StateFlow<Int> = _smartSize.asStateFlow()

    private val _lastRefresh = MutableStateFlow(prefs.getString(KEY_LAST_REFRESH, null))
    /** "kept|added|removed|atMs", or null before the first refresh. */
    val lastRefresh: StateFlow<String?> = _lastRefresh.asStateFlow()

    fun setQuality(q: StreamQuality) {
        _quality.value = q
        prefs.edit().putString(KEY_QUALITY, q.name).apply()
    }

    fun setWifiOnly(on: Boolean) {
        _wifiOnly.value = on
        prefs.edit().putBoolean(KEY_WIFI_ONLY, on).apply()
    }

    fun setSmartEnabled(on: Boolean) {
        _smartEnabled.value = on
        prefs.edit().putBoolean(KEY_SMART, on).apply()
    }

    fun setSmartSize(n: Int) {
        val v = n.coerceIn(0, SMART_MAX)
        _smartSize.value = v
        prefs.edit().putInt(KEY_SMART_SIZE, v).apply()
    }

    fun setLastRefresh(kept: Int, added: Int, removed: Int, atMs: Long) {
        val v = "$kept|$added|$removed|$atMs"
        _lastRefresh.value = v
        prefs.edit().putString(KEY_LAST_REFRESH, v).apply()
    }

    var premiumAtMs: Long?
        get() = prefs.getLong(KEY_PREMIUM_AT, -1).takeIf { it >= 0 }
        set(v) {
            prefs.edit().apply { if (v == null) remove(KEY_PREMIUM_AT) else putLong(KEY_PREMIUM_AT, v) }.apply()
        }

    companion object {
        const val SMART_DEFAULT = 100
        const val SMART_MAX = 500
        private const val KEY_QUALITY = "quality"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_PREMIUM_AT = "premium_at"
        private const val KEY_SMART = "smart"
        private const val KEY_SMART_SIZE = "smart_size"
        private const val KEY_LAST_REFRESH = "smart_last"
    }
}
