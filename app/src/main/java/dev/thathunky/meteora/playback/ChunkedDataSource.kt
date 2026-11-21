package dev.thathunky.meteora.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Reads a long exact byte range as consecutive requests of at most [chunkBytes] each. YouTube
 * serves a range of up to 10 MiB at full speed and throttles a larger one to about twice the
 * bitrate, as it does an open-ended one (docs/api-notes.md, "Video formats"). A video file is
 * tens of MB, so without this the picture would buffer no faster than it plays. Requests with no
 * length, or short enough already (nearly every audio file), go through unchanged.
 */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource, private val chunkBytes: Long = CHUNK_BYTES) : DataSource {
    /** The whole request while it is being read in chunks; null when passed straight through. */
    private var spec: DataSpec? = null
    /** Absolute position of the next byte, and the end of the request (exclusive). */
    private var position = 0L
    private var end = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length <= chunkBytes) {
            spec = null
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        position = dataSpec.position
        end = dataSpec.position + dataSpec.length
        openChunk(dataSpec)
        return dataSpec.length
    }

    private fun openChunk(s: DataSpec) {
        upstream.open(s.subrange(position - s.position, nextChunkLength(position, end, chunkBytes)))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = spec ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            // This chunk is done (or the server sent less than asked): ask for the next one from here.
            upstream.close()
            openChunk(s)
            n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        position += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val chunkBytes: Long = CHUNK_BYTES) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    companion object {
        /** 10 MiB: the largest range seen served at full speed (2026-11-22). */
        const val CHUNK_BYTES = 10L * 1024 * 1024

        /** Length of the request that starts at [position] in a range ending at [end] (exclusive). */
        fun nextChunkLength(position: Long, end: Long, chunkBytes: Long): Long = minOf(chunkBytes, end - position)
    }
}
