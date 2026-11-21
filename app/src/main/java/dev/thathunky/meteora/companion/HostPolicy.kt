package dev.thathunky.meteora.companion

/**
 * The companion server speaks plain HTTP, so cleartext is allowed app-wide (network security
 * config cannot express IP ranges) and this check keeps it to local / tailnet hosts only.
 */
object HostPolicy {

    fun isAllowed(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        if (h.endsWith(".local") || h.endsWith(".ts.net")) return true
        parseIpv4(h)?.let { return isPrivateV4(it) }
        if (':' in h) return isPrivateV6(h)
        // Bare single-label names (e.g. "desktop") resolve via local DNS / MagicDNS.
        return '.' !in h
    }

    private fun parseIpv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            octets[i] = v
        }
        return octets
    }

    private fun isPrivateV4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true // link-local
        o[0] == 127 -> true
        o[0] == 100 && o[1] in 64..127 -> true // CGNAT 100.64.0.0/10 (Tailscale)
        else -> false
    }

    private fun isPrivateV6(h: String): Boolean =
        h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
}
