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
