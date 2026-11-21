package dev.thathunky.meteora.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.thathunky.meteora.R
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.data.ConnectionState
import dev.thathunky.meteora.domain.PlaybackState

/** M1 "done when" debug controls. Replaced by the real player in M2. */
@Composable
fun DebugPlayerCard(
    connection: ConnectionState,
    state: PlaybackState?,
    send: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(connection.toString(), style = MaterialTheme.typography.labelSmall)
            Text(state?.track?.title ?: "—", style = MaterialTheme.typography.titleLarge)
            Text(state?.track?.artist.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { send(Command.Previous) }) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_previous))
                }
                FilledIconButton(
                    onClick = { send(Command.PlayPause) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.padding(4.dp),
                ) {
                    val playing = state?.isPlaying == true
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    )
                }
                IconButton(onClick = { send(Command.Next) }) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next))
                }
            }
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: (state?.volume ?: 0).toFloat(),
                onValueChange = { dragging = it; send(Command.SetVolume(it.toInt())) },
                onValueChangeFinished = { dragging = null },
                valueRange = 0f..100f,
            )
        }
    }
}
