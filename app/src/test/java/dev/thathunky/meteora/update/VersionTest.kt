package dev.thathunky.meteora.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    private fun v(s: String) = requireNotNull(Version.parse(s)) { s }

    @Test fun `parses tags and version names`() {
        assertEquals(Version(0, 1, 0, listOf("alpha", "2")), v("v0.1.0-alpha.2"))
        assertEquals(Version(0, 1, 0, listOf("alpha", "2")), v("0.1.0-alpha.2"))
        assertEquals(Version(1, 20, 3), v("1.20.3"))
        assertEquals(Version(1, 0, 0, listOf("rc", "1")), v("1.0.0-rc.1+build.5"))
    }

    @Test fun `round trips and displays`() {
        assertEquals("0.1.0-alpha.2", v("v0.1.0-alpha.2").toString())
        assertEquals("0.1.0 alpha 2", v("v0.1.0-alpha.2").display())
        assertEquals("0.2.0", v("0.2.0").display())
    }

    @Test fun `semver order`() {
        val sorted = listOf(
            "0.1.0-alpha", "0.1.0-alpha.1", "0.1.0-alpha.2", "0.1.0-alpha.10", "0.1.0-alpha.beta",
            "0.1.0-beta.1", "0.1.0-rc.1", "0.1.0", "0.1.1-alpha.1", "0.1.1", "0.2.0", "1.0.0",
        ).map(::v)
        assertEquals(sorted, sorted.shuffled(java.util.Random(7)).sorted())
        for (i in 0 until sorted.size - 1) assertTrue("${sorted[i]} < ${sorted[i + 1]}", sorted[i] < sorted[i + 1])
    }

    @Test fun `build metadata does not count`() {
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
    }

    @Test fun `junk does not parse`() {
        listOf("", "v", "latest", "1.0", "1.0.0.0", "01.0.0", "1.0.0-", "1.0.0-alpha..1", "1.0.0-01", "1.0.0-al_pha", "nightly-2026-11-22")
            .forEach { assertNull(it, Version.parse(it)) }
    }

    @Test fun `pre-release flag`() {
        assertTrue(v("0.1.0-alpha.1").isPreRelease)
        assertTrue(!v("0.1.0").isPreRelease)
    }
}
