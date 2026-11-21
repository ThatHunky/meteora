package dev.thathunky.meteora.ytm

import dev.thathunky.meteora.domain.LikeStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Defensive InnerTube parsing. Layouts move around often (search results recently moved from one
 * musicShelfRenderer into per-item itemSectionRenderers), so instead of fixed paths this finds the
 * renderers we know *anywhere* in the tree, in document order, and reads only stable fields.
 * Anything unexpected is skipped, never thrown.
 */
object YtmParser {

    // ---- public entry points ----

    fun search(root: JsonElement): SearchResults {
        val card = root.findAll("musicCardShelfRenderer").firstOrNull()?.let(::cardItem)
        val items = root.findAll("musicResponsiveListItemRenderer").mapNotNull(::responsiveItem)
            .filterNot { card != null && it.sameAs(card) }
        return SearchResults(card, items)
    }

    fun suggestions(root: JsonElement): List<String> =
        root.findAll("searchSuggestionRenderer").mapNotNull { it["suggestion"].runsText().ifBlank { null } }

    /** Home, artist pages, explore: every carousel/list shelf with its title. */
    fun shelves(root: JsonElement): List<Shelf> = root.findAllOf(SHELF_KEYS).mapNotNull { (_, shelf) ->
        val header = shelf["header"]
        val title = (header["musicCarouselShelfBasicHeaderRenderer"] ?: header["musicShelfHeaderRenderer"])["title"].runsText()
            .ifBlank { shelf["title"].runsText() }
        val items = shelf["contents"].array().mapNotNull { c ->
            c["musicTwoRowItemRenderer"]?.let { twoRowItem(it as JsonObject) }
                ?: c["musicResponsiveListItemRenderer"]?.let { responsiveItem(it as JsonObject) }
        }
        val more = header["musicCarouselShelfBasicHeaderRenderer"]["moreContentButton"]
            ?.findAll("browseEndpoint")?.firstOrNull()?.str("browseId")
            ?: shelf["bottomEndpoint"]["browseEndpoint"].str("browseId")
        if (items.isEmpty()) null else Shelf(title, items, more)
    }

    /** Album or playlist page. */
    fun collection(root: JsonElement): CollectionPage {
        val header = root.findAllOf(HEADER_KEYS).firstOrNull()?.second
        val tracks = root.findAll("musicResponsiveListItemRenderer").mapNotNull(::responsiveItem).filterIsInstance<MusicItem.Song>()
        val playlistId = header?.findAll("watchPlaylistEndpoint")?.firstOrNull()?.str("playlistId")
            ?: header?.findAll("watchEndpoint")?.firstNotNullOfOrNull { it.str("playlistId") }
            ?: mostFrequentPlaylistId(root)
        val strap = header["straplineTextOne"].runsText()
        return CollectionPage(
            title = header["title"].runsText(),
            subtitle = strap.ifBlank { header["subtitle"].runsText() },
            secondSubtitle = (if (strap.isNotBlank()) header["subtitle"].runsText() else header["secondSubtitle"].runsText()).ifBlank { null },
            thumbnail = header?.let(::bestThumbnail),
            playlistId = playlistId,
            tracks = tracks,
        )
    }

    fun artist(root: JsonElement): ArtistPage {
        val header = root.findAllOf(HEADER_KEYS).firstOrNull()?.second
        val watchPlaylists = header?.findAll("watchPlaylistEndpoint").orEmpty().mapNotNull { it.str("playlistId") }
        return ArtistPage(
            name = header["title"].runsText(),
            thumbnail = header?.let(::bestThumbnail),
            description = header["description"].runsText().ifBlank { null },
            shelves = shelves(root),
            radioPlaylistId = watchPlaylists.firstOrNull { it.startsWith("RD") },
            shufflePlaylistId = watchPlaylists.firstOrNull { !it.startsWith("RD") },
        )
    }

    /** The `next` endpoint's watch queue (radio / album / playlist as the player would queue it). */
    fun watchQueue(root: JsonElement): WatchQueue {
        val songs = root.findAll("playlistPanelVideoRenderer").mapNotNull { r ->
            val videoId = r.str("videoId") ?: return@mapNotNull null
            val byline = r["longBylineText"]["runs"].array()
            MusicItem.Song(
                videoId = videoId,
                title = r["title"].runsText().ifBlank { return@mapNotNull null },
                artists = artistRefs(byline.takeWhile { it.str("text")?.trim() != "•" }.ifEmpty { byline }),
                album = byline.firstOrNull { it.pageType() == PT_ALBUM }?.let {
                    AlbumRef(it.str("text").orEmpty(), it["navigationEndpoint"]["browseEndpoint"].str("browseId"))
                },
                duration = r["lengthText"].runsText().ifBlank { null },
                thumbnail = bestThumbnail(r),
                isVideo = r.findAll("watchEndpointMusicConfig").firstOrNull()?.str("musicVideoType")
                    ?.let { it != "MUSIC_VIDEO_TYPE_ATV" } ?: false,
            )
        }.distinctBy { it.videoId }
        val selected = root.findAll("playlistPanelVideoRenderer").indexOfFirst { (it["selected"] as? JsonPrimitive)?.contentOrNull == "true" }
        val continuation = root.findAll("nextRadioContinuationData").firstOrNull()?.str("continuation")
            ?: root.findAll("nextContinuationData").firstOrNull()?.str("continuation")
        return WatchQueue(songs, selected.coerceAtLeast(0), continuation, queueLikes(root))
    }

    /**
     * Like status per queued track. Signed in, each item's menu has a liked-songs toggle whose
     * default icon is the action on offer: `UNFAVORITE` ("remove from liked songs") means the
     * track is liked. The player overlay gives the full status, dislike included, but only for
     * the current track. Seen on a signed-in response on 2026-11-21; signed out there is neither.
     */
    private fun queueLikes(root: JsonElement): Map<String, LikeStatus> {
        val likes = HashMap<String, LikeStatus>()
        for (r in root.findAll("playlistPanelVideoRenderer")) {
            val videoId = r.str("videoId") ?: continue
            val icon = r.findAll("toggleMenuServiceItemRenderer").firstNotNullOfOrNull { t ->
                t["defaultIcon"].str("iconType")?.takeIf { it == "FAVORITE" || it == "UNFAVORITE" }
            } ?: continue
            likes[videoId] = if (icon == "UNFAVORITE") LikeStatus.Like else LikeStatus.Indifferent
        }
        val overlay = root.findAll("playerOverlayRenderer").firstOrNull()?.findAll("likeButtonRenderer")?.firstOrNull()
        val current = overlay?.get("target").str("videoId")
        val status = when (overlay?.str("likeStatus")) {
            "LIKE" -> LikeStatus.Like
            "DISLIKE" -> LikeStatus.Dislike
            "INDIFFERENT" -> LikeStatus.Indifferent
            else -> null
        }
        if (current != null && status != null) likes[current] = status
        return likes
    }

    // ---- item renderers ----

    fun responsiveItem(r: JsonObject): MusicItem? {
        val cols = r["flexColumns"].array().map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val title = cols.getOrNull(0).runsText().ifBlank { return null }
        val subtitleRuns = cols.drop(1).flatMap { it["runs"].array() }
        val subtitle = subtitleRuns.joinToString("") { it.str("text").orEmpty() }
        val thumb = bestThumbnail(r)
        val browse = r["navigationEndpoint"]["browseEndpoint"]
        val videoId = r["playlistItemData"].str("videoId")
            ?: r["overlay"]?.findAll("watchEndpoint")?.firstOrNull()?.str("videoId")
            ?: cols.firstOrNull()?.findAll("watchEndpoint")?.firstOrNull()?.str("videoId")

        if (browse == null && videoId != null) {
            val watch = r.findAll("watchEndpoint").firstOrNull { it.str("videoId") == videoId }
            val videoType = watch?.findAll("watchEndpointMusicConfig")?.firstOrNull()?.str("musicVideoType")
            val duration = r["fixedColumns"].array().firstOrNull()
                ?.get("musicResponsiveListItemFixedColumnRenderer")["text"].runsText().ifBlank { null }
                ?: subtitleRuns.map { it.str("text").orEmpty().trim() }.lastOrNull { DURATION.matches(it) }
            return MusicItem.Song(
                videoId = videoId,
                title = title,
                artists = artistRefs(subtitleRuns),
                album = subtitleRuns.firstOrNull { it.pageType() == PT_ALBUM }?.let {
                    AlbumRef(it.str("text").orEmpty(), it["navigationEndpoint"]["browseEndpoint"].str("browseId"))
                },
                duration = duration,
                thumbnail = thumb,
                isVideo = videoType != null && videoType != "MUSIC_VIDEO_TYPE_ATV",
            )
        }
        return browse?.let { browseItem(it, title, cleanSubtitle(subtitle), thumb, r) }
    }

    fun twoRowItem(r: JsonObject): MusicItem? {
        val title = r["title"].runsText().ifBlank { return null }
        val subtitle = r["subtitle"].runsText()
        val thumb = bestThumbnail(r)
        val nav = r["navigationEndpoint"]
        nav["browseEndpoint"]?.let { return browseItem(it, title, subtitle, thumb, r) }
        nav["watchEndpoint"]?.let { w ->
            val videoId = w.str("videoId") ?: return null
            return MusicItem.Song(videoId, title, artistRefs(r["subtitle"]["runs"].array()), null, null, thumb, isVideo = true)
        }
        nav["watchPlaylistEndpoint"].str("playlistId")?.let { pid ->
            return MusicItem.Playlist("VL$pid", title, subtitle, thumb, pid)
        }
        return null
    }

    private fun cardItem(r: JsonObject): MusicItem? {
        val title = r["title"].runsText().ifBlank { return null }
        val subtitle = r["subtitle"].runsText()
        val thumb = bestThumbnail(r)
        val nav = r["title"]["runs"].array().firstOrNull()?.get("navigationEndpoint") ?: r["onTap"]
        nav["browseEndpoint"]?.let { return browseItem(it, cleanSubtitle(subtitle), thumb, r, title) }
        nav["watchEndpoint"].str("videoId")?.let {
            return MusicItem.Song(it, title, artistRefs(r["subtitle"]["runs"].array()), null, null, thumb, isVideo = false)
        }
        return null
    }

    private fun browseItem(browse: JsonElement, subtitle: String, thumb: String?, ctx: JsonObject, title: String): MusicItem? =
        browseItem(browse, title, subtitle, thumb, ctx)

    private fun browseItem(browse: JsonElement, title: String, subtitle: String, thumb: String?, ctx: JsonObject): MusicItem? {
        val id = browse.str("browseId") ?: return null
        return when (browse.pageTypeOfEndpoint()) {
            PT_ALBUM, PT_AUDIOBOOK -> MusicItem.Album(id, title, subtitle, thumb, ctx.findAll("watchPlaylistEndpoint").firstOrNull()?.str("playlistId"))
            PT_ARTIST, PT_LIBRARY_ARTIST, PT_USER_CHANNEL -> MusicItem.Artist(id, title, subtitle, thumb)
            PT_PLAYLIST, PT_PODCAST -> MusicItem.Playlist(id, title, subtitle, thumb, id.removePrefix("VL"))
            else -> if (id.startsWith("VL")) MusicItem.Playlist(id, title, subtitle, thumb, id.removePrefix("VL")) else null
        }
    }

    // ---- helpers ----

    private fun artistRefs(runs: List<JsonObject>): List<ArtistRef> {
        val linked = runs.filter { it.pageType() == PT_ARTIST || it.pageType() == PT_USER_CHANNEL }
            .map { ArtistRef(it.str("text").orEmpty(), it["navigationEndpoint"]["browseEndpoint"].str("browseId")) }
        if (linked.isNotEmpty()) return linked
        // Unlinked: first run that isn't a type label / separator / duration / count.
        return runs.map { it.str("text").orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() && it != "•" && it !in TYPE_LABELS && !DURATION.matches(it) }
            ?.let { listOf(ArtistRef(it, null)) }.orEmpty()
    }

    private fun cleanSubtitle(s: String) = s.split(" • ").filterNot { it in TYPE_LABELS }.joinToString(" • ")

    private fun MusicItem.sameAs(other: MusicItem): Boolean = when {
        this is MusicItem.Song && other is MusicItem.Song -> videoId == other.videoId
        this is MusicItem.Album && other is MusicItem.Album -> browseId == other.browseId
        this is MusicItem.Artist && other is MusicItem.Artist -> browseId == other.browseId
        this is MusicItem.Playlist && other is MusicItem.Playlist -> browseId == other.browseId
        else -> false
    }

    private fun mostFrequentPlaylistId(root: JsonElement): String? =
        root.findAll("watchEndpoint").mapNotNull { it.str("playlistId") }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun bestThumbnail(o: JsonElement): String? {
        val all = o.findArrays("thumbnails").flatMap { arr -> arr.mapNotNull { it as? JsonObject } }
        val best = all.maxByOrNull { (it.str("width")?.toIntOrNull() ?: 0) * (it.str("height")?.toIntOrNull() ?: 0) } ?: return null
        val url = best.str("url") ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun JsonElement.pageType(): String? = this["navigationEndpoint"]["browseEndpoint"].pageTypeOfEndpoint()

    private fun JsonElement?.pageTypeOfEndpoint(): String? =
        this["browseEndpointContextSupportedConfigs"]["browseEndpointContextMusicConfig"].str("pageType")

    private val SHELF_KEYS = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer")
    private val HEADER_KEYS = listOf(
        "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer",
        "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )
    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val TYPE_LABELS = setOf("Song", "Video", "Album", "Single", "EP", "Artist", "Playlist", "Episode", "Podcast", "Profile")

    private const val PT_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val PT_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"
    private const val PT_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val PT_LIBRARY_ARTIST = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    private const val PT_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val PT_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    private const val PT_PODCAST = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
}

// ---- tiny JSON tree helpers (null-tolerant) ----

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.let { it[key] }

internal fun JsonElement?.array(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonElement?.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Concatenated text of `{runs:[{text}]}` or `{simpleText}`. */
internal fun JsonElement?.runsText(): String {
    this.str("simpleText")?.let { return it }
    return this["runs"].array().joinToString("") { it.str("text").orEmpty() }
}

/** All objects stored under [key] anywhere below this element, depth-first, document order. */
internal fun JsonElement.findAll(key: String): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key && v is JsonObject) out += v
                walk(v)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}

/** Like [findAll] for several keys, preserving document order, not descending into matches. */
internal fun JsonElement.findAllOf(keys: Collection<String>): List<Pair<String, JsonObject>> {
    val out = ArrayList<Pair<String, JsonObject>>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k in keys && v is JsonObject) out += k to v else walk(v)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}

internal fun JsonElement.findArrays(key: String): List<JsonArray> {
    val out = ArrayList<JsonArray>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key && v is JsonArray) out += v
                walk(v)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}
