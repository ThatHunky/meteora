package dev.thathunky.meteora.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ytm.MusicItem
import kotlinx.coroutines.launch

fun MusicItem.subtitleText(): String = when (this) {
    is MusicItem.Song -> listOfNotNull(artists.joinToString(", ") { it.name }.ifBlank { null }, album?.name, duration).joinToString(" · ")
    is MusicItem.Album -> subtitle
    is MusicItem.Artist -> subtitle
    is MusicItem.Playlist -> subtitle
}

/** A list row for any item. Tap: songs play, collections open. Long-press: actions sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicRow(item: MusicItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.subtitleText(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(
                    when (item) {
                        is MusicItem.Artist -> CircleShape
                        is MusicItem.Album -> MaterialShapes.Cookie9Sided.toShape()
                        else -> RoundedCornerShape(10.dp)
                    },
                ),
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
    )
}

/** A carousel card (Home, artist shelves). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicCard(item: MusicItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier.width(152.dp).combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(152.dp).clip(if (item is MusicItem.Artist) CircleShape else RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            item.subtitleText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ItemAction.label(): Pair<Int, ImageVector> = when (this) {
    ItemAction.Play -> R.string.action_play to Icons.Filled.PlayArrow
    ItemAction.Shuffle -> R.string.shuffle to Icons.Outlined.Shuffle
    ItemAction.PlayNext -> R.string.action_play_next to Icons.AutoMirrored.Outlined.QueueMusic
    ItemAction.AddToQueue -> R.string.action_add_to_queue to Icons.AutoMirrored.Outlined.PlaylistAdd
    ItemAction.Radio -> R.string.action_radio to Icons.Outlined.Radio
    ItemAction.GoToAlbum -> R.string.action_go_album to Icons.Outlined.Album
    ItemAction.GoToArtist -> R.string.action_go_artist to Icons.Outlined.Person
    ItemAction.Share -> R.string.action_share to Icons.Outlined.Share
}

@Composable
fun ItemActionsSheet(item: MusicItem, onAction: (ItemAction) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        ListItem(
            headlineContent = { Text(item.title, style = MaterialTheme.typography.titleMediumEmphasized) },
            supportingContent = { Text(item.subtitleText(), maxLines = 1) },
            leadingContent = {
                AsyncImage(item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            },
        )
        itemActions(item).forEach { action ->
            val (label, icon) = action.label()
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { Icon(icon, null) },
                modifier = Modifier.combinedClickable(onClick = {
                    onAction(action)
                    // Slide the sheet away before removing it, instead of cutting it off.
                    scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
                }),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
