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
            }
        }
        Log.i(TAG, "menus in ${if (queueOf != null) "a queue" else "Home"}: ${kinds.size} kinds")
        kinds.entries.sortedByDescending { it.value }.forEach { Log.i(TAG, "x${it.value}: ${it.key}") }
        val tokens = root.findAll("feedbackEndpoint").count { it.str("feedbackToken") != null }
        Log.i(TAG, "feedbackEndpoint with a token: $tokens")
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
