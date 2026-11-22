package dev.thathunky.meteora.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import dev.thathunky.meteora.core.MeteoraCore
import dev.thathunky.meteora.playback.ChunkedDataSource
import dev.thathunky.meteora.stream.AudioStream
import dev.thathunky.meteora.stream.StreamException
import dev.thathunky.meteora.stream.StreamQuality
import dev.thathunky.meteora.stream.StreamResolver
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** Byte-range maths for a download, pure for the JVM tests. */
object DownloadRanges {
    /** At most 10 MiB per request: YouTube throttles larger and open-ended ranges (docs/api-notes.md, "Video formats"). */
    const val CHUNK = ChunkedDataSource.CHUNK_BYTES

    /** The `Range` header for the request that starts at [position] in a file of [total] bytes. */
    fun range(position: Long, total: Long, chunk: Long = CHUNK): String {
        val len = ChunkedDataSource.nextChunkLength(position, total, chunk)
        require(len > 0) { "nothing left to fetch" }
        return "bytes=$position-${position + len - 1}"
    }

    /**
     * Where to go on after re-resolving: the same file continues from [position], another one
     * starts over. The same itag is not enough: a loudness-compressed or dubbed variant shares
     * the itag of the plain track but is another file, which its size tells apart.
     */
    fun resumeAt(oldItag: Int, oldTotal: Long, newItag: Int, newTotal: Long, position: Long): Long =
        if (oldItag == newItag && oldTotal == newTotal) position else 0

    /** The temporary file's name: a part is only ever continued by a request for the same file. */
    fun partName(videoId: String, itag: Int, total: Long) = "$videoId.$itag.$total.part"

    /** Whole percent reached, for progress that is reported once per percent, not per buffer. */
    fun percent(at: Long, total: Long): Int = if (total <= 0) 0 else (at * 100 / total).toInt()
}

/** One file written to shared storage. */
data class Written(val uri: Uri, val itag: Int, val bytes: Long, val tagged: Boolean)

/**
 * One song to a tagged `.m4a` in `Music/Meteora/<Artist>/<Album>/`: resolve (AAC only), fetch in
 * ranges of at most 10 MiB into a private temporary file, tag it in the Rust core, then write it
 * through MediaStore (pending until complete). No storage permission is needed for files the app
 * creates. URLs are never logged.
 */
class Downloader(
    private val context: Context,
    http: OkHttpClient,
    private val resolver: StreamResolver,
) {
    private val http = http.newBuilder().readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val tmpDir get() = File(context.filesDir, "downloads-tmp").apply { mkdirs() }

    /**
     * Downloads [e]. [commit] is called with the finished file at once, before anything can
     * cancel the call: once the file exists in shared storage it must reach the index, or a
     * cancellation at that moment would leave it there unknown and queue the song again.
     */
    suspend fun download(
        e: DownloadEntry,
        quality: StreamQuality,
        cover: ByteArray?,
        onProgress: (Float) -> Unit,
        commit: (Written) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        // Resolved just before fetching: URLs expire after about six hours and are tied to this IP.
        var stream = resolver.resolveForDownload(e.videoId, quality)
        var itag = itagOf(stream)
        var total = stream.contentLength ?: throw StreamException("no file size for ${e.videoId}")
        var part = partFile(e.videoId, itag, total)
        // A part left by another format would mix two files' bytes.
        partsOf(e.videoId).filter { it != part }.forEach { it.delete() }
        var position = part.length().coerceAtMost(total)
        var reResolved = false
        var shown = -1
        val progress = { at: Long ->
            val p = DownloadRanges.percent(at, total)
            if (p != shown) {
                shown = p
                onProgress(at.toFloat() / total)
            }
        }
        while (position < total) {
            ensureActive()
            try {
                position = fetch(stream, part, position, total, job, progress)
            } catch (x: CancellationException) {
                throw x
            } catch (x: IOException) {
                if (reResolved) throw x
                reResolved = true
                Log.w(TAG, "range failed for ${e.videoId} at $position of $total: ${x.javaClass.simpleName}; resolving again")
                stream = resolver.resolveForDownload(e.videoId, quality)
                val newItag = itagOf(stream)
                val newTotal = stream.contentLength ?: throw StreamException("no file size for ${e.videoId}")
                position = DownloadRanges.resumeAt(itag, total, newItag, newTotal, position)
                if (newItag != itag || newTotal != total) {
                    part.delete()
                    itag = newItag
                    total = newTotal
                    part = partFile(e.videoId, itag, total)
                }
            }
        }
        ensureActive()
        // No suspension from here to commit: the file and its index row appear together.
        val written = write(e, part, total, itag, cover)
        commit(written)
        part.delete()
    }

    private fun itagOf(s: AudioStream): Int = s.cacheKey.substringAfter('/', "").toIntOrNull() ?: 0

    private fun partFile(videoId: String, itag: Int, total: Long) = File(tmpDir, DownloadRanges.partName(videoId, itag, total))

    private fun partsOf(videoId: String): List<File> =
        tmpDir.listFiles()?.filter { it.name.startsWith("$videoId.") && it.name.endsWith(".part") }.orEmpty()

    /** Records the MediaStore row a write is filling, so a write cut off by process death can be cleaned up. */
    private fun pendingMarker(videoId: String) = File(tmpDir, "$videoId.pending")

    /** Deletes the row a cut-off write left pending (hidden, holding its space for a week). */
    private fun dropStalePending(videoId: String) {
        val marker = pendingMarker(videoId)
        if (!marker.exists()) return
        runCatching { context.contentResolver.delete(Uri.parse(marker.readText()), null, null) }
        marker.delete()
    }

    /** A song no longer wanted: its temporary parts and any row a cut-off write left pending go. */
    fun discard(videoId: String) {
        partsOf(videoId).forEach { it.delete() }
        dropStalePending(videoId)
    }

    /** One range appended at [position]; returns the new position. Stops between buffers when [job] is cancelled. */
    private fun fetch(stream: AudioStream, part: File, position: Long, total: Long, job: Job?, progress: (Long) -> Unit): Long {
        val req = Request.Builder().url(stream.url)
            .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", DownloadRanges.range(position, total))
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code != 206 && !(res.code == 200 && position == 0L)) throw IOException("HTTP ${res.code}")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(position)
                out.seek(position)
                var at = position
                val buf = ByteArray(64 * 1024)
                res.body.byteStream().use { input ->
                    while (true) {
                        // The bytes so far stay in the part, and the next run continues from them.
                        if (job?.isActive == false) throw CancellationException("download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        at += n
                        progress(at)
                    }
                }
                if (at == position) throw IOException("empty range")
                return at
            }
        }
    }

    private fun write(e: DownloadEntry, part: File, total: Long, itag: Int, cover: ByteArray?): Written {
        val (head, resumeAt) = tag(e, part, cover)
        val resolverCr = context.contentResolver
        val folder = DownloadPaths.relativePath(e.albumArtist ?: e.artist.substringBefore(", "), e.album)
        val name = DownloadPaths.unique(DownloadPaths.displayName(e.trackNumber, e.title), namesIn(folder))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        // A write cut off last time (the process died) left a hidden pending row: it goes first.
        dropStalePending(e.videoId)
        val uri = resolverCr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused the file")
        val marker = pendingMarker(e.videoId)
        try {
            marker.writeText(uri.toString())
            resolverCr.openOutputStream(uri, "w")?.use { out ->
                out.write(head)
                part.inputStream().use { input ->
                    var skip = resumeAt
                    while (skip > 0) {
                        val s = input.skip(skip)
                        if (s <= 0) throw IOException("short temporary file")
                        skip -= s
                    }
                    input.copyTo(out, 64 * 1024)
                }
            } ?: throw IOException("cannot open the new file")
            // Still pending, the file stays hidden and MediaStore deletes it after a week.
            if (resolverCr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) < 1) {
                throw IOException("MediaStore did not publish the file")
            }
        } catch (x: Exception) {
            runCatching { resolverCr.delete(uri, null, null) }
            throw x
        } finally {
            marker.delete()
        }
        Log.i(TAG, "downloaded ${e.videoId}: itag $itag, $total bytes, ${if (resumeAt > 0) "tagged" else "untagged"}")
        return Written(uri, itag, head.size + (total - resumeAt), resumeAt > 0)
    }

    /** The new head and where to continue in [part]; the file as it is when it cannot be tagged. */
    private fun tag(e: DownloadEntry, part: File, cover: ByteArray?): Pair<ByteArray, Long> {
        val length = part.length()
        val head = ByteArray(minOf(length, HEAD_BYTES).toInt())
        val tail = ByteArray(minOf(length, 16L).toInt())
        RandomAccessFile(part, "r").use { f ->
            f.readFully(head)
            f.seek(length - tail.size)
            f.readFully(tail)
        }
        val tags = buildJsonObject {
            put("title", e.title)
            put("artist", e.artist)
            e.album?.let { put("album", it) }
            put("album_artist", e.albumArtist ?: e.artist.substringBefore(", "))
            e.trackNumber?.takeIf { it > 0 }?.let { put("track", it) }
            e.year?.let { put("year", it) }
        }.toString()
        return try {
            MeteoraCore.tagM4a(head, tail, tags, cover)
        } catch (x: StreamException) {
            // The fallback the plan names: the file plays, its folder still says artist and album.
            Log.w(TAG, "not tagging ${e.videoId}: ${x.message}")
            ByteArray(0) to 0L
        }
    }

    /** File names already in [folder] that this app can see (its own). */
    private fun namesIn(folder: String): List<String> = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(folder),
            null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
    }.getOrDefault(emptyList())

    /** Removes a downloaded file; true if it is gone (also when it already was). */
    fun delete(uri: String): Boolean = runCatching { context.contentResolver.delete(Uri.parse(uri), null, null); true }.getOrDefault(false)

    /** True while the file behind [uri] can still be opened (the user may have deleted it in a file manager). */
    fun exists(uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Downloader"
        /** ftyp + moov + sidx are about 1 KB; 256 KB leaves room for the first fragment too. */
        const val HEAD_BYTES = 256L * 1024
    }
}
