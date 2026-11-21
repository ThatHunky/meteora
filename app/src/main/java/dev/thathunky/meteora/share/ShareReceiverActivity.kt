package dev.thathunky.meteora.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.thathunky.meteora.R
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.data.MusicActions
import dev.thathunky.meteora.data.PlayerRepository
import dev.thathunky.meteora.domain.PlayIntent
import dev.thathunky.meteora.ytm.MusicItem
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Share → Meteora" from YouTube Music / YouTube (and opening their links): plays the target
 * on the PC without opening the app. Invisible; shows a toast and finishes.
 */
@AndroidEntryPoint
@androidx.media3.common.util.UnstableApi
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var player: PlayerRepository
    @Inject lateinit var actions: MusicActions
    @Inject lateinit var router: dev.thathunky.meteora.data.PlaybackRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val target = text?.let(ShareLink::parse)
        if (target == null) {
            toast(R.string.share_unsupported)
            finish()
            return
        }
        if (router.isPhone) {
            // Phone playback needs the app's session service; open the app instead of playing headless.
            toast(R.string.share_phone_mode)
            finish()
            return
        }
        player.acquire()
        lifecycleScope.launch {
            // Wait briefly for the PC; commands go through the normal throttled path.
            val connected = withTimeoutOrNull(6_000) {
                player.connection.first { it is ConnectionState.Connected || it == ConnectionState.Unpaired || it == ConnectionState.Unauthorized }
            }
            if (connected !is ConnectionState.Connected) {
                toast(R.string.share_no_pc)
            } else {
                when (target) {
                    is ShareTarget.Video ->
                        if (target.playlistId != null) player.play(PlayIntent.Collection(target.playlistId, target.videoId))
                        else player.play(PlayIntent.SongRadio(target.videoId))
                    is ShareTarget.Playlist -> player.play(PlayIntent.Collection(target.playlistId))
                    is ShareTarget.Album -> actions.play(MusicItem.Album(target.browseId, "", "", null, null))
                    is ShareTarget.Artist -> actions.play(MusicItem.Artist(target.browseId, "", "", null))
                }
                toast(R.string.share_playing, connected.pcName)
                // Give the throttler / album lookup a moment before releasing the session.
                kotlinx.coroutines.delay(4_000)
            }
            player.release()
            finish()
        }
    }

    private fun toast(res: Int, vararg args: Any) =
        Toast.makeText(applicationContext, getString(res, *args), Toast.LENGTH_SHORT).show()
}
