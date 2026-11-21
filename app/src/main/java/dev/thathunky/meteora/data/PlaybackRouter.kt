package dev.thathunky.meteora.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.di.AppScope
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.playback.LocalEngine
import dev.thathunky.meteora.playback.LocalSong
import dev.thathunky.meteora.playback.toLocal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PlaybackTarget { Pc, Phone }

/**
 * Where playback happens: the paired PC (YTMDesktop) or this phone. Everything the UI and the
 * media session do goes through here, so both targets share one player UI.
 */
@UnstableApi
@Singleton
class PlaybackRouter @Inject constructor(
    @ApplicationContext context: Context,
    val pc: PlayerRepository,
    val local: LocalEngine,
    @AppScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _target = MutableStateFlow(
        runCatching { PlaybackTarget.valueOf(prefs.getString(KEY_TARGET, null) ?: "") }.getOrDefault(PlaybackTarget.Pc),
    )
    val target: StateFlow<PlaybackTarget> = _target.asStateFlow()

    val state: StateFlow<PlaybackState?> = _target
        .flatMapLatest { if (it == PlaybackTarget.Phone) local.state else pc.state }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val connection: StateFlow<ConnectionState> = combine(_target, pc.connection) { t, c ->
        if (t == PlaybackTarget.Phone) ConnectionState.Local else c
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    val errors = merge(pc.errors, local.errors)

    val isPhone get() = _target.value == PlaybackTarget.Phone

    init {
        if (isPhone) local.prewarm()
    }

    /**
     * Switch target. With [handOff], whatever is playing moves along: the queue and position
     * carry over and the other side pauses.
     */
    fun setTarget(t: PlaybackTarget, handOff: Boolean = true) {
        val from = _target.value
        if (from == t) return
        prefs.edit().putString(KEY_TARGET, t.name).apply()
        val pcState = pc.state.value
        _target.value = t
        if (t == PlaybackTarget.Phone) local.prewarm()
        if (!handOff) return
        scope.launch {
            when (t) {
                PlaybackTarget.Phone -> {
                    val s = pcState ?: return@launch
                    val track = s.track ?: return@launch
                    val wasPlaying = s.isPlaying
                    val songs = s.queue.map { it.toLocal() }.ifEmpty {
                        listOf(LocalSong(track.videoId, track.title, track.artist, track.album, track.albumId, track.artistChannelId, track.durationSec, track.artworkUrl))
                    }
                    val idx = s.selectedIndex.takeIf { it in songs.indices && songs[it].videoId == track.videoId }
                        ?: songs.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
                    val position = (s.progressAt(android.os.SystemClock.elapsedRealtime()) * 1000).toLong()
                    if (wasPlaying) pc.send(Command.Pause)
                    // The PC knows the current track's like status; the rest of the queue stays unknown.
                    val likes = if (track.like == dev.thathunky.meteora.domain.LikeStatus.Unknown) emptyMap() else mapOf(track.videoId to track.like)
                    local.setQueue(songs, idx, position, playWhenReady = wasPlaying, source = s.playlistId, likes = likes)
                }
                PlaybackTarget.Pc -> {
                    val s = local.state.value ?: return@launch
                    val track = s.track ?: return@launch
                    val wasPlaying = s.isPlaying
                    val position = s.progressAt(android.os.SystemClock.elapsedRealtime())
                    local.pause()
                    if (!wasPlaying) return@launch
                    // The PC can't take our queue; play the track in its original context (or its radio).
                    val connected = withTimeoutOrNull(6_000) { pc.connection.first { it is ConnectionState.Connected } }
                    if (connected == null) return@launch
                    val playlist = local.currentPlaylistId
                    // changeVideo on the PC's already-loaded video is a no-op (S1): just resume it.
                    if (pc.state.value?.track?.videoId == track.videoId) pc.send(Command.Play)
                    else pc.send(Command.ChangeVideo(track.videoId, playlist ?: "RDAMVM${track.videoId}"))
                    if (position > 5) {
                        withTimeoutOrNull(8_000) { pc.state.first { it?.track?.videoId == track.videoId && it.isPlaying } }
                        pc.send(Command.SeekTo(position))
                    }
                }
            }
        }
    }

    fun send(command: Command) {
        if (isPhone) local.handle(command) else pc.send(command)
    }

    /** [first]: the tapped song, if known, so the phone can start it before its queue has loaded. */
    fun play(intent: PlayIntent, shuffle: Boolean = false, first: LocalSong? = null) {
        if (isPhone) local.play(intent, shuffle, first) else pc.play(intent)
    }

    fun queueCommandFor(videoId: String, index: Int, build: (Int) -> Command) {
        if (!isPhone) return pc.queueCommandFor(videoId, index, build)
        val q = local.state.value?.queue ?: return
        val i = if (q.getOrNull(index)?.videoId == videoId) index else q.indexOfFirst { it.videoId == videoId }
        if (i >= 0) local.handle(build(i))
    }

    private companion object {
        const val KEY_TARGET = "target"
    }
}
