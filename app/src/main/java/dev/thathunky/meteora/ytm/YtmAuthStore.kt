package dev.thathunky.meteora.ytm

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.data.SecureStore
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.ytmAuthStore: DataStore<Preferences> by preferencesDataStore("ytm_auth")

/**
 * YouTube Music session from the in-app sign-in (PLAN.md Q4): the music.youtube.com cookie
 * header, Keystore-encrypted. Requests are signed with SAPISIDHASH like the web client does.
 */
@Singleton
class YtmAuthStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secure: SecureStore,
) : YtmAuthProvider {

    private val key = stringPreferencesKey("cookies_encrypted")

    val signedIn: Flow<Boolean> = context.ytmAuthStore.data.map { it[key] != null }

    @Volatile private var cached: String? = null
    @Volatile private var loaded = false

    override suspend fun headers(): Map<String, String>? {
        val cookies = cookies() ?: return null
        val sapisid = cookieValue(cookies, "SAPISID") ?: cookieValue(cookies, "__Secure-3PAPISID") ?: return null
        val ts = System.currentTimeMillis() / 1000
        return mapOf(
            "Cookie" to cookies,
            "Authorization" to "SAPISIDHASH ${ts}_${sha1("$ts $sapisid ${YtmClient.ORIGIN}")}",
            "X-Origin" to YtmClient.ORIGIN,
            "X-Goog-AuthUser" to "0",
        )
    }

    /** True if the cookie header is a real signed-in session. */
    fun isSession(cookies: String) =
        cookieValue(cookies, "SAPISID") != null || cookieValue(cookies, "__Secure-3PAPISID") != null

    suspend fun save(cookies: String) {
        cached = cookies
        loaded = true
        context.ytmAuthStore.edit { it[key] = secure.encrypt(cookies) }
    }

    /** Sign out: drop stored cookies *and* everything the login WebView kept. */
    suspend fun signOut() {
        cached = null
        loaded = true
        context.ytmAuthStore.edit { it.remove(key) }
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    private suspend fun cookies(): String? {
        if (!loaded) {
            cached = context.ytmAuthStore.data.first()[key]?.let(secure::decrypt)
            loaded = true
        }
        return cached
    }

    private fun cookieValue(cookies: String, name: String): String? =
        cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.ifBlank { null }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
