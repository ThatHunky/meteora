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

    /** Where to go on after re-resolving: the same format continues from [position], another one starts over. */
    fun resumeAt(oldItag: Int, newItag: Int, position: Long): Long = if (oldItag == newItag) position else 0
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

    suspend fun download(e: DownloadEntry, quality: StreamQuality, cover: ByteArray?, onProgress: (Float) -> Unit): Written = withContext(Dispatchers.IO) {
        // Resolved just before fetching: URLs expire after about six hours and are tied to this IP.
        var stream = resolver.resolveForDownload(e.videoId, quality)
        var itag = itagOf(stream)
        var total = stream.contentLength ?: throw StreamException("no file size for ${e.videoId}")
        var part = partFile(e.videoId, itag)
        // A part left by another format would mix two files' bytes.
        tmpDir.listFiles()?.filter { it.name.startsWith("${e.videoId}.") && it != part }?.forEach { it.delete() }
        var position = part.length().coerceAtMost(total)
        var reResolved = false
        while (position < total) {
            ensureActive()
            try {
                position = fetch(stream, part, position, total)
                onProgress(position.toFloat() / total)
            } catch (x: CancellationException) {
                throw x
            } catch (x: IOException) {
                if (reResolved) throw x
                reResolved = true
                Log.w(TAG, "range failed for ${e.videoId} at $position of $total: ${x.javaClass.simpleName}; resolving again")
                stream = resolver.resolveForDownload(e.videoId, quality)
                val newItag = itagOf(stream)
                position = DownloadRanges.resumeAt(itag, newItag, position)
                if (newItag != itag) {
                    part.delete()
                    itag = newItag
                    part = partFile(e.videoId, itag)
                    total = stream.contentLength ?: throw StreamException("no file size for ${e.videoId}")
                }
            }
        }
        val written = write(e, part, total, itag, cover)
        part.delete()
        written
    }

    private fun itagOf(s: AudioStream): Int = s.cacheKey.substringAfter('/', "").toIntOrNull() ?: 0

    private fun partFile(videoId: String, itag: Int) = File(tmpDir, "$videoId.$itag.part")

    /** One range appended at [position]; returns the new position. */
    private fun fetch(stream: AudioStream, part: File, position: Long, total: Long): Long {
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
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        at += n
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
        val uri = resolverCr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused the file")
        try {
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
            resolverCr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (x: Exception) {
            runCatching { resolverCr.delete(uri, null, null) }
            throw x
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
