package dev.thathunky.meteora.share

import dev.thathunky.meteora.ytm.MusicItem
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

    /** The music.youtube.com link for [target]; [parseUrl] reads it back to the same target. */
    fun url(target: ShareTarget): String = when (target) {
        is ShareTarget.Video -> "$MUSIC/watch?v=${target.videoId}" + (target.playlistId?.let { "&list=$it" } ?: "")
        is ShareTarget.Playlist -> "$MUSIC/playlist?list=${target.playlistId.removePrefix("VL")}"
        is ShareTarget.Album -> "$MUSIC/browse/${target.browseId}"
        is ShareTarget.Artist -> "$MUSIC/channel/${target.browseId}"
    }

    private const val MUSIC = "https://music.youtube.com"

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()
}

/** What sharing [this] points at. An album shares as its playlist when the OLAK5uy_ id is known. */
fun MusicItem.shareTarget(): ShareTarget = when (this) {
    is MusicItem.Song -> ShareTarget.Video(videoId, null)
    is MusicItem.Playlist -> ShareTarget.Playlist(playlistId.removePrefix("VL"))
    is MusicItem.Album -> playlistId?.let(ShareTarget::Playlist) ?: ShareTarget.Album(browseId)
    is MusicItem.Artist -> ShareTarget.Artist(browseId)
}

fun shareUrl(item: MusicItem): String = ShareLink.url(item.shareTarget())

/** "title – artist" for the share sheet; just the title when there is no artist to name. */
fun shareTitle(title: String, artist: String?): String =
    artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$title – $it" } ?: title

fun MusicItem.shareTitle(): String = when (this) {
    is MusicItem.Song -> shareTitle(title, artists.joinToString(", ") { it.name })
    // An album's subtitle is "Album • Artist • 2001", or without the type label in search results.
    is MusicItem.Album -> shareTitle(title, subtitle.split("•").map { it.trim() }.firstOrNull { it.isNotEmpty() && it !in ALBUM_LABELS && !YEAR.matches(it) })
    is MusicItem.Playlist, is MusicItem.Artist -> title
}

private val YEAR = Regex("^\\d{4}$")
private val ALBUM_LABELS = setOf("Album", "Single", "EP", "Альбом", "Сингл")
