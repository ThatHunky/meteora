package dev.thathunky.meteora.downloads

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.withArtSize
import dev.thathunky.meteora.playback.LocalSong
import dev.thathunky.meteora.playback.parseDuration
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.YtmClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The listening log: what is played and skipped on this phone. It never leaves the phone. */
class ListenLog(private val store: ListenStore, private val scope: CoroutineScope) {
    @Volatile private var cache: Map<String, Listen>? = null

    @Synchronized fun all(): Map<String, Listen> = cache ?: store.listens().associateBy { it.videoId }.also { cache = it }

    /** The phone left [song] at [positionMs]; [byUser]: by Next (or a tap elsewhere), not by reaching the end. */
    fun left(song: LocalSong, positionMs: Long, byUser: Boolean) {
        val outcome = ListenRules.outcome(positionMs, (song.durationSec * 1000).toLong(), byUser)
        if (outcome == ListenOutcome.Nothing) return
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            synchronized(this@ListenLog) {
                val row = Listen(song.videoId, 0, 0, 0, song.title, song.artist, song.album, song.albumId, song.artworkUrl, song.durationSec)
                val next = ListenRules.apply(all()[song.videoId], row, outcome, now) ?: return@synchronized
                store.putListen(next)
                cache = all() + (next.videoId to next)
            }
        }
    }

    fun clear() = scope.launch(Dispatchers.IO) {
        synchronized(this@ListenLog) {
            store.clearListens()
            cache = emptyMap()
        }
    }
}

/**
 * Smart downloads: a set of songs kept downloaded by itself, refreshed nightly (charging,
 * unmetered, storage not low) and from "Refresh now". Manual downloads are outside its size.
 */
@Singleton
class SmartDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadManager,
    private val ytm: YtmClient,
    @AppScope private val scope: CoroutineScope,
    private val filter: dev.thathunky.meteora.filter.ContentFilter,
) {
    private val settings get() = downloads.settings

    fun setEnabled(on: Boolean) {
        settings.setSmartEnabled(on)
        val wm = WorkManager.getInstance(context)
        if (on) {
            val nightly = PeriodicWorkRequestBuilder<SmartWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(NIGHTLY, ExistingPeriodicWorkPolicy.KEEP, nightly)
            refreshNow()
        } else {
            wm.cancelUniqueWork(NIGHTLY)
        }
    }

    fun refreshNow() {
        val net = if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
        val once = OneTimeWorkRequestBuilder<SmartWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, once)
    }

    /** One refresh. False when it stopped early because YouTube lookups failed. */
    suspend fun refresh(): Boolean {
        if (!settings.smartEnabled.value) return true
        if (!downloads.allowed(forceCheck = true, notify = false)) {
            Log.i(TAG, "refresh skipped: not Premium")
            return true
        }
        val index = downloads.index
        index.ensureLoaded()
        val now = System.currentTimeMillis()
        var failures = 0
        val likes = lookup { ytm.likedSongs().tracks.map { it.smart() } } ?: emptyList<SmartSong>().also { failures++ }
        val home = lookup { ytm.home() } ?: emptyList<dev.thathunky.meteora.ytm.Shelf>().also { failures++ }
        if (failures == 2) {
            Log.w(TAG, "refresh stopped: liked songs and Home both failed")
            settings.setLastRefresh(-1, 0, 0, now)
            return false
        }
        val listenAgain = home.firstOrNull { SmartHome.isListenAgain(it.title) }?.items.orEmpty().filterIsInstance<MusicItem.Song>().map { it.smart() }
        val mixId = home.flatMap { it.items }.filterIsInstance<MusicItem.Playlist>().firstOrNull { SmartHome.isMix(it.title) }?.playlistId
        val mix = mixId?.let { id -> lookup { ytm.watchQueue(playlistId = id).songs.map { it.smart() } } }.orEmpty()
        val listens = downloads.listens.all()
        val entries = index.songs.value.values
        val manual = entries.filter { it.manual }.map { it.videoId }.toSet()
        val size = settings.smartSize.value
        // The content filter applies to every source when its Smart downloads switch is on.
        val place = dev.thathunky.meteora.filter.Place.SmartDownloads
        fun List<SmartSong>.shown() = filter { filter.allows(it.title, it.artist, place) }
        val wanted = SmartRules.wanted(
            SmartRules.Inputs(size, listens.filterValues { filter.allows(it.title, it.artist, place) }, likes.shown(), listenAgain.shown(), mix.shown(), manual, now),
        )
        // Smart songs that became manual leave the set (they stay downloaded as manual) and their slot is refilled.
        val current = entries.filter { it.smart && !it.manual }.sortedBy { it.addedAtMs }.map { it.videoId }
        var plan = SmartRules.plan(current, wanted, size, listens, now)
        // Stops adding when free space would fall under 500 MB.
        val perSong = if (settings.quality.value == dev.thathunky.meteora.stream.StreamQuality.High) 8L shl 20 else 4L shl 20
        val room = ((downloads.freeBytes() - DownloadManager.MIN_FREE_BYTES) / perSong).coerceAtLeast(0)
        if (plan.add.size > room) plan = plan.copy(add = plan.add.take(room.toInt()))
        downloads.apply(DownloadRules.removeSmart(entries, plan.remove.toSet()))
        // A kept song whose download failed gets another try.
        plan.keep.forEach { id -> index.update(id) { if (it.state == DownloadState.Failed) it.copy(state = DownloadState.Queued) else it } }
        entries.filter { it.smart && it.manual }.forEach { e -> index.update(e.videoId) { it.copy(smart = false) } }
        downloads.enqueueSmart(plan.add.map { it.entry() })
        settings.setLastRefresh(plan.keep.size, plan.add.size, plan.remove.size, now)
        Log.i(TAG, "refresh: kept ${plan.keep.size}, added ${plan.add.size}, removed ${plan.remove.size}, wanted ${wanted.songs.size}")
        return failures == 0
    }

    private suspend fun <T> lookup(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "lookup failed: ${e.javaClass.simpleName}")
        null
    }

    private fun MusicItem.Song.smart() = SmartSong(videoId, title, artists.joinToString(", ") { it.name }, album?.name, thumbnail?.withArtSize(1080), parseDuration(duration))

    private fun SmartSong.entry() = DownloadEntry(videoId, title, artist, album, artist.substringBefore(", "), null, null, artworkUrl, durationSec, smart = true)

    companion object {
        private const val TAG = "SmartDownloads"
        const val NIGHTLY = "smart-nightly"
        const val NOW = "smart-now"
    }
}

/** Which Home shelves feed smart downloads. Titles are translated, so both languages the app speaks are matched. Unverified signed in. */
object SmartHome {
    private val LISTEN_AGAIN = listOf("listen again", "слухати знову", "послухати знову", "слухайте знову")

    fun isListenAgain(title: String): Boolean = title.trim().lowercase().let { t -> LISTEN_AGAIN.any { t == it || t.startsWith(it) } }

    /** "My Supermix", "My Mix 1", "Мій супермікс"… */
    fun isMix(title: String): Boolean = title.lowercase().let { "mix" in it || "мікс" in it }
}

class SmartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun smart(): SmartDownloads
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).smart().refresh()
        return Result.success()
    }
}
