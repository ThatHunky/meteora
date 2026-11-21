package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.LyricLine

/**
 * LRC text to timed lines: `[mm:ss.xx]text`, with `[mm:ss]` and `[mm:ss.xxx]` too, several time
 * tags on one line (a repeated chorus), metadata tags (`[ar:…]`, `[length:…]`) skipped and
 * word-level `<mm:ss.xx>` tags dropped. Lines come back in time order; a line with no text is an
 * instrumental gap and stays, with empty text.
 */
object LrcParser {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            var rest = raw.trim()
            val starts = ArrayList<Long>()
            while (true) {
                val m = TIME.matchAt(rest, 0) ?: break
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                starts += min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
                rest = rest.substring(m.range.last + 1)
            }
            if (starts.isEmpty()) continue // metadata or not an LRC line
            val words = rest.replace(WORD_TIME, "").trim()
            starts.forEach { out += LyricLine(it, words) }
        }
        return out.sortedBy { it.startMs } // stable: lines at the same time keep their order
    }
}
