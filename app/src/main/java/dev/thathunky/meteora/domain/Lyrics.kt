package dev.thathunky.meteora.domain

/** One line of a track's lyrics. [startMs] is null when the lyrics are not timed. */
data class LyricLine(val startMs: Long?, val text: String)

/** Where a track's lyrics came from; decides how the source line is written. */
enum class LyricsProvider { YouTube, LrcLib }

/**
 * A track's lyrics. [synced]: every line has a start time, in order. [source] is the sentence
 * YouTube sends ("Source: Musixmatch", already localised), shown as it is; null for LRCLIB, whose
 * line the UI writes itself.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String?,
    val provider: LyricsProvider = LyricsProvider.YouTube,
)

/**
 * Index of the line being sung at [positionMs]: the last line that started at or before it.
 * -1 before the first line, and always -1 for lines without times. [lines] are in time order.
 */
fun currentLine(lines: List<LyricLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val start = lines[mid].startMs ?: return -1
        if (start <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}
