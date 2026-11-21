package dev.thathunky.meteora.stream

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The fallback path: yt-dlp bundled in youtubedl-android. Slow (~15 s per track on the OP7T:
 * a Python process per call plus several client requests) but it tracks YouTube changes and
 * updates itself at most daily, so it keeps working when [InnerTubeStreams] breaks.
 * Calls are serialised: each one starts a Python process.
 */
class YtDlpStreams(private val context: Context) {

    private val initLock = Mutex()
    private val runLock = Mutex()
    @Volatile private var ready = false
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE) }

    /** Extracts Python on first run and updates yt-dlp when the last update is a day old. */
    suspend fun ensureReady() = initLock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            val t = SystemClock.elapsedRealtime()
            YoutubeDL.getInstance().init(context)
            Log.i(TAG, "init ${SystemClock.elapsedRealtime() - t} ms, yt-dlp ${YoutubeDL.getInstance().version(context)}")
            ready = true
            maybeUpdate()
        }
    }

    private fun maybeUpdate() {
        val last = prefs.getLong(KEY_UPDATED, 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        runCatching {
            val t = SystemClock.elapsedRealtime()
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            Log.i(TAG, "update $status in ${SystemClock.elapsedRealtime() - t} ms → ${YoutubeDL.getInstance().version(context)}")
            prefs.edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
        }.onFailure { Log.w(TAG, "yt-dlp update failed", it) }
    }

    fun version(): String? = if (ready) YoutubeDL.getInstance().version(context) else null

    suspend fun resolve(videoId: String): AudioStream = runLock.withLock {
        ensureReady()
        withContext(Dispatchers.IO) {
            val t = SystemClock.elapsedRealtime()
            val request = YoutubeDLRequest("https://music.youtube.com/watch?v=$videoId").apply {
                // Progressive audio only: no HLS/DASH manifests to deal with in ExoPlayer.
                addOption("-f", "bestaudio[ext=m4a][protocol^=http]/bestaudio[protocol^=http]/best[protocol^=http]")
                addOption("-O", "%(.{url,http_headers,format_id,abr})j")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--cache-dir", File(context.cacheDir, "yt-dlp").absolutePath)
            }
            val out = try {
                YoutubeDL.getInstance().execute(request).out
            } catch (e: Exception) {
                throw StreamException("yt-dlp failed for $videoId: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }}", e)
            }
            val line = out.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
                ?: throw StreamException("yt-dlp returned nothing for $videoId")
            val obj = json.parseToJsonElement(line).jsonObject
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: throw StreamException("no url for $videoId")
            val headers = (obj["http_headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            val took = SystemClock.elapsedRealtime() - t
            Log.i(TAG, "resolved $videoId in $took ms: format ${obj["format_id"]} abr ${obj["abr"]}")
            AudioStream(videoId, url, headers, staleAtFor(url), source = "yt-dlp")
        }
    }

    /** googlevideo URLs carry `expire=<unix s>`; refresh 10 min early (default 1 h). */
    private fun staleAtFor(url: String): Long {
        val expire = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong() }.getOrNull()
        val lifetimeMs = expire?.let { it * 1000 - System.currentTimeMillis() - 10 * 60_000 } ?: 60 * 60_000L
        return SystemClock.elapsedRealtime() + lifetimeMs.coerceAtLeast(60_000)
    }

    private companion object {
        const val TAG = "StreamResolver"
        const val KEY_UPDATED = "updatedAt"
        const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L
    }
}
