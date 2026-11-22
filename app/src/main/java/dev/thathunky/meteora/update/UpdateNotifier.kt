package dev.thathunky.meteora.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.thathunky.meteora.R

/**
 * The download's notification on its own channel. Posts only when the app already has the
 * notification permission (asked elsewhere, for playback); it never asks for it.
 */
internal class UpdateNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)
    private var lastPost = 0L

    fun progress(r: Release, percent: Int) {
        // Android drops updates posted faster than about ten a second; two a second is plenty.
        val now = SystemClock.elapsedRealtime()
        if (percent in 1..99 && now - lastPost < 500) return
        lastPost = now
        post(
            builder()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.update_notif_downloading, r.version.display()))
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setSilent(true),
        )
    }

    fun ready(r: Release) = post(
        builder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notif_ready, r.version.display()))
            .setContentText(context.getString(R.string.update_notif_ready_text))
            .setAutoCancel(true),
    )

    fun failed(r: Release?) {
        if (r == null) return cancel()
        post(
            builder()
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.update_notif_failed))
                .setContentText(context.getString(R.string.update_notif_ready_text))
                .setAutoCancel(true),
        )
    }

    fun cancel() = nm.cancel(ID)

    private fun builder(): NotificationCompat.Builder {
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL) == null) {
            system.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(context, CHANNEL).setContentIntent(open).setOnlyAlertOnce(true)
    }

    private fun post(b: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { nm.notify(ID, b.build()) }
    }

    private companion object {
        const val CHANNEL = "updates"
        const val ID = 0x7e7e0
    }
}
