package dev.thathunky.meteora

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import dev.thathunky.meteora.ui.theme.MeteoraTheme
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
            val playing by vm.playback.collectAsStateWithLifecycle()
            // Ask once there's something the notification would control.
            val wantsNotification = connection is ConnectionState.Connected || playing?.track != null
            LaunchedEffect(wantsNotification) {
                if (wantsNotification) askForNotifications()
            }
            MeteoraTheme(albumSeed = seed) {
                MeteoraShell(vm)
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
