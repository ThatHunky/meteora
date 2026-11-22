package dev.thathunky.meteora.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * A [PackageInstaller] session for one verified APK. Android always asks the user: skipping the
 * dialog (`USER_ACTION_NOT_REQUIRED`) also needs the `UPDATE_PACKAGES_WITHOUT_USER_ACTION`
 * permission, which the manifest deliberately does not declare (docs/updater-plan.md: nothing is
 * installed without the user's tap). The request is set anyway so declaring it later is enough.
 * The dialog arrives as `STATUS_PENDING_USER_ACTION` in [UpdateStatusReceiver].
 */
internal object UpdateInstaller {
    const val ACTION = "dev.thathunky.meteora.UPDATE_STATUS"

    fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                // Explicit (our receiver, our package) and mutable: the installer fills in the status extras.
                val status = Intent(context, UpdateStatusReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (x: Exception) {
            runCatching { installer.abandonSession(id) }
            throw x
        }
    }
}

/** The install session's status. Not exported: only the system installer's PendingIntent reaches it. */
class UpdateStatusReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updater(): Updater
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).updater().onInstallStatus(status, message, confirm)
    }
}
