package dev.thathunky.meteora.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkCheckTest {
    private val cert = "30820a0b" + "cd".repeat(20)
    private val other = "30820a0b" + "ef".repeat(20)
    private val digest = "0ccab464f150d7e49360625e47665351471111dd80c0833e0c71451bae939dbe"

    private val expected = Expected(size = 1000, sha256 = digest, packageName = "dev.thathunky.meteora", installedVersionCode = 2, installedSigners = setOf(cert))
    private val good = ApkFacts(size = 1000, sha256 = digest, packageName = "dev.thathunky.meteora", versionCode = 3, signers = setOf(cert))

    @Test fun `a matching file passes`() {
        assertNull(checkApk(expected, good))
        // Digest case and certificate case do not matter.
        assertNull(checkApk(expected, good.copy(sha256 = digest.uppercase(), signers = setOf(cert.uppercase()))))
    }

    @Test fun `size first`() {
        assertEquals(Refusal.Size, checkApk(expected, good.copy(size = 999)))
        assertEquals(Refusal.Size, checkApk(expected, good.copy(size = 999, sha256 = "00")))
    }

    @Test fun `digest when the release has one`() {
        assertEquals(Refusal.Digest, checkApk(expected, good.copy(sha256 = "00".repeat(32))))
        assertNull(checkApk(expected.copy(sha256 = null), good.copy(sha256 = "00".repeat(32))))
    }

    @Test fun `not a package`() {
        assertEquals(Refusal.NotAnApk, checkApk(expected, good.copy(packageName = null, versionCode = null, signers = null)))
    }

    @Test fun `another app`() {
        assertEquals(Refusal.WrongPackage, checkApk(expected, good.copy(packageName = "com.example.evil")))
    }

    @Test fun `same or lower versionCode`() {
        assertEquals(Refusal.NotNewer, checkApk(expected, good.copy(versionCode = 2)))
        assertEquals(Refusal.NotNewer, checkApk(expected, good.copy(versionCode = 1)))
    }

    @Test fun `different or missing signatures fail closed`() {
        assertEquals(Refusal.DifferentSigner, checkApk(expected, good.copy(signers = setOf(other))))
        assertEquals(Refusal.DifferentSigner, checkApk(expected, good.copy(signers = setOf(cert, other))))
        assertEquals(Refusal.DifferentSigner, checkApk(expected, good.copy(signers = null)))
        assertEquals(Refusal.DifferentSigner, checkApk(expected, good.copy(signers = emptySet())))
        assertEquals(Refusal.DifferentSigner, checkApk(expected.copy(installedSigners = emptySet()), good))
    }

    @Test fun `hex of bytes`() {
        assertEquals("00ff10", hex(byteArrayOf(0, -1, 16)))
    }
}
