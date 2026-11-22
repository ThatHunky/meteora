package dev.thathunky.meteora.downloads

/**
 * Downloads are for YouTube Music Premium accounts only (docs/downloads-plan.md, decisions). A
 * positive check is remembered for 30 days; signed out, a failed check or a missing marker are
 * "not Premium". Files already downloaded are never touched by this.
 */
object DownloadsGate {
    const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000

    enum class Decision { Allowed, Check, SignedOut }

    /** Before queueing: [lastPremiumAtMs] is when a check last said Premium, null when never or when it said no. */
    fun decide(signedIn: Boolean, lastPremiumAtMs: Long?, nowMs: Long, forceCheck: Boolean = false): Decision = when {
        !signedIn -> Decision.SignedOut
        forceCheck -> Decision.Check
        lastPremiumAtMs != null && nowMs >= lastPremiumAtMs && nowMs - lastPremiumAtMs < REMEMBER_MS -> Decision.Allowed
        else -> Decision.Check
    }

    /**
     * What to remember after a check with [verdict]: its time when Premium; nothing when it said
     * no; [previous] unchanged when it could not tell (a network error is not a "no", and must
     * not undo a yes from last week).
     */
    fun remember(verdict: Boolean?, previous: Long?, nowMs: Long): Long? = when (verdict) {
        true -> nowMs
        false -> null
        null -> previous
    }
}
