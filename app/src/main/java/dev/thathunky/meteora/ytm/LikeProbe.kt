package dev.thathunky.meteora.ytm

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Debug tool: where does a signed-in `next` response say whether a track is liked? Logs counts
 * and key paths only, never titles or ids beyond the first few characters.
 */
object LikeProbe {
    private const val TAG = "LikeProbe"

    /**
     * What do the item menus on Home (or in a queue) offer? The official app has "Not interested"
     * and "Don't recommend artist"; this logs each menu entry's renderer, icon and endpoint kinds
     * with counts. No titles, ids or tokens.
     */
    suspend fun menus(ytm: YtmClient, queueOf: String?) {
        val root = runCatching { if (queueOf != null) ytm.nextRaw(queueOf, null) else ytm.browse("FEmusic_home") }
            .getOrElse { Log.i(TAG, "menus failed: $it"); return }
        val labels = HashMap<String, Int>()
        val kinds = HashMap<String, Int>()
        for (menu in root.findAll("menuRenderer")) {
            for (entry in (menu["items"] as? JsonArray).orEmpty()) {
                val (renderer, body) = (entry as? JsonObject)?.entries?.firstOrNull() ?: continue
                val o = body as? JsonObject ?: continue
                val icon = (o["icon"] as? JsonObject)?.str("iconType") ?: (o["defaultIcon"] as? JsonObject)?.str("iconType")
                val endpoints = listOf("serviceEndpoint", "navigationEndpoint", "defaultServiceEndpoint")
                    .mapNotNull { o[it] as? JsonObject }
                    .flatMap { e -> e.keys.filter { it.endsWith("Endpoint") } }
                kinds.merge("${renderer.removeSuffix("Renderer")} icon=$icon → $endpoints", 1, Int::plus)
                // YouTube's own label for the two feedback entries, and what else the endpoint carries.
                if (icon == "NOT_INTERESTED" || icon == "REMOVE_CIRCLE") {
                    val label = ((o["text"] as? JsonObject)?.get("runs") as? JsonArray)?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }
                    val fe = (o["serviceEndpoint"] as? JsonObject)?.get("feedbackEndpoint") as? JsonObject
                    labels.merge("$icon \"$label\" endpoint keys=${fe?.keys}", 1, Int::plus)
                }
            }
        }
        Log.i(TAG, "menus in ${if (queueOf != null) "a queue" else "Home"}: ${kinds.size} kinds")
        kinds.entries.sortedByDescending { it.value }.forEach { Log.i(TAG, "x${it.value}: ${it.key}") }
        labels.forEach { (k, n) -> Log.i(TAG, "label x$n: $k") }
        val tokens = root.findAll("feedbackEndpoint").count { it.str("feedbackToken") != null }
        Log.i(TAG, "feedbackEndpoint with a token: $tokens")
    }

    /**
     * How does a signed-in queue pair songs with their music videos? Logs, per wrapped item, the
     * kinds of the two versions, the raw shape of the segment map (key names and the first few
     * numbers) and what the parser made of it. Ids are cut to four characters.
     */
    suspend fun video(ytm: YtmClient, videoId: String) {
        val root = runCatching { ytm.nextRaw(videoId, null) }.getOrElse { Log.i(TAG, "video failed: $it"); return }
        val wrappers = root.findAll("playlistPanelVideoWrapperRenderer")
        Log.i(TAG, "video: ${root.queueItems().size} queue items, ${wrappers.size} wrapped with a counterpart")
        fun kind(r: JsonObject?) = r?.findAll("watchEndpointMusicConfig")?.firstOrNull()?.str("musicVideoType")?.removePrefix("MUSIC_VIDEO_TYPE_")
        for (w in wrappers.take(40)) {
            val primary = (w["primaryRenderer"] as? JsonObject)?.get("playlistPanelVideoRenderer") as? JsonObject
            val c = (w["counterpart"] as? JsonArray)?.firstOrNull() as? JsonObject
            val other = (c?.get("counterpartRenderer") as? JsonObject)?.get("playlistPanelVideoRenderer") as? JsonObject
            val map = c?.get("segmentMap") as? JsonObject
            val segs = map?.values?.firstOrNull { it is JsonArray } as? JsonArray
            Log.i(TAG, "pair \"${primary?.get("title").runsText().take(40)}\" ${primary?.str("videoId")} ${kind(primary)} len=${primary?.get("lengthText").runsText()} ↔ ${other?.str("videoId")} ${kind(other)} len=${other?.get("lengthText").runsText()}")
            Log.i(TAG, "  counterpart keys=${c?.keys} segmentMap keys=${map?.keys} segments=${segs?.size}")
            segs?.take(4)?.forEach { Log.i(TAG, "  segment $it") }
        }
        val parsed = YtmParser.watchQueue(root)
        for (s in parsed.songs.filter { it.videoCounterpartId != null }.take(40)) {
            val offsets = s.segments.map { it.videoStartMs - it.audioStartMs }
            Log.i(TAG, "parsed ${s.videoId.take(4)}… isVideo=${s.isVideo} segments=${s.segments.size} offsets(video-audio)=$offsets durations=${s.segments.map { it.durationMs }}")
        }
    }

    suspend fun run(ytm: YtmClient, videoId: String?, playlistId: String?) {
        val root = runCatching { ytm.nextRaw(videoId, playlistId ?: if (videoId == null) "LM" else null) }
            .getOrElse { Log.i(TAG, "failed: $it"); return }
        val items = root.queueItems()
        val statuses = HashMap<String, Int>()
        val paths = HashMap<String, Int>()
        var withStatus = 0
        for (item in items) {
            val found = ArrayList<Pair<String, String>>()
            collect(item, "", found)
            if (found.isNotEmpty()) withStatus++
            for ((path, status) in found) {
                statuses.merge(status, 1, Int::plus)
                paths.merge(path, 1, Int::plus)
            }
        }
        Log.i(TAG, "queue items: ${items.size}, with a like status: $withStatus, statuses: $statuses")
        paths.entries.sortedByDescending { it.value }.take(4).forEach { Log.i(TAG, "path x${it.value}: ${it.key}") }
        val overlay = root.findAll("playerOverlayRenderer").firstOrNull()?.findAll("likeButtonRenderer")?.firstOrNull()
        Log.i(TAG, "overlay: status=${overlay?.str("likeStatus")} for ${(overlay?.get("target") as? JsonObject)?.str("videoId")?.take(4)}…")
        // Menu entries of the first item, to see what else could tell (e.g. "Remove from liked songs").
        val menu = items.firstOrNull()?.findAll("menuRenderer")?.firstOrNull()
        val kinds = (menu?.get("items") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.keys?.firstOrNull() }
        val top = (menu?.get("topLevelButtons") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.keys?.firstOrNull() }
        Log.i(TAG, "first item menu: items=$kinds topLevelButtons=$top")
        // The toggles' icons per item: a liked track may show "remove from liked" as its default.
        val icons = HashMap<String, Int>()
        for (item in items) {
            val key = item.findAll("toggleMenuServiceItemRenderer").joinToString("+") { t ->
                "${(t["defaultIcon"] as? JsonObject)?.str("iconType")}>${(t["toggledIcon"] as? JsonObject)?.str("iconType")}"
            }
            icons.merge(key, 1, Int::plus)
        }
        Log.i(TAG, "toggle icons (default>toggled) per item: $icons")
    }

    private fun collect(e: JsonElement, path: String, out: MutableList<Pair<String, String>>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == "likeStatus" && v is JsonPrimitive) out += path to (v.contentOrNull ?: "?")
                else collect(v, if (path.isEmpty()) k else "$path/$k", out)
            }
            is JsonArray -> e.forEach { collect(it, "$path[]", out) }
            else -> Unit
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
