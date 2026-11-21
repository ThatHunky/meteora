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
    }
}
