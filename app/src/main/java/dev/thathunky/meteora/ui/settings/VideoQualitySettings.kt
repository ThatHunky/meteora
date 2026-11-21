package dev.thathunky.meteora.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.stream.StreamQualityStore
import dev.thathunky.meteora.stream.VideoQuality
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.SegmentedChoice
import javax.inject.Inject

@HiltViewModel
class VideoQualityViewModel @Inject constructor(private val store: StreamQualityStore) : ViewModel() {
    val wifi = store.videoWifi
    val mobile = store.videoMobile
    fun setWifi(q: VideoQuality) = store.setVideoWifi(q)
    fun setMobile(q: VideoQuality) = store.setVideoMobile(q)
}

/**
 * Settings → Playback, next to the audio quality: the picture's ceiling on Wi-Fi and on mobile
 * data (720p and 360p by default). Meant to sit right after `quality_note` in
 * `PlaybackSettingsScreen` (ui/shell/SettingsScreens.kt); not called from there yet.
 */
@Composable
fun ColumnScope.VideoQualitySection(vm: VideoQualityViewModel = hiltViewModel()) {
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val mobile by vm.mobile.collectAsStateWithLifecycle()
    val options = listOf(
        Choice(VideoQuality.Low, stringResource(R.string.video_quality_low)),
        Choice(VideoQuality.Normal, stringResource(R.string.video_quality_normal)),
        Choice(VideoQuality.High, stringResource(R.string.video_quality_high)),
        Choice(VideoQuality.Highest, stringResource(R.string.video_quality_highest)),
    )
    Text(stringResource(R.string.video_quality), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.quality_wifi), style = MaterialTheme.typography.labelLarge)
    SegmentedChoice(options, wifi, vm::setWifi, Modifier.fillMaxWidth())
    Text(stringResource(R.string.quality_mobile), style = MaterialTheme.typography.labelLarge)
    SegmentedChoice(options, mobile, vm::setMobile, Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.video_quality_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
