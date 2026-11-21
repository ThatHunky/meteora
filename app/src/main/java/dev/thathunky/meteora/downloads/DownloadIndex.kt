package dev.thathunky.meteora.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What is downloaded, queued or failed, kept in [store] and mirrored in memory for the UI and the
 * player. Every write goes to the store first. Thread-safe; the first use reads the store, so
 * call it off the main thread once (the app does at start-up).
 */
class DownloadIndex(private val store: DownloadStore) {
    private val _songs = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val songs: StateFlow<Map<String, DownloadEntry>> = _songs.asStateFlow()
    private val _collections = MutableStateFlow<Map<String, DownloadCollection>>(emptyMap())
    val collections: StateFlow<Map<String, DownloadCollection>> = _collections.asStateFlow()
    /** videoId → 0..1 while it downloads. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // Work that was cut off (the app was killed) is queued again.
            val songs = store.songs().map { if (it.state == DownloadState.Downloading) it.copy(state = DownloadState.Queued) else it }
            _songs.value = songs.associateBy { it.videoId }
            _collections.value = store.collections().associateBy { it.id }
            loaded = true
        }
    }

    fun get(videoId: String): DownloadEntry? {
        ensureLoaded()
        return _songs.value[videoId]
    }

    /** The file of a complete download, or null. */
    fun completeUri(videoId: String): String? = get(videoId)?.takeIf { it.state == DownloadState.Done }?.uri

    fun put(e: DownloadEntry) = synchronized(this) {
        ensureLoaded()
        store.putSong(e)
        _songs.value = _songs.value + (e.videoId to e)
    }

    fun delete(videoId: String) = synchronized(this) {
        ensureLoaded()
        store.deleteSong(videoId)
        _songs.value = _songs.value - videoId
        _progress.value = _progress.value - videoId
    }

    fun putCollection(c: DownloadCollection) = synchronized(this) {
        ensureLoaded()
        store.putCollection(c)
        _collections.value = _collections.value + (c.id to c)
    }

    fun deleteCollection(id: String) = synchronized(this) {
        ensureLoaded()
        store.deleteCollection(id)
        _collections.value = _collections.value - id
    }

    /** Changes one entry if it is still there. */
    fun update(videoId: String, change: (DownloadEntry) -> DownloadEntry) = synchronized(this) {
        get(videoId)?.let { put(change(it)) }
    }

    fun setProgress(videoId: String, fraction: Float?) {
        _progress.value = if (fraction == null) _progress.value - videoId else _progress.value + (videoId to fraction)
    }

    fun queued(): List<DownloadEntry> {
        ensureLoaded()
        return _songs.value.values.filter { it.state == DownloadState.Queued }.sortedBy { it.addedAtMs }
    }

    /** The downloaded collection with playlist id [playlistId] or page [browseId], if any. */
    fun collectionFor(playlistId: String?, browseId: String?): DownloadCollection? {
        ensureLoaded()
        val all = _collections.value
        return playlistId?.let { all[it] ?: all[it.removePrefix("VL")] } ?: browseId?.let { b -> all.values.firstOrNull { it.browseId == b } }
    }

    /** One song's state for its button. */
    fun status(videoId: String): Flow<DownloadStatus> =
        songs.map { it[videoId] }.distinctUntilChanged().combineProgress(videoId)

    private fun Flow<DownloadEntry?>.combineProgress(videoId: String): Flow<DownloadStatus> =
        kotlinx.coroutines.flow.combine(this, progress.map { it[videoId] }.distinctUntilChanged()) { e, p -> DownloadStatus.of(e, p) }
            .distinctUntilChanged()

    /** A collection's state: downloaded when every track is, the share done while it is not. */
    fun collectionStatus(id: String): Flow<DownloadStatus> =
        kotlinx.coroutines.flow.combine(collections, songs) { cs, songs ->
            val c = cs[id] ?: return@combine DownloadStatus.None
            DownloadStatus.ofCollection(c.videoIds.mapNotNull { songs[it] })
        }.distinctUntilChanged()
}

/** What a download button shows. */
sealed interface DownloadStatus {
    data object None : DownloadStatus
    data object Queued : DownloadStatus
    data class Downloading(val fraction: Float) : DownloadStatus
    data object Done : DownloadStatus
    data object Failed : DownloadStatus

    companion object {
        fun of(e: DownloadEntry?, progress: Float?): DownloadStatus = when (e?.state) {
            null -> None
            DownloadState.Queued -> Queued
            DownloadState.Downloading -> Downloading(progress ?: 0f)
            DownloadState.Done -> Done
            DownloadState.Failed -> Failed
        }

        fun ofCollection(tracks: List<DownloadEntry>): DownloadStatus = when {
            tracks.isEmpty() -> Queued
            tracks.all { it.state == DownloadState.Done } -> Done
            tracks.any { it.state == DownloadState.Queued || it.state == DownloadState.Downloading } ->
                Downloading(tracks.count { it.state == DownloadState.Done }.toFloat() / tracks.size)
            else -> Failed
        }
    }
}
