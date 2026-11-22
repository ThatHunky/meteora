package dev.thathunky.meteora.ytm

import android.util.Log
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the signed-in responses say about YouTube Music Premium. Only names and booleans: no
 * account name, email or handle is kept or logged.
 *
 * - [loggedIn], [subscriber]: `LOGGED_IN` and `IS_SUBSCRIBER` in the page's `ytcfg`, fetched
 *   with the session cookies. Null when the page did not say.
 * - [upgradeOffered]: the account menu (`account/account_menu`) has the "Get Music Premium"
 *   entry, found by `browseId: SPunlimited`, `/music_premium` or the `UNLIMITED` icon, never by its
 *   title (the title is translated). Null when the menu could not be read.
 *
 * Unverified for a Premium account (docs/api-notes.md, "Premium"): signed out the page says
 * `IS_SUBSCRIBER:false` and the menu offers the upgrade.
 */
data class PremiumMarkers(val loggedIn: Boolean?, val subscriber: Boolean?, val upgradeOffered: Boolean?) {
    /** Fail closed: the page must say subscriber, and the menu must have been read and offer no upgrade. */
    val isPremium: Boolean get() = loggedIn == true && subscriber == true && upgradeOffered == false

    /**
     * True: Premium. False: a marker said no. Null: the check could not tell (a network error, a
     * menu that did not load); that is not Premium either, but it must not erase a remembered yes.
     */
    val verdict: Boolean? get() = when {
        isPremium -> true
        loggedIn == false || subscriber == false || upgradeOffered == true -> false
        else -> null
    }

    override fun toString() = "loggedIn=$loggedIn subscriber=$subscriber upgradeOffered=$upgradeOffered → premium=$isPremium"
}

object PremiumParser {
    private val LOGGED_IN = Regex("\"LOGGED_IN\"\\s*:\\s*(true|false)")
    private val SUBSCRIBER = Regex("\"IS_SUBSCRIBER\"\\s*:\\s*(true|false)")
    private val VERSION = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"")

    fun loggedIn(page: String): Boolean? = LOGGED_IN.find(page)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    fun subscriber(page: String): Boolean? = SUBSCRIBER.find(page)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    fun clientVersion(page: String): String? = VERSION.find(page)?.groupValues?.get(1)

    /** True when the menu links to the Premium sign-up page. Null when [menu] is not an account menu at all. */
    fun upgradeOffered(menu: JsonElement): Boolean? {
        if (menu.findAll("multiPageMenuRenderer").isEmpty()) return null
        return menu.findAll("compactLinkRenderer").any(::isUpgrade) || menu.findAll("browseEndpoint").any(::isUpgradeEndpoint)
    }

    private fun isUpgrade(link: JsonObject): Boolean =
        ((link["icon"] as? JsonObject)?.str("iconType") == "UNLIMITED") ||
            link.findAll("browseEndpoint").any(::isUpgradeEndpoint)

    private fun isUpgradeEndpoint(e: JsonObject): Boolean =
        e.str("browseId") == "SPunlimited" || e.str("canonicalBaseUrl")?.startsWith("/music_premium") == true

    /**
     * For the probe: the menu's icon types and browse ids. Only page ids (`SP…`, `FE…`) are shown;
     * any other id (the account's own channel is a `UC…` id) is logged as `other`, as it names the account.
     */
    fun menuShape(menu: JsonElement): String {
        val icons = menu.findAll("icon").mapNotNull { it.str("iconType") }
        val ids = menu.findAll("browseEndpoint").mapNotNull { it.str("browseId") }
            .map { if (it.startsWith("SP") || it.startsWith("FE")) it else "other" }
        return "icons=$icons browseIds=$ids header=${menu.findAll("activeAccountHeaderRenderer").isNotEmpty()}"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.findAll(key: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == key && v is JsonObject) out += v
                    walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return out
    }
}

/**
 * Asks YouTube Music whether the signed-in account has Premium (downloads are Premium only, as in
 * the official app). Its own OkHttp client with redirects off: the Cookie header is set by hand
 * and a redirect would carry it wherever it points.
 */
@Singleton
class PremiumCheck @Inject constructor(okHttp: OkHttpClient, private val auth: YtmAuthProvider) {
    private val http = okHttp.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Signed out, or any failure: all markers null, which reads as not Premium. */
    suspend fun markers(): PremiumMarkers = withContext(Dispatchers.IO) {
        val headers = auth.headers() ?: return@withContext PremiumMarkers(null, null, null)
        try {
            val page = get(YtmClient.ORIGIN + "/", headers)
            val version = PremiumParser.clientVersion(page) ?: YtmClient.FALLBACK_CLIENT_VERSION
            val menu = runCatching { menu(version, headers) }.getOrNull()
            PremiumMarkers(PremiumParser.loggedIn(page), PremiumParser.subscriber(page), menu?.let(PremiumParser::upgradeOffered))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "premium check failed: ${e.javaClass.simpleName}")
            PremiumMarkers(null, null, null)
        }
    }

    suspend fun isPremium(): Boolean = markers().isPremium

    /** [PremiumMarkers.verdict]: null when the check could not tell. */
    suspend fun verdict(): Boolean? = markers().verdict

    /** Debug probe: the markers, and the menu's shape (icon types and browse ids only). */
    suspend fun probe() = withContext(Dispatchers.IO) {
        val m = markers()
        Log.i(TAG, "premium markers: $m")
        val headers = auth.headers() ?: return@withContext Log.i(TAG, "signed out")
        runCatching {
            val page = get(YtmClient.ORIGIN + "/", headers)
            val menu = menu(PremiumParser.clientVersion(page) ?: YtmClient.FALLBACK_CLIENT_VERSION, headers)
            Log.i(TAG, "account menu: ${PremiumParser.menuShape(menu)}")
        }.onFailure { Log.i(TAG, "account menu failed: ${it.javaClass.simpleName}") }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).header("User-Agent", YtmClient.USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw YtmException(it.code, "page HTTP ${it.code}")
            it.body.string()
        }
    }

    private fun menu(version: String, headers: Map<String, String>): JsonElement {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", version)
                    put("hl", "en")
                }
            }
        }
        val req = Request.Builder().url("${YtmClient.ORIGIN}/youtubei/v1/account/account_menu?prettyPrint=false")
            .header("User-Agent", YtmClient.USER_AGENT)
            .header("Origin", YtmClient.ORIGIN)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", version)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw YtmException(it.code, "account menu HTTP ${it.code}")
            json.parseToJsonElement(it.body.string())
        }
    }

    private companion object {
        const val TAG = "PremiumCheck"
    }
}
