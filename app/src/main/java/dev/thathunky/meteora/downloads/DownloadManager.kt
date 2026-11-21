package dev.thathunky.meteora.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.R
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.withArtSize
import dev.thathunky.meteora.playback.LocalSong
import dev.thathunky.meteora.playback.parseDuration
import dev.thathunky.meteora.stream.StreamResolver
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.PremiumCheck
import dev.thathunky.meteora.ytm.YtmAuthProvider
import dev.thathunky.meteora.ytm.YtmClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads for the app: queueing (behind the Premium gate), removing, and the WorkManager run
 * that fetches the queue two files at a time with a progress notification.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
    resolver: StreamResolver,
    private val ytm: YtmClient,
    private val auth: YtmAuthProvider,
    private val premium: PremiumCheck,
    val settings: DownloadSettings,
    @AppScope private val scope: CoroutineScope,
) {
    val index = DownloadIndex(SqliteDownloadStore(context))
    private val http = okHttp
    private val downloader = Downloader(context, okHttp, resolver)
    private val gateLock = Mutex()

    /** Emits when a download was asked for without Premium; the UI shows why. */
    private val _blocked = MutableSharedFlow<Blocked>(extraBufferCapacity = 4)
    val blocked: SharedFlow<Blocked> = _blocked

    enum class Blocked { SignedOut, NotPremium }

    init {
        scope.launch(Dispatchers.IO) {
            index.ensureLoaded()
            if (index.queued().isNotEmpty()) schedule()
        }
    }

    // ---- the gate ----

    /** True when downloading is allowed now; otherwise tells the UI and returns false. */
    suspend fun allowed(forceCheck: Boolean = false): Boolean = gateLock.withLock {
        val signedIn = runCatching { auth.headers() }.getOrNull() != null
        val now = System.currentTimeMillis()
        when (DownloadsGate.decide(signedIn, settings.premiumAtMs, now, forceCheck)) {
            DownloadsGate.Decision.Allowed -> true
            DownloadsGate.Decision.SignedOut -> false.also { _blocked.tryEmit(Blocked.SignedOut) }
            DownloadsGate.Decision.Check -> {
                val ok = premium.isPremium()
                settings.premiumAtMs = DownloadsGate.remember(ok, now)
                if (!ok) _blocked.tryEmit(Blocked.NotPremium)
                ok
            }
        }
    }

    // ---- asking ----

    fun downloadSong(song: MusicItem.Song) = scope.launch {
        if (!allowed()) return@launch
        enqueue(listOf(entryFor(song, single = true)), fromAlbum = false)
    }

    /** From the player: the song playing now. */
    fun downloadSong(song: LocalSong) = scope.launch {
        if (!allowed()) return@launch
        enqueue(listOf(entryFor(song)), fromAlbum = false)
    }

    /** An album or playlist from a list (long-press): its page is read first. */
    fun downloadItem(item: MusicItem) = scope.launch {
        when (item) {
            is MusicItem.Song -> downloadSong(item)
            is MusicItem.Album -> {
                if (!allowed()) return@launch
                val page = runCatching { ytm.album(item.browseId) }.getOrNull() ?: return@launch
                addCollection(page, page.playlistId ?: item.playlistId ?: item.browseId, item.browseId, isAlbum = true)
            }
            is MusicItem.Playlist -> {
                if (!allowed()) return@launch
                val page = runCatching { ytm.playlist(item.playlistId) }.getOrNull() ?: return@launch
                addCollection(page, item.playlistId, item.browseId, isAlbum = false)
            }
            is MusicItem.Artist -> Unit
        }
    }

    /** An album or playlist whose page is open. [id]: its playlist id; [browseId] opens the page again. */
    fun downloadCollection(page: CollectionPage, id: String, browseId: String, isAlbum: Boolean) = scope.launch {
        if (!allowed()) return@launch
        addCollection(page, id, browseId, isAlbum)
    }

    private fun addCollection(page: CollectionPage, id: String, browseId: String, isAlbum: Boolean) {
        val tracks = page.tracks.filter { it.videoId.isNotEmpty() }
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        index.putCollection(DownloadCollection(id, page.title, isAlbum, page.thumbnail, browseId, tracks.map { it.videoId }, now))
        val year = if (isAlbum) DownloadRules.year(listOfNotNull(page.subtitle, page.secondSubtitle).joinToString(" ")) else null
        val albumArtist = if (isAlbum) tracks.firstNotNullOfOrNull { it.artists.firstOrNull()?.name } else null
        val entries = tracks.mapIndexed { i, s ->
            val base = entryFor(s, single = false).copy(collections = setOf(id), trackNumber = i + 1)
            if (isAlbum) base.copy(album = page.title, albumArtist = albumArtist ?: base.albumArtist, year = year, artworkUrl = base.artworkUrl ?: page.thumbnail?.withArtSize(1080))
            else base
        }
        enqueue(entries, fromAlbum = isAlbum)
    }

    /** Smart downloads (D5) queue through here; already-manual songs only gain the flag. */
    internal fun enqueueSmart(songs: List<DownloadEntry>) = enqueue(songs.map { it.copy(smart = true, single = false, collections = emptySet()) }, fromAlbum = false)

    private fun enqueue(entries: List<DownloadEntry>, fromAlbum: Boolean) {
        val now = System.currentTimeMillis()
        entries.forEachIndexed { i, e ->
            val merged = DownloadRules.merge(index.get(e.videoId), e.copy(addedAtMs = now + i), fromAlbum)
            index.put(merged)
        }
        schedule()
    }

    fun retry(videoId: String) = scope.launch {
        if (!allowed()) return@launch
        index.update(videoId) { it.copy(state = DownloadState.Queued) }
        schedule()
    }

    // ---- removing ----

    fun removeSong(videoId: String) = scope.launch(Dispatchers.IO) {
        val e = index.get(videoId) ?: return@launch
        apply(DownloadRules.removeSingle(e))
    }

    fun removeCollection(id: String) = scope.launch(Dispatchers.IO) {
        apply(DownloadRules.removeCollection(index.songs.value.values, id))
        index.deleteCollection(id)
    }

    /** Settings → "Remove all downloads". */
    fun removeAll() = scope.launch(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        index.songs.value.values.forEach { e ->
            e.uri?.let(downloader::delete)
            index.delete(e.videoId)
        }
        index.collections.value.keys.forEach(index::deleteCollection)
    }

    internal fun apply(r: DownloadRules.Release) {
        r.keep.forEach(index::put)
        r.delete.forEach { e ->
            e.uri?.let(downloader::delete)
            index.delete(e.videoId)
        }
    }

    /** The player found the file gone: forget it, so the track streams. */
    fun forgetMissing(videoId: String) {
        index.update(videoId) { it.copy(state = DownloadState.Failed, uri = null) }
    }

    fun fileExists(uri: String): Boolean = downloader.exists(uri)

    // ---- space ----

    fun usedBytes(): Long = index.songs.value.values.filter { it.state == DownloadState.Done }.sumOf { it.bytes }

    @Suppress("DEPRECATION")
    fun freeBytes(): Long = runCatching { StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    // ---- running ----

    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        // Appended, so songs queued while a run is ending are picked up by the next one.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** The worker's body. False: stopped by a network failure, to be retried when the network is back. */
    internal suspend fun runQueue(foreground: suspend (ForegroundInfo) -> Unit): Boolean {
        index.ensureLoaded()
        val covers = ConcurrentHashMap<String, ByteArray>()
        var done = 0
        while (true) {
            val batch = index.queued().take(PARALLEL)
            if (batch.isEmpty()) return true
            if (freeBytes() in 1 until MIN_FREE_BYTES) {
                Log.w(TAG, "stopping: less than ${MIN_FREE_BYTES shr 20} MB free")
                return true
            }
            val total = done + index.queued().size
            runCatching { foreground(foregroundInfo(done + 1, total, batch.first().title)) }
            val ok = coroutineScope {
                batch.map { e -> async { one(e, covers) } }.awaitAll()
            }
            done += batch.size
            if (ok.any { !it }) return false
        }
    }

    /** False on a network failure (the song goes back to the queue). */
    private suspend fun one(e: DownloadEntry, covers: MutableMap<String, ByteArray>): Boolean {
        index.update(e.videoId) { it.copy(state = DownloadState.Downloading) }
        index.setProgress(e.videoId, 0f)
        return try {
            val cover = e.artworkUrl?.let { url ->
                val key = "${e.albumArtist ?: e.artist}/${e.album ?: url}"
                covers[key] ?: fetchCover(url)?.also { covers[key] = it }
            }
            val w = downloader.download(e, settings.quality.value, cover) { index.setProgress(e.videoId, it) }
            // Removed while it was downloading: the file goes too.
            if (index.get(e.videoId)?.wanted != true) {
                downloader.delete(w.uri.toString())
            } else {
                index.update(e.videoId) { it.copy(state = DownloadState.Done, uri = w.uri.toString(), itag = w.itag, bytes = w.bytes) }
            }
            true
        } catch (x: CancellationException) {
            index.update(e.videoId) { it.copy(state = DownloadState.Queued) }
            throw x
        } catch (x: IOException) {
            Log.w(TAG, "network failure on ${e.videoId}: ${x.javaClass.simpleName}")
            index.update(e.videoId) { it.copy(state = DownloadState.Queued) }
            false
        } catch (x: Exception) {
            Log.w(TAG, "download failed for ${e.videoId}: ${x.message}")
            index.update(e.videoId) { it.copy(state = DownloadState.Failed) }
            true
        } finally {
            index.setProgress(e.videoId, null)
        }
    }

    private suspend fun fetchCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url.withArtSize(1080)).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes().takeIf { it.size in 1..MAX_COVER_BYTES } else null
            }
        }.getOrNull()
    }

    private fun foregroundInfo(n: Int, total: Int, title: String): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.download_notification_title, n, total))
            .setContentText(title)
            .setProgress(total, n - 1, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun entryFor(s: MusicItem.Song, single: Boolean) = DownloadEntry(
        videoId = s.videoId,
        title = s.title,
        artist = s.artists.joinToString(", ") { it.name },
        album = s.album?.name,
        albumArtist = s.artists.firstOrNull()?.name,
        trackNumber = null,
        artworkUrl = s.thumbnail?.withArtSize(1080),
        durationSec = parseDuration(s.duration),
        single = single,
    )

    private fun entryFor(s: LocalSong) = DownloadEntry(
        videoId = s.videoId,
        title = s.title,
        artist = s.artist,
        album = s.album,
        albumArtist = s.artist.substringBefore(", ").ifBlank { null },
        trackNumber = null,
        artworkUrl = s.artworkUrl?.withArtSize(1080),
        durationSec = s.durationSec,
        single = true,
    )

    companion object {
        private const val TAG = "Downloads"
        const val WORK = "downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 0xD0
        private const val PARALLEL = 2
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val MAX_COVER_BYTES = 4 * 1024 * 1024
    }
}

/** Runs [DownloadManager.runQueue]. Dependencies through an entry point, so WorkManager needs no Hilt setup. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloads(): DownloadManager
    }

    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).downloads()
        return if (manager.runQueue { setForeground(it) }) Result.success() else Result.retry()
    }
}
