package dev.thathunky.meteora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.thathunky.meteora.R
import dev.thathunky.meteora.playback.NerdStats
import dev.thathunky.meteora.stream.VideoQuality
import kotlinx.coroutines.delay

/** The gear on the picture: quality for this network, and the stats overlay. */
@UnstableApi
@Composable
fun VideoOptionsButton(vm: VideoViewModel, open: Boolean, onOpenChange: (Boolean) -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var quality by remember(open) { mutableStateOf(vm.quality()) }
    IconButton(onClick = { onOpenChange(true) }, modifier) {
        Icon(Icons.Outlined.Settings, stringResource(R.string.action_video_options), tint = tint)
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            VideoQuality.entries.reversed().forEach { q ->
                DropdownMenuItem(
                    text = { Text(stringResource(q.label())) },
                    trailingIcon = { if (q == quality) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        quality = q
                        vm.setQuality(q)
                        onOpenChange(false)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stats_for_nerds)) },
                trailingIcon = { if (stats) Icon(Icons.Filled.Check, null) },
                onClick = {
                    vm.stats.value = !stats
                    onOpenChange(false)
                },
            )
        }
    }
}

private fun VideoQuality.label() = when (this) {
    VideoQuality.Low -> R.string.video_quality_low
    VideoQuality.Normal -> R.string.video_quality_normal
    VideoQuality.High -> R.string.video_quality_high
    VideoQuality.Highest -> R.string.video_quality_highest
}

/**
 * What is actually playing, refreshed once a second while shown. The labels are technical terms
 * and stay in English, as the field names do in YouTube's own panel.
 */
@UnstableApi
@Composable
fun NerdStatsOverlay(vm: VideoViewModel, modifier: Modifier = Modifier) {
    val on by vm.stats.collectAsStateWithLifecycle()
    if (!on) return
    var stats by remember { mutableStateOf<NerdStats?>(vm.nerdStats()) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = vm.nerdStats()
            delay(1_000)
        }
    }
    val s = stats ?: return
    Column(modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        s.lines().forEach { (label, value) ->
            Text("$label  $value", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
        }
    }
}
