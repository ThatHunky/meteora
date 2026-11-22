package dev.thathunky.meteora.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dagger.hilt.android.AndroidEntryPoint
import dev.thathunky.meteora.MainActivity
import dev.thathunky.meteora.data.PlaybackRouter
import dev.thathunky.meteora.data.PlaybackTarget
import dev.thathunky.meteora.domain.TrackState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the media session for whichever target is active:
 * - PC: a [RemotePlayer] mirroring YTMDesktop. Never "pause on stop" here: pausing our player
 *   pauses the PC. After [IDLE_TIMEOUT_MS] paused it lets go of the connection and stops (Q15).
 * - Phone: the [LocalEngine]'s ExoPlayer directly; Media3 runs the foreground service while it plays.
 * The session's player is swapped when the target changes.
 */
@UnstableApi
@AndroidEntryPoint
class MeteoraSessionService : MediaSessionService() {

    @Inject lateinit var router: PlaybackRouter

    private val scope = MainScope()
    private var session: MediaSession? = null
    private var remote: RemotePlayer? = null
    private var idleJob: Job? = null
    private var holdingPc = false

    override fun onCreate() {
        super.onCreate()
        val remote = RemotePlayer(router.pc).also { this.remote = it }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val initial = if (router.isPhone) router.local.player else remote
        session = MediaSession.Builder(this, initial)
            .setSessionActivity(open)
            .setCallback(Buttons())
            .setMediaButtonPreferences(SessionButtons.buttons(this, SessionButtons.shown(router.state.value)))
            .build()

        // Like, dislike, shuffle and repeat follow the state of whichever target is playing.
        scope.launch {
            router.state.map(SessionButtons::shown).distinctUntilChanged().collect { shown ->
                session?.setMediaButtonPreferences(SessionButtons.buttons(this@MeteoraSessionService, shown))
            }
        }

        scope.launch {
            router.target.collect { t ->
                val s = session ?: return@collect
                idleJob?.cancel()
                idleJob = null
                if (t == PlaybackTarget.Phone) {
                    if (s.player !== router.local.player) s.player = router.local.player
                    if (holdingPc) { router.pc.release(); holdingPc = false }
                } else {
                    if (s.player !== remote) s.player = remote
                    if (!holdingPc) { router.pc.acquire(); holdingPc = true }
                }
            }
        }
        scope.launch {
            router.pc.state.collect { s ->
                remote.update(s)
                if (router.isPhone) return@collect
                val active = s?.trackState == TrackState.Playing || s?.trackState == TrackState.Buffering
                if (active) {
                    idleJob?.cancel()
                    idleJob = null
                } else if (idleJob == null) {
                    idleJob = scope.launch {
                        delay(IDLE_TIMEOUT_MS)
                        stopSelf()
                    }
                }
            }
        }
    }

    /** Lets every controller use the four extra buttons, and turns a press into the usual [Command]. */
    private inner class Buttons : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().addSessionCommands(SessionButtons.commands).build(),
                )
                .setMediaButtonPreferences(SessionButtons.buttons(this@MeteoraSessionService, SessionButtons.shown(router.state.value)))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val command = SessionButtons.commandFor(customCommand.customAction, router.state.value)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            router.send(command)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away while nothing plays ends the session right away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = session?.player?.playWhenReady == true
        if (!playing) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        remote?.release()
        if (holdingPc) router.pc.release()
        // The ExoPlayer belongs to LocalEngine (a singleton) and outlives the service.
        if (router.isPhone) router.local.pause()
        super.onDestroy()
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
