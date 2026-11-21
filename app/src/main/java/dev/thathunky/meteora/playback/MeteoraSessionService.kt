package dev.thathunky.meteora.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.thathunky.meteora.MainActivity
import dev.thathunky.meteora.data.PlayerRepository
import dev.thathunky.meteora.domain.TrackState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Owns the media session while Meteora is connected and the PC is (or was recently) playing.
 * Bound by the activity's MediaController; Media3 promotes it to a foreground service while
 * the [RemotePlayer] reports playing. After [IDLE_TIMEOUT_MS] paused it lets go of the
 * connection and stops (PLAN.md Q15). It can't restart itself from the background (Android 12+),
 * so controls come back the next time the app is opened.
 */
@UnstableApi
@AndroidEntryPoint
class MeteoraSessionService : MediaSessionService() {

    @Inject lateinit var repo: PlayerRepository

    private val scope = MainScope()
    private var session: MediaSession? = null
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = RemotePlayer(repo)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        repo.acquire()

        scope.launch {
            repo.state.collect { s ->
                player.update(s)
                val active = s?.trackState == TrackState.Playing || s?.trackState == TrackState.Buffering
                if (active) {
                    idleJob?.cancel()
                    idleJob = null
                } else if (idleJob == null) {
                    idleJob = scope.launch {
                        delay(IDLE_TIMEOUT_MS)
                        stopEverything()
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away while the PC is paused ends the session right away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = session?.player?.playWhenReady == true
        if (!playing) stopEverything()
    }

    /** Never pauseAllPlayersAndStopSelf(): "pausing" our player would pause the PC. */
    private fun stopEverything() {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        repo.release()
        super.onDestroy()
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
