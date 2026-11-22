package dev.thathunky.meteora.lyrics

import dev.thathunky.meteora.domain.LyricLine

/**
 * LRC text to timed lines: `[mm:ss.xx]text`, with `[mm:ss]` and `[mm:ss.xxx]` too, several time
 * tags on one line (a repeated chorus, spaces between the tags allowed), metadata tags
 * (`[ar:…]`, `[length:…]`) skipped and word-level `<mm:ss.xx>` tags dropped. An `[offset:±ms]`
 * tag moves every line: a positive offset shows the lines sooner. Lines come back in time order;
 * a line with no text is an instrumental gap and stays, with empty text.
 */
object LrcParser {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        var offsetMs = 0L
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            var rest = raw.trim()
            OFFSET.find(rest)?.let { offsetMs = it.groupValues[1].toLongOrNull() ?: 0L }
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
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (starts.isEmpty()) continue // metadata or not an LRC line
            val words = rest.replace(WORD_TIME, "").trim()
            starts.forEach { out += LyricLine(it, words) }
        }
        val shifted = if (offsetMs == 0L) out else out.map { it.copy(startMs = (it.startMs!! - offsetMs).coerceAtLeast(0)) }
        return shifted.sortedBy { it.startMs } // stable: lines at the same time keep their order
    }
}
