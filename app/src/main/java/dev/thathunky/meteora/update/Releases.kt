package dev.thathunky.meteora.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/** One GitHub release that carries an APK, as far as the updater cares. */
data class Release(
    val version: Version,
    val tag: String,
    val preRelease: Boolean,
    val notes: String,
    /** The release's web page, for the "install by hand" message. */
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from the asset's `digest` (`sha256:…`), when GitHub gives one. */
    val sha256: String?,
)

/**
 * The list from `GET /repos/ThatHunky/meteora/releases` (shape checked on 2026-11-22 with an
 * anonymous request: `tag_name`, `draft`, `prerelease`, `body`, `html_url`, and per asset `name`,
 * `size`, `browser_download_url` and `digest` as `sha256:<hex>`).
 */
object Releases {
    const val OWNER_REPO = "ThatHunky/meteora"
    const val URL = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=10"
    private const val PAGE_PREFIX = "https://github.com/$OWNER_REPO/"

    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("[0-9a-f]{64}")

    /**
     * Releases that are not drafts, whose tag is semver and which have an `.apk` asset with an
     * HTTPS download link and a size. Anything else in the list is left out, not an error.
     */
    fun parse(body: String): List<Release> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::release) }
    }

    private fun release(o: JsonObject): Release? {
        if (o.bool("draft") != false) return null
        val tag = o.str("tag_name") ?: return null
        val version = Version.parse(tag) ?: return null
        val apk = (o["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { a ->
                a.str("name")?.endsWith(".apk", ignoreCase = true) == true &&
                    a.str("browser_download_url")?.startsWith("https://") == true &&
                    (a.long("size") ?: 0) > 0 &&
                    (a.str("state") ?: "uploaded") == "uploaded"
            } ?: return null
        val digest = apk.str("digest")?.lowercase()
        val sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.takeIf { HEX64.matches(it) }
        val page = o.str("html_url")?.takeIf { it.startsWith(PAGE_PREFIX) } ?: "${PAGE_PREFIX}releases/tag/$tag"
        return Release(
            version = version,
            tag = tag,
            preRelease = o.bool("prerelease") ?: version.isPreRelease,
            notes = o.str("body").orEmpty(),
            pageUrl = page,
            apkUrl = apk.str("browser_download_url")!!,
            apkSize = apk.long("size")!!,
            sha256 = sha256,
        )
    }

    /**
     * The update to offer: the highest release, pre-releases only when [includePreReleases], and
     * only when it is newer than [installed]. When that highest one is [skipped], nothing is
     * offered (an older release between the two is not offered instead).
     */
    fun pick(releases: List<Release>, installed: Version, includePreReleases: Boolean, skipped: Version? = null): Release? {
        val best = releases
            .filter { includePreReleases || (!it.preRelease && !it.version.isPreRelease) }
            .maxByOrNull { it.version } ?: return null
        if (best.version <= installed) return null
        if (skipped != null && best.version.compareTo(skipped) == 0) return null
        return best
    }

    /**
     * The check: anonymous, JSON, with a plain User-Agent. No cookies, no token, nothing about
     * the user; the client it is sent with has no cookie jar and TLS only.
     */
    fun request(versionName: String): Request = Request.Builder()
        .url(URL)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", userAgent(versionName))
        .build()

    fun userAgent(versionName: String) = "Meteora/$versionName"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
