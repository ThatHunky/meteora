package dev.thathunky.meteora.share

import java.net.URI

/** What a shared YouTube / YouTube Music link points at. */
sealed interface ShareTarget {
    data class Video(val videoId: String, val playlistId: String?) : ShareTarget
    data class Playlist(val playlistId: String) : ShareTarget
    data class Album(val browseId: String) : ShareTarget
    data class Artist(val browseId: String) : ShareTarget
}

object ShareLink {
    private val URL = Regex("https?://\\S+")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Finds the first YouTube link in shared text ("Check this out: https://…") and parses it. */
    fun parse(text: String): ShareTarget? = URL.findAll(text).firstNotNullOfOrNull { parseUrl(it.value.trimEnd('.', ',', ')')) }

    fun parseUrl(url: String): ShareTarget? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val q = query(uri.rawQuery)
        return when (host) {
            "youtu.be" -> path.trim('/').takeIf { VIDEO_ID.matches(it) }?.let { ShareTarget.Video(it, q["list"]) }
            "music.youtube.com", "youtube.com" -> when {
                path == "/watch" -> q["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { ShareTarget.Video(it, q["list"]) }
                    ?: q["list"]?.let(ShareTarget::Playlist)
                path == "/playlist" -> q["list"]?.let(ShareTarget::Playlist)
                path.startsWith("/browse/MPREb_") -> ShareTarget.Album(path.removePrefix("/browse/"))
                path.startsWith("/channel/UC") -> ShareTarget.Artist(path.removePrefix("/channel/"))
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").takeIf { VIDEO_ID.matches(it) }?.let { ShareTarget.Video(it, null) }
                else -> null
            }
            else -> null
        }
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()
}
