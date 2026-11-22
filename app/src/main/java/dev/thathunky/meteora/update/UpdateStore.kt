package dev.thathunky.meteora.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The updater's settings and memory, in the preferences file `updates`. */
@Singleton
class UpdateStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    /** Check once a day when the app is opened. */
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    // On while only alphas exist: with it off there is nothing to update to.
    private val _preReleases = MutableStateFlow(prefs.getBoolean(KEY_PRE, true))
    val includePreReleases: StateFlow<Boolean> = _preReleases.asStateFlow()

    private val _lastCheck = MutableStateFlow(prefs.getLong(KEY_LAST, -1).takeIf { it >= 0 })
    /** When GitHub last answered a check (or said "not now"), in ms; null before the first. */
    val lastCheckAt: StateFlow<Long?> = _lastCheck.asStateFlow()

    private val _skipped = MutableStateFlow(prefs.getString(KEY_SKIPPED, null)?.let(Version::parse))
    val skipped: StateFlow<Version?> = _skipped.asStateFlow()

    fun setAuto(on: Boolean) {
        _auto.value = on
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun setIncludePreReleases(on: Boolean) {
        _preReleases.value = on
        prefs.edit().putBoolean(KEY_PRE, on).apply()
    }

    fun setLastCheckAt(ms: Long) {
        _lastCheck.value = ms
        prefs.edit().putLong(KEY_LAST, ms).apply()
    }

    fun setSkipped(v: Version?) {
        _skipped.value = v
        prefs.edit().apply { if (v == null) remove(KEY_SKIPPED) else putString(KEY_SKIPPED, v.toString()) }.apply()
    }

    /**
     * The release last found and not yet installed or skipped, so the offer survives the app
     * being killed (the check after it is not due until tomorrow).
     */
    var offered: Release?
        get() = prefs.getString(KEY_OFFERED, null)?.let(Releases::decode)
        set(r) {
            prefs.edit().apply { if (r == null) remove(KEY_OFFERED) else putString(KEY_OFFERED, Releases.encode(r)) }.apply()
        }

    companion object {
        private const val KEY_OFFERED = "offered"
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val KEY_AUTO = "auto"
        private const val KEY_PRE = "pre_releases"
        private const val KEY_LAST = "last_check"
        private const val KEY_SKIPPED = "skipped"

        /**
         * The once-a-day rule: due when there was no check yet, a day or more has passed, or the
         * last check is in the future (the clock was moved back), so a wrong clock cannot stop checks.
         */
        fun isDue(lastCheckAt: Long?, now: Long): Boolean =
            lastCheckAt == null || now < lastCheckAt || now - lastCheckAt >= DAY_MS
    }
}
