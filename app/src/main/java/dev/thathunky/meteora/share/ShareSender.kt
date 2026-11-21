package dev.thathunky.meteora.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ytm.MusicItem

/** Opens the system share sheet with a link. Call with the activity context. */
fun Context.shareLink(url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    val chooser = Intent.createChooser(send, getString(R.string.share_chooser)).apply {
        // Our own "Play on PC" target takes text/plain too; sharing to ourselves makes no sense here.
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this@shareLink, ShareReceiverActivity::class.java)))
    }
    startActivity(chooser)
}

fun Context.share(item: MusicItem) = shareLink(shareUrl(item), item.shareTitle())
