package dev.thathunky.meteora.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.BuildConfig
import dev.thathunky.meteora.di.AppScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The in-app updater (docs/updater-plan.md). Checks GitHub's release list (small, anonymous, at
 * most once a day by itself), downloads the APK only when the user taps, verifies it
 * ([checkApk]) and hands it to Android's installer, which asks the user.
 */
@Singleton
class Updater @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
    val store: UpdateStore,
) {
    /** TLS only, no cookies, no HTTPS-to-HTTP redirects. GitHub serves assets from another host, so redirects are followed. */
    private val http = okHttp.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val installed: Version = Version.parse(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val notifier = UpdateNotifier(context)
    private val dir = File(context.cacheDir, "updates")
    private var job: Job? = null
    @Volatile private var call: okhttp3.Call? = null

    init {
        // The offer found before the app was killed, if it still is one. A finished download is
        // not re-fetched: download() finds the complete file and goes straight to verifying it.
        store.offered?.let { r ->
            val still = Releases.pick(listOf(r), installed, store.includePreReleases.value, store.skipped.value)
            if (still != null) _state.value = UpdateState.Available(still) else store.offered = null
        }
        // Leftovers: a file for the version now installed (the install killed the process before
        // it could clean up) or an older one, or anything that is not ours.
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.forEach { f ->
                val v = Version.parse(f.name.removeSuffix(".apk"))
                if (v == null || v <= installed) f.delete()
            }
        }
    }

    /** From the UI when the app is shown: checks when the switch is on and a day has passed. */
    fun checkIfDue() {
        if (!store.auto.value || !UpdateStore.isDue(store.lastCheckAt.value, System.currentTimeMillis())) return
        val s = _state.value
        if (s is UpdateState.Idle || s is UpdateState.UpToDate || (s is UpdateState.Failed && s.release == null)) check(manual = false)
    }

    /**
     * Asks GitHub for the release list. [manual] (the Settings button) shows Checking and its
     * failures, and offers a skipped version again; the automatic check fails silently.
     */
    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        val before = _state.value
        if (before is UpdateState.Downloading || before is UpdateState.Verifying || before is UpdateState.Installing || before is UpdateState.Ready) return
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch(Dispatchers.IO) {
            val next = try {
                http.newCall(Releases.request(BuildConfig.VERSION_NAME)).execute().use { r ->
                    when {
                        // Rate limited (60 an hour per IP without a token): try again tomorrow.
                        r.code == 403 || r.code == 429 -> {
                            store.setLastCheckAt(System.currentTimeMillis())
                            UpdateState.Failed(Failure.Busy, null)
                        }
                        !r.isSuccessful -> UpdateState.Failed(Failure.Network, null)
                        else -> {
                            val releases = Releases.parse(r.body.string())
                            store.setLastCheckAt(System.currentTimeMillis())
                            val skipped = if (manual) null else store.skipped.value
                            val found = Releases.pick(releases, installed, store.includePreReleases.value, skipped)
                            store.offered = found
                            found?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (x: IOException) {
                Log.w(TAG, "check failed: ${x.javaClass.simpleName}")
                UpdateState.Failed(Failure.Network, null)
            }
            // A failed check keeps an offer already showing; the automatic one never shows its failure.
            _state.value = if (next is UpdateState.Failed && (!manual || before.release != null)) before else next
        }
    }

    /** Starts or resumes the download of the release on offer. Only ever from the user's tap. */
    fun download() {
        val s = _state.value
        val r = s.release ?: return
        if (s !is UpdateState.Available && s !is UpdateState.Failed) return
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val file = fetch(r)
                currentCoroutineContext().ensureActive()
                _state.value = UpdateState.Verifying(r)
                val refusal = verify(r, file)
                if (refusal != null) {
                    Log.w(TAG, "refused ${r.tag}: $refusal")
                    file.delete()
                    fail(Failure.of(refusal), r)
                } else {
                    _state.value = UpdateState.Ready(r, file)
                    notifier.ready(r)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: NoSpace) {
                fail(Failure.Storage, r)
            } catch (_: TooLong) {
                fileFor(r).delete()
                fail(Failure.Size, r)
            } catch (x: IOException) {
                // A cancelled call throws IOException too: that is a stop, not a failure.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "download failed: ${x.javaClass.simpleName}: ${x.message}")
                fail(Failure.Network, r)
            }
        }
    }

    /** Stops a download; what was fetched stays for the next try. */
    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        stop { _state.value = UpdateState.Available(s.release) }
    }

    /**
     * Cancels the running job and its HTTP call, then runs [after] once the job has really
     * ended, so a last progress update from it cannot overwrite the new state.
     */
    private fun stop(after: () -> Unit) {
        val j = job
        call?.cancel()
        if (j == null || !j.isActive) {
            notifier.cancel()
            after()
            return
        }
        j.cancel()
        j.invokeOnCompletion {
            notifier.cancel()
            after()
        }
    }

    /** Hands the verified file to Android's installer. The status comes back through [onInstallStatus]. */
    fun install() {
        val s = _state.value as? UpdateState.Ready ?: return
        if (!s.file.isFile) {
            _state.value = UpdateState.Available(s.release)
            return
        }
        pending = s
        _state.value = UpdateState.Installing(s.release)
        scope.launch(Dispatchers.IO) {
            try {
                UpdateInstaller.commit(context, s.file)
            } catch (x: Exception) {
                if (x is CancellationException) throw x
                Log.w(TAG, "install session failed: $x")
                pending = null
                fail(Failure.InstallOther, s.release, x.message)
            }
        }
    }

    /** The release on offer is not offered again by the automatic check. */
    fun skip() {
        val r = _state.value.release ?: return
        if (_state.value is UpdateState.Installing) return
        store.setSkipped(r.version)
        store.offered = null
        stop {
            fileFor(r).delete()
            _state.value = UpdateState.Idle
        }
    }

    private var pending: UpdateState.Ready? = null

    /** From [UpdateStatusReceiver]: the installer's answer to the session. */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val p = pending ?: return
        Log.i(TAG, "install status $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    pending = null
                    fail(Failure.InstallOther, p.release, message)
                } else {
                    // The app is in front (the user just tapped Install), so it may start the dialog.
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { pending = null; fail(Failure.InstallOther, p.release, it.message) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                pending = null
                store.offered = null
                p.file.delete()
                notifier.cancel()
                _state.value = UpdateState.Idle
            }
            // The user said no in the dialog: offer the same file again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                pending = null
                _state.value = p
            }
            else -> {
                pending = null
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> Failure.InstallConflict
                    PackageInstaller.STATUS_FAILURE_STORAGE -> Failure.InstallStorage
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> Failure.InstallBlocked
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Failure.InstallIncompatible
                    PackageInstaller.STATUS_FAILURE_INVALID -> Failure.InstallInvalid
                    else -> Failure.InstallOther
                }
                fail(reason, p.release, message)
            }
        }
    }

    /** False until the user allows installs from Meteora; Android asks the first time. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun fail(reason: Failure, r: Release?, detail: String? = null) {
        _state.value = UpdateState.Failed(reason, r, detail)
        notifier.failed(r)
    }

    private fun fileFor(r: Release) = File(dir, "v${r.version}.apk")

    private class NoSpace : IOException()
    private class TooLong : IOException()

    /**
     * Downloads to `cacheDir/updates/v<version>.apk`, resuming a partial file with a Range
     * request: 206 appends, 200 (Range ignored) starts over, 416 deletes and starts over.
     */
    private suspend fun fetch(r: Release): File {
        dir.mkdirs()
        val file = fileFor(r)
        for (attempt in 0..1) {
            var have = if (file.isFile) file.length() else 0L
            if (have > r.apkSize) { file.delete(); have = 0 }
            if (have == r.apkSize) return file
            if (dir.usableSpace < (r.apkSize - have) + SPACE_MARGIN) throw NoSpace()
            _state.value = UpdateState.Downloading(r, have, r.apkSize)
            notifier.progress(r, 0)
            val req = Request.Builder()
                .url(r.apkUrl)
                .header("User-Agent", Releases.userAgent(BuildConfig.VERSION_NAME))
                // The file's own bytes: OkHttp's transparent gzip would break sizes and ranges.
                .header("Accept-Encoding", "identity")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            val restart = http.newCall(req).also { call = it }.execute().use { resp ->
                val append = when {
                    resp.code == 416 -> null
                    resp.code == 206 && have > 0 && resp.header("Content-Range")?.startsWith("bytes $have-") == true -> true
                    resp.code == 206 -> null
                    resp.code == 200 -> false
                    else -> throw IOException("HTTP ${resp.code}")
                }
                if (append == null) {
                    file.delete()
                    return@use true
                }
                var done = if (append) have else 0L
                FileOutputStream(file, append).use { out ->
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var shown = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (done + n > r.apkSize) throw TooLong()
                            out.write(buf, 0, n)
                            done += n
                            val pct = ((done * 100) / r.apkSize).toInt()
                            if (pct != shown) {
                                shown = pct
                                _state.value = UpdateState.Downloading(r, done, r.apkSize)
                                notifier.progress(r, pct)
                            }
                        }
                    }
                }
                false
            }
            if (restart) continue
            if (file.length() == r.apkSize) return file
            throw IOException("download ended at ${file.length()} of ${r.apkSize}")
        }
        throw IOException("server would not send the file")
    }

    /** Size, digest and the package checks of [checkApk], on the file and on the installed app. */
    private fun verify(r: Release, file: File): Refusal? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file)
        val mine = installedInfo(pm)
        val facts = ApkFacts(
            size = file.length(),
            sha256 = sha256(file),
            packageName = archive?.packageName,
            versionCode = archive?.longVersionCode,
            signers = archive?.signers(),
        )
        val expected = Expected(
            size = r.apkSize,
            sha256 = r.sha256,
            packageName = context.packageName,
            installedVersionCode = mine.longVersionCode,
            installedSigners = mine.signers().orEmpty(),
        )
        return checkApk(expected, facts)
    }

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** The certificates that signed the APK's contents, as hex; null when Android read none. */
    private fun PackageInfo.signers(): Set<String>? =
        signingInfo?.apkContentsSigners?.map { hex(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private companion object {
        const val TAG = "Updater"
        /** Room left on the disk after the download, for the installer's own copy to start. */
        const val SPACE_MARGIN = 16L * 1024 * 1024
    }
}
