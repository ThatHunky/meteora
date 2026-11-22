package dev.thathunky.meteora.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixture is the real list as fetched anonymously on 2026-11-22, with the user blocks cut. */
class ReleasesTest {

    private val fixture = requireNotNull(javaClass.getResource("/fixtures/update/releases.json")).readText()
    private fun v(s: String) = requireNotNull(Version.parse(s))

    private fun release(
        tag: String,
        draft: Boolean = false,
        pre: Boolean = true,
        assetName: String = "meteora-${tag.removePrefix("v")}.apk",
        digest: String? = "\"sha256:" + "ab".repeat(32) + "\"",
        url: String = "https://github.com/ThatHunky/meteora/releases/download/$tag/$assetName",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes $tag","html_url":"https://github.com/ThatHunky/meteora/releases/tag/$tag",
        "assets":[{"name":"$assetName","state":"uploaded","size":1000,"digest":${digest ?: "null"},"browser_download_url":"$url"}]}"""

    private fun list(vararg r: String) = Releases.parse("[" + r.joinToString(",") + "]")

    @Test fun `real response parses`() {
        val r = Releases.parse(fixture).single()
        assertEquals(v("0.1.0-alpha.1"), r.version)
        assertEquals("v0.1.0-alpha.1", r.tag)
        assertTrue(r.preRelease)
        assertEquals(124_131_768L, r.apkSize)
        assertEquals("0ccab464f150d7e49360625e47665351471111dd80c0833e0c71451bae939dbe", r.sha256)
        assertEquals("https://github.com/ThatHunky/meteora/releases/download/v0.1.0-alpha.1/meteora-0.1.0-alpha.1.apk", r.apkUrl)
        assertEquals("https://github.com/ThatHunky/meteora/releases/tag/v0.1.0-alpha.1", r.pageUrl)
        assertTrue(r.notes.startsWith("First alpha."))
    }

    @Test fun `installed alpha 1 has nothing newer in the real list`() {
        assertNull(Releases.pick(Releases.parse(fixture), v("0.1.0-alpha.1"), includePreReleases = true))
    }

    @Test fun `drafts, releases without an APK and junk tags are left out`() {
        val rs = list(
            release("v0.1.0-alpha.5", draft = true),
            release("v0.1.0-alpha.4", assetName = "meteora.zip"),
            release("nightly"),
            release("v0.1.0-alpha.3", url = "http://example.com/a.apk"),
            release("v0.1.0-alpha.2"),
        )
        assertEquals(listOf("v0.1.0-alpha.2"), rs.map { it.tag })
    }

    @Test fun `a missing or odd digest is null, not a failure`() {
        assertNull(list(release("v0.1.0-alpha.2", digest = null)).single().sha256)
        assertNull(list(release("v0.1.0-alpha.2", digest = "\"md5:abc\"")).single().sha256)
    }

    @Test fun `picks the highest newer one`() {
        val rs = list(release("v0.1.0-alpha.2"), release("v0.1.0-alpha.10"), release("v0.1.0-alpha.3"))
        assertEquals("v0.1.0-alpha.10", Releases.pick(rs, v("0.1.0-alpha.1"), true)?.tag)
    }

    @Test fun `older and equal versions are not updates`() {
        val rs = list(release("v0.1.0-alpha.1"), release("v0.1.0-alpha.2"))
        assertNull(Releases.pick(rs, v("0.1.0-alpha.2"), true))
        assertNull(Releases.pick(rs, v("0.1.0-alpha.3"), true))
        assertNull(Releases.pick(rs, v("0.1.0"), true))
    }

    @Test fun `pre-releases only with the switch on`() {
        val rs = list(release("v0.1.0-alpha.3"), release("v0.1.0", pre = false))
        assertEquals("v0.1.0", Releases.pick(rs, v("0.1.0-alpha.2"), includePreReleases = false)?.tag)
        val alphas = list(release("v0.1.0-alpha.3"))
        assertNull(Releases.pick(alphas, v("0.1.0-alpha.2"), includePreReleases = false))
        assertEquals("v0.1.0-alpha.3", Releases.pick(alphas, v("0.1.0-alpha.2"), includePreReleases = true)?.tag)
        // A pre-release tag counts as one even when the release is not marked so.
        val unmarked = list(release("v0.1.1-beta.1", pre = false))
        assertNull(Releases.pick(unmarked, v("0.1.0"), includePreReleases = false))
    }

    @Test fun `a skipped version is not offered, a later one is`() {
        val rs = list(release("v0.1.0-alpha.3"), release("v0.1.0-alpha.2"))
        assertNull(Releases.pick(rs, v("0.1.0-alpha.1"), true, skipped = v("0.1.0-alpha.3")))
        val later = list(release("v0.1.0-alpha.4"), release("v0.1.0-alpha.3"))
        assertEquals("v0.1.0-alpha.4", Releases.pick(later, v("0.1.0-alpha.1"), true, skipped = v("0.1.0-alpha.3"))?.tag)
    }

    @Test fun `not a list is empty`() {
        assertTrue(Releases.parse("""{"message":"API rate limit exceeded"}""").isEmpty())
        assertTrue(Releases.parse("<html>").isEmpty())
    }

    @Test fun `the check request carries nothing about the user`() {
        val r = Releases.request("0.1.0-alpha.2")
        assertEquals("https://api.github.com/repos/ThatHunky/meteora/releases?per_page=10", r.url.toString())
        assertEquals("GET", r.method)
        assertEquals(setOf("Accept", "X-GitHub-Api-Version", "User-Agent"), r.headers.names())
        assertEquals("Meteora/0.1.0-alpha.2", r.header("User-Agent"))
    }

    @Test fun `once a day`() {
        val day = UpdateStore.DAY_MS
        assertTrue(UpdateStore.isDue(null, 1_000))
        assertFalse(UpdateStore.isDue(10_000, 10_000 + day - 1))
        assertTrue(UpdateStore.isDue(10_000, 10_000 + day))
        // The clock went back: check rather than wait.
        assertTrue(UpdateStore.isDue(10_000 + day, 10_000))
    }
}
