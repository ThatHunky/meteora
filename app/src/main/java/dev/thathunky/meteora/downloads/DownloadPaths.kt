package dev.thathunky.meteora.downloads

/**
 * Where a download goes in shared storage: `Music/Meteora/<Artist>/<Album>/<NN> <Title>.m4a`.
 * Pure, so the rules are tested on the JVM.
 */
object DownloadPaths {
    const val ROOT = "Music/Meteora/"
    const val UNKNOWN_ARTIST = "Unknown artist"
    const val SINGLES = "Singles"
    private const val MAX = 80
    private val FORBIDDEN = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")

    /** One path part: no `/ \ : * ? " < > |` or control characters, no leading dot, trimmed, at most 80 characters. */
    fun part(name: String?, fallback: String): String {
        val cleaned = (name ?: "").replace(FORBIDDEN, " ").replace(Regex("\\s+"), " ").trim().trimStart('.').trim()
        // Cut on a code point boundary, then drop what a cut may leave at the end.
        val cut = if (cleaned.codePointCount(0, cleaned.length) > MAX) cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX)) else cleaned
        return cut.trimEnd(' ', '.').ifEmpty { fallback }
    }

    /** `RELATIVE_PATH` for MediaStore, ending in a slash. */
    fun relativePath(artist: String?, album: String?): String = "$ROOT${part(artist, UNKNOWN_ARTIST)}/${part(album, SINGLES)}/"

    /** `DISPLAY_NAME`: `03 Title.m4a`, or `Title.m4a` without a track number. */
    fun displayName(track: Int?, title: String?): String {
        val t = part(title, "Track")
        return if (track != null && track > 0) "${track.toString().padStart(2, '0')} $t.m4a" else "$t.m4a"
    }

    /** [wanted], or `Name (2).m4a`, `Name (3).m4a`… when a file of that name is already in the folder (case-insensitive, as on FAT). */
    fun unique(wanted: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in lower) return wanted
        val base = wanted.removeSuffix(".m4a")
        var n = 2
        while ("$base ($n).m4a".lowercase() in lower) n++
        return "$base ($n).m4a"
    }
}
