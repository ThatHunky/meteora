package dev.thathunky.meteora.stream

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Audio quality tier for playback on the phone, named like YouTube Music's own setting. */
enum class StreamQuality {
    Low, Normal, High;

    companion object {
        /** Stored name → tier. Also reads the names used before the rename. */
        fun fromStored(name: String?): StreamQuality? = when (name) {
            "DataSaver" -> Low
            "Balanced" -> Normal
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** Video picture ceiling, separate from the audio tier: the sound never changes with it. */
enum class VideoQuality(val maxHeight: Int) {
    Low(360), Normal(480), High(720), Highest(1080);

    companion object {
        fun fromStored(name: String?): VideoQuality? = entries.firstOrNull { it.name == name }
    }
}

/** The user's quality choice for Wi-Fi and for mobile data. */
@Singleton
class StreamQualityStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _wifi = MutableStateFlow(read(KEY_WIFI, StreamQuality.High))
    val wifi: StateFlow<StreamQuality> = _wifi.asStateFlow()

    // Normal by default on mobile data, as in the official app.
    private val _mobile = MutableStateFlow(read(KEY_MOBILE, StreamQuality.Normal))
    val mobile: StateFlow<StreamQuality> = _mobile.asStateFlow()

    fun setWifi(q: StreamQuality) {
        _wifi.value = q
        prefs.edit().putString(KEY_WIFI, q.name).apply()
    }

    fun setMobile(q: StreamQuality) {
        _mobile.value = q
        prefs.edit().putString(KEY_MOBILE, q.name).apply()
    }

    /** "Mobile data" is any metered network, so a metered Wi-Fi hotspot counts too. */
    fun current(): StreamQuality = if (metered()) mobile.value else wifi.value

    private val _videoWifi = MutableStateFlow(VideoQuality.fromStored(prefs.getString(KEY_VIDEO_WIFI, null)) ?: VideoQuality.High)
    val videoWifi: StateFlow<VideoQuality> = _videoWifi.asStateFlow()

    private val _videoMobile = MutableStateFlow(VideoQuality.fromStored(prefs.getString(KEY_VIDEO_MOBILE, null)) ?: VideoQuality.Low)
    val videoMobile: StateFlow<VideoQuality> = _videoMobile.asStateFlow()

    fun setVideoWifi(q: VideoQuality) {
        _videoWifi.value = q
        prefs.edit().putString(KEY_VIDEO_WIFI, q.name).apply()
    }

    fun setVideoMobile(q: VideoQuality) {
        _videoMobile.value = q
        prefs.edit().putString(KEY_VIDEO_MOBILE, q.name).apply()
    }

    /** Video ceiling for the network in use: 720p on Wi-Fi and 360p on mobile data by default. */
    /** Sets the ceiling for the network in use now (the picker on the picture). */
    fun setCurrentVideo(q: VideoQuality) = if (metered()) setVideoMobile(q) else setVideoWifi(q)

    fun currentVideo(): VideoQuality = if (metered()) videoMobile.value else videoWifi.value

    private fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    private fun read(key: String, default: StreamQuality): StreamQuality =
        StreamQuality.fromStored(prefs.getString(key, null)) ?: default

    private companion object {
        const val KEY_WIFI = "quality_wifi"
        const val KEY_MOBILE = "quality_mobile"
        const val KEY_VIDEO_WIFI = "video_quality_wifi"
        const val KEY_VIDEO_MOBILE = "video_quality_mobile"
    }
}
