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
    private val db = SqliteDownloadStore(context)
    val index = DownloadIndex(db)
    /** Smart downloads' listening log, in the same database. Written by LocalEngine (phone target only). */
    val listens = ListenLog(SqliteListenStore(db), scope)
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

    /** True when downloading is allowed now; otherwise tells the UI (when [notify]) and returns false. */
    suspend fun allowed(forceCheck: Boolean = false, notify: Boolean = true): Boolean = gateLock.withLock {
        val headers = runCatching { auth.headers() }
        val signedIn = headers.getOrNull() != null
        val now = System.currentTimeMillis()
        when (DownloadsGate.decide(signedIn, settings.premiumAtMs, now, forceCheck)) {
            DownloadsGate.Decision.Allowed -> true
            DownloadsGate.Decision.SignedOut -> false.also {
                // The memory is not tied to an account: the next one to sign in must be checked.
                // Only when the store said "no session", not when reading it failed this once.
                if (headers.isSuccess) settings.premiumAtMs = null
                if (notify) _blocked.tryEmit(Blocked.SignedOut)
            }
            DownloadsGate.Decision.Check -> {
                val verdict = premium.verdict()
                val ok = verdict == true
                settings.premiumAtMs = DownloadsGate.remember(verdict, settings.premiumAtMs, now)
                if (!ok && notify) _blocked.tryEmit(Blocked.NotPremium)
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
        val albumArtist = if (isAlbum) DownloadRules.albumArtist(page.subtitle, page.secondSubtitle) else null
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
        index.songs.value.keys.forEach(::forget)
        index.collections.value.keys.forEach(index::deleteCollection)
    }

    internal fun apply(r: DownloadRules.Release) {
        // Only the reasons to keep it change: the state may have moved on since the snapshot.
        r.keep.forEach { k -> index.update(k.videoId) { it.copy(single = k.single, smart = k.smart, collections = k.collections) } }
        r.delete.forEach { forget(it.videoId) }
    }

    /**
     * Drops a song from the index and deletes its file as the index has it now (not as an older
     * snapshot had it: a download may have finished since), with any temporary part.
     */
    private fun forget(videoId: String) {
        index.remove(videoId)?.uri?.let(downloader::delete)
        downloader.discard(videoId)
    }

    /** The player found the file gone: forget it, so the track streams. */
    fun forgetMissing(videoId: String) {
        index.update(videoId) { it.copy(state = DownloadState.Failed, uri = null) }
    }

    fun fileExists(uri: String): Boolean = downloader.exists(uri)

    // ---- offline ----

    /** No network that reaches the internet. */
    fun offline(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * A queue built from the index alone (the network is needed for YouTube's queue): the
     * collection [playlistId]'s downloaded tracks in order, or just [videoId]. Null when nothing fits.
     */
    fun localQueue(playlistId: String?, videoId: String?): Pair<List<LocalSong>, Int>? {
        val songs = index.songs.value
        // collectionFor also finds a playlist asked for by its page id ("VL…") rather than its playlist id.
        val ids = playlistId?.let { index.collectionFor(it, null)?.videoIds } ?: listOfNotNull(videoId)
        val done = ids.mapNotNull { songs[it]?.takeIf { e -> e.state == DownloadState.Done } }
        if (done.isEmpty()) return null
        val start = done.indexOfFirst { it.videoId == videoId }.coerceAtLeast(0)
        return done.map { it.toLocalSong() } to start
    }

    // ---- space ----

    fun usedBytes(): Long = index.songs.value.values.filter { it.state == DownloadState.Done }.sumOf { it.bytes }

    @Suppress("DEPRECATION")
    fun freeBytes(): Long = runCatching { StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    // ---- running ----

    /**
     * Appended, so songs queued while a run is ending are picked up by the next one. [replace]:
     * the network rule changed, so the run (and its constraint) is replaced; a cut-off song is
     * queued again and resumes from its temporary file.
     */
    fun schedule(replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** The worker's body. False: stopped by a network failure, to be retried when the network is back. */
    internal suspend fun runQueue(foreground: suspend (ForegroundInfo) -> Unit): Boolean {
        index.ensureLoaded()
        // Covers of the last few albums only: a 500-song smart run over 400 albums would hold them all.
        val covers = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > COVERS_KEPT
        })
        var done = 0
        var failedInARow = 0
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
            // Smart downloads stop early when lookups keep failing: the slow fallback is not used for them.
            failedInARow = if (batch.all { index.get(it.videoId)?.state == DownloadState.Failed }) failedInARow + batch.size else 0
            if (failedInARow >= MAX_FAILED_IN_A_ROW && index.queued().all { it.smart && !it.manual }) {
                Log.w(TAG, "stopping: $failedInARow downloads in a row failed")
                return true
            }
        }
    }

    /** False on a network failure (the song goes back to the queue). */
    private suspend fun one(queued: DownloadEntry, covers: MutableMap<String, ByteArray>): Boolean {
        val id = queued.videoId
        index.update(id) { it.copy(state = DownloadState.Downloading) }
        index.setProgress(id, 0f)
        return try {
            // Inside the try: a cancellation during the album lookup must not leave the row Downloading.
            val e = withAlbum(queued)
            val cover = e.artworkUrl?.let { url ->
                val key = "${e.albumArtist ?: e.artist}/${e.album ?: url}"
                covers[key] ?: fetchCover(url)?.also { covers[key] = it }
            }
            downloader.download(e, settings.quality.value, cover, { index.setProgress(id, it) }) { w ->
                val kept = index.update(id) { it.copy(state = DownloadState.Done, uri = w.uri.toString(), itag = w.itag, bytes = w.bytes) }
                // Removed while it was downloading: the file goes too.
                if (!kept) downloader.delete(w.uri.toString())
            }
            true
        } catch (x: CancellationException) {
            // A file finished just before the cancellation is already Done: leave it so.
            index.update(id) { if (it.state == DownloadState.Downloading) it.copy(state = DownloadState.Queued) else it }
            throw x
        } catch (x: IOException) {
            if (!isConnectivity(x)) {
                // A refused URL, MediaStore or a full disk: this song fails, the queue goes on.
                Log.w(TAG, "download failed for $id: ${x.javaClass.simpleName} ${x.message}")
                index.update(id) { it.copy(state = DownloadState.Failed) }
                return true
            }
            Log.w(TAG, "network failure on $id: ${x.javaClass.simpleName}")
            index.update(id) { it.copy(state = DownloadState.Queued) }
            false
        } catch (x: Exception) {
            Log.w(TAG, "download failed for $id: ${x.message}")
            index.update(id) { it.copy(state = DownloadState.Failed) }
            true
        } finally {
            index.setProgress(id, null)
        }
    }

    /**
     * A song picked from Home, search or a playlist often comes without its album, and the file
     * would land in "Singles". The watch queue names the album; its page gives the track number,
     * the year and the album's artist. Any failure leaves the entry as it was.
     */
    private suspend fun withAlbum(e: DownloadEntry): DownloadEntry {
        if (e.album != null) return e
        return try {
            val ref = ytm.watchQueue(videoId = e.videoId).songs.firstOrNull { it.videoId == e.videoId }?.album ?: return e
            val page = ref.browseId?.let { id -> runCatching { ytm.album(id) }.getOrElse { x -> if (x is CancellationException) throw x else null } }
            val position = page?.tracks?.indexOfFirst { it.videoId == e.videoId }?.takeIf { it >= 0 }
            val found = e.copy(
                album = page?.title ?: ref.name,
                albumArtist = page?.let { DownloadRules.albumArtist(it.subtitle, it.secondSubtitle) } ?: e.albumArtist,
                trackNumber = e.trackNumber ?: position?.plus(1),
                year = e.year ?: page?.let { DownloadRules.year(listOfNotNull(it.subtitle, it.secondSubtitle).joinToString(" ")) },
                artworkUrl = e.artworkUrl ?: page?.thumbnail?.withArtSize(1080),
            )
            index.update(e.videoId) {
                it.copy(album = found.album, albumArtist = found.albumArtist, trackNumber = found.trackNumber, year = found.year, artworkUrl = found.artworkUrl)
            }
            found
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            Log.w(TAG, "no album for ${e.videoId}: ${x.javaClass.simpleName}")
            e
        }
    }

    /** The same set LocalEngine treats as "offline": only these wait for the network instead of failing the song. */
    private fun isConnectivity(x: Throwable): Boolean = generateSequence(x) { it.cause }.any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
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
        private const val MAX_FAILED_IN_A_ROW = 6
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val MAX_COVER_BYTES = 4 * 1024 * 1024
        private const val COVERS_KEPT = 8
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
