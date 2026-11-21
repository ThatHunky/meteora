package dev.thathunky.meteora.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Sends `content://` specs (downloaded files) to [content] and everything else to [network]
 * (the disk-cached HTTP source). A downloaded file must never go through the HTTP cache: it is
 * already on the phone, and its bytes need no cache key.
 */
@UnstableApi
class SchemeDataSource(private val network: DataSource, private val content: DataSource) : DataSource {
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        network.addTransferListener(transferListener)
        content.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "content") content else network
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val network: DataSource.Factory, private val content: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeDataSource(network.createDataSource(), content.createDataSource())
    }
}
