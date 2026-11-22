package dev.thathunky.meteora.update

/** What the release promised and what is installed now. Certificates are hex strings of their bytes. */
data class Expected(
    val size: Long,
    val sha256: String?,
    val packageName: String,
    val installedVersionCode: Long,
    val installedSigners: Set<String>,
)

/**
 * What the downloaded file is. [packageName], [versionCode] and [signers] are null when Android
 * could not read the file as a package (or read no signatures from it).
 */
data class ApkFacts(
    val size: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    val signers: Set<String>?,
)

/** Why a downloaded file is not offered for install. */
enum class Refusal { Size, Digest, NotAnApk, WrongPackage, NotNewer, DifferentSigner }

/**
 * The decision before offering to install, in order: size, digest when the release has one,
 * readable as a package, our package name, a higher versionCode, and exactly the installed
 * app's signing certificates. Fails closed: no signatures read on either side is a refusal.
 * Returns null when every check passes.
 */
fun checkApk(expected: Expected, actual: ApkFacts): Refusal? {
    if (actual.size != expected.size) return Refusal.Size
    if (expected.sha256 != null && !expected.sha256.equals(actual.sha256, ignoreCase = true)) return Refusal.Digest
    if (actual.packageName == null || actual.versionCode == null) return Refusal.NotAnApk
    if (actual.packageName != expected.packageName) return Refusal.WrongPackage
    if (actual.versionCode <= expected.installedVersionCode) return Refusal.NotNewer
    val signers = actual.signers?.map { it.lowercase() }?.toSet()
    val installed = expected.installedSigners.map { it.lowercase() }.toSet()
    if (signers.isNullOrEmpty() || installed.isEmpty() || signers != installed) return Refusal.DifferentSigner
    return null
}

/** Lower-case hex of [bytes]. */
fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
