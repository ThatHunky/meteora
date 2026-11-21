package dev.thathunky.meteora

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.data.PlaybackRouter
import dev.thathunky.meteora.playback.MeteoraSessionService
import dev.thathunky.meteora.playback.RemotePlayer
import dev.thathunky.meteora.ui.shell.MeteoraShell
import dev.thathunky.meteora.ui.shell.ShellViewModel
import dev.thathunky.meteora.ui.settings.AppearanceViewModel
import dev.thathunky.meteora.ui.theme.LocalAlbumSeed
import dev.thathunky.meteora.ui.theme.LocalArtworkShape
import dev.thathunky.meteora.ui.theme.MeteoraTheme
import dev.thathunky.meteora.ui.theme.isDark
import dev.thathunky.meteora.ui.theme.themeById
import javax.inject.Inject

// AppCompatActivity (not ComponentActivity) so per-app locales work on Android 12.
@UnstableApi
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var router: PlaybackRouter

    private var controller: ListenableFuture<MediaController>? = null
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ShellViewModel = hiltViewModel()
            val seed by vm.albumSeed.collectAsStateWithLifecycle()
            val connection by vm.connection.collectAsStateWithLifecycle()
            val hasTrack by vm.hasTrack.collectAsStateWithLifecycle()
            // Ask once there's something the notification would control.
            val wantsNotification = connection is ConnectionState.Connected || hasTrack
            LaunchedEffect(wantsNotification) {
                if (wantsNotification) askForNotifications()
            }
            val appearance: AppearanceViewModel = hiltViewModel()
            val themeId by appearance.themeId.collectAsStateWithLifecycle()
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val pureBlack by appearance.pureBlack.collectAsStateWithLifecycle()
            val artworkShape by appearance.artworkShape.collectAsStateWithLifecycle()
            val theme = themeById(themeId)
            val dark = isDark(theme, mode, isSystemInDarkTheme())
            // Bar icons follow the app's theme: a dark theme on a light phone needs light icons.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalArtworkShape provides artworkShape, LocalAlbumSeed provides seed) {
                MeteoraTheme(theme = theme, mode = mode, pureBlack = pureBlack, albumSeed = seed) {
                    MeteoraShell(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the session service; it stays foreground while the PC plays.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, MeteoraSessionService::class.java)))
            .buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    /**
     * When the PC is the target, volume keys in the app drive the PC (outside it, the remote
     * session does). On the phone they fall through to the system as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = router.pc
        val connected = !router.isPhone && player.connection.value is ConnectionState.Connected
        val vol = player.state.value?.volume
        if (connected && vol != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) RemotePlayer.VOLUME_STEP else -RemotePlayer.VOLUME_STEP
                pendingVolume = ((pendingVolume ?: vol) + step).coerceIn(0, 100)
                player.send(Command.SetVolume(pendingVolume!!))
            } else if (event.action == KeyEvent.ACTION_UP) {
                pendingVolume = null
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Accumulates while a key is held so repeats don't all start from the stale PC volume. */
    private var pendingVolume: Int? = null

    private fun askForNotifications() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
