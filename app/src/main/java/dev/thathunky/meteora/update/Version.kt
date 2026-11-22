package dev.thathunky.meteora.update

/**
 * A semantic version (semver.org 2.0.0) with its pre-release part: `0.1.0-alpha.2`. Build
 * metadata after `+` is accepted and ignored for ordering, as semver says. Ordering follows the
 * semver rules: `0.1.0-alpha.2 < 0.1.0-alpha.10 < 0.1.0-beta.1 < 0.1.0`.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Version> {

    val isPreRelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A release is above any of its pre-releases.
        if (pre.isEmpty() || other.pre.isEmpty()) return compareValues(other.pre.size.coerceAtMost(1), pre.size.coerceAtMost(1))
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePart(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    /** `0.1.0-alpha.2`: the form `versionName` and the tag (after its `v`) use. */
    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    /** `0.1.0 alpha 2`: for people. */
    fun display(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else " " + pre.joinToString(" ")

    companion object {
        private val CORE = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
        private val IDENT = Regex("""[0-9A-Za-z-]+""")
        private val NUMERIC = Regex("""\d+""")

        /** `0.1.0-alpha.2`, `v0.1.0-alpha.2` (a tag) or null when it is not semver. */
        fun parse(text: String): Version? {
            val s = text.trim().removePrefix("v").substringBefore('+')
            val core = s.substringBefore('-')
            val m = CORE.matchEntire(core) ?: return null
            val pre = if ('-' in s) s.substringAfter('-').split('.') else emptyList()
            for (p in pre) {
                if (!IDENT.matches(p)) return null
                // Numeric identifiers have no leading zeros.
                if (NUMERIC.matches(p) && p.length > 1 && p[0] == '0') return null
            }
            val (a, b, c) = m.destructured
            return Version(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null, pre)
        }

        /** Numbers compare as numbers and below words; words compare in ASCII order. */
        private fun comparePart(a: String, b: String): Int {
            val na = if (NUMERIC.matches(a)) a.toBigInteger() else null
            val nb = if (NUMERIC.matches(b)) b.toBigInteger() else null
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
