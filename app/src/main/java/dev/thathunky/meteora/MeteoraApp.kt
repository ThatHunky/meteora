package dev.thathunky.meteora

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.stream.StreamProbe
import dev.thathunky.meteora.stream.StreamResolver
import dev.thathunky.meteora.ytm.YtmAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class MeteoraApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProbeDeps {
        fun okHttp(): OkHttpClient
        fun auth(): YtmAuthProvider
        fun resolver(): StreamResolver
        fun ytm(): dev.thathunky.meteora.ytm.YtmClient
        fun premium(): dev.thathunky.meteora.ytm.PremiumCheck
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) registerProbe()
    }

    /** Debug builds only: lets adb run [StreamProbe] (see its doc for the command). */
    private fun registerProbe() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val videoId = intent.getStringExtra("v") ?: return
                val deps = EntryPointAccessors.fromApplication(this@MeteoraApp, ProbeDeps::class.java)
                CoroutineScope(Dispatchers.IO).launch { StreamProbe(deps.okHttp(), deps.auth(), deps.resolver().webMusic).run(videoId) }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter("dev.thathunky.meteora.PROBE"), ContextCompat.RECEIVER_EXPORTED)
        // adb shell am broadcast -a dev.thathunky.meteora.PROBE_LIKES -p dev.thathunky.meteora [--es v <videoId>] [--es list <playlistId>]
        val likes = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val deps = EntryPointAccessors.fromApplication(this@MeteoraApp, ProbeDeps::class.java)
                val v = intent.getStringExtra("v")
                val list = intent.getStringExtra("list")
                CoroutineScope(Dispatchers.IO).launch {
                    // --es menus home | --es menus <videoId>: what the item menus offer.
                    val menus = intent.getStringExtra("menus")
                    // --es video <videoId>: how the queue pairs songs with their videos.
                    val video = intent.getStringExtra("video")
                    if (video != null) dev.thathunky.meteora.ytm.LikeProbe.video(deps.ytm(), video)
                    else if (menus != null) dev.thathunky.meteora.ytm.LikeProbe.menus(deps.ytm(), menus.takeIf { it != "home" })
                    else dev.thathunky.meteora.ytm.LikeProbe.run(deps.ytm(), v, list)
                }
            }
        }
        ContextCompat.registerReceiver(this, likes, IntentFilter("dev.thathunky.meteora.PROBE_LIKES"), ContextCompat.RECEIVER_EXPORTED)
        // adb shell am broadcast -a dev.thathunky.meteora.PROBE_PREMIUM -p dev.thathunky.meteora ; adb logcat -s PremiumCheck
        val premium = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val deps = EntryPointAccessors.fromApplication(this@MeteoraApp, ProbeDeps::class.java)
                CoroutineScope(Dispatchers.IO).launch { deps.premium().probe() }
            }
        }
        ContextCompat.registerReceiver(this, premium, IntentFilter("dev.thathunky.meteora.PROBE_PREMIUM"), ContextCompat.RECEIVER_EXPORTED)
    }
}
