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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.ui.platform.LocalDensity
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.MusicItem
import kotlinx.coroutines.launch

fun MusicItem.subtitleText(): String = when (this) {
    is MusicItem.Song -> listOfNotNull(artists.joinToString(", ") { it.name }.ifBlank { null }, album?.name, duration).joinToString(" · ")
    is MusicItem.Album -> subtitle
    is MusicItem.Artist -> subtitle
    is MusicItem.Playlist -> subtitle
}

/**
 * The subtitle with the kind in front ("Song • …"), for places where kinds are mixed: a tap on
 * a song plays it, a tap on an album opens it, and the two look alike. Albums, singles and
 * playlists already name their kind in the subtitle YouTube sends.
 */
@Composable
fun MusicItem.typedSubtitle(): String = when (this) {
    is MusicItem.Song -> listOf(stringResource(if (isVideo) R.string.type_video else R.string.type_song), subtitleText())
        .filter { it.isNotBlank() }.joinToString(" • ")
    else -> subtitleText()
}

/** A list row for any item. Tap: songs play, collections open. Long-press: actions sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicRow(item: MusicItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, showType: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    MRow(
        headline = item.title,
        supporting = if (showType) item.typedSubtitle() else item.subtitleText(),
        leading = {
            ArtworkFrame(
                item.thumbnail,
                when (item) {
                    is MusicItem.Artist -> ArtKind.Artist
                    is MusicItem.Album -> ArtKind.Album
                    else -> ArtKind.Song
                },
                Modifier.size(LocalLook.current.rowArt),
            )
        },
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
        ArtworkFrame(item.thumbnail, if (item is MusicItem.Artist) ArtKind.Artist else ArtKind.Card, Modifier.size(152.dp))
        Spacer(Modifier.height(8.dp))
        // A fixed height for two title lines and the subtitle: a carousel is as tall as its
        // tallest visible card, so cards of different heights made everything below jump while
        // it scrolled sideways. The subtitle sits right under the title; spare room goes below it.
        val titleStyle = MaterialTheme.typography.titleSmallEmphasized
        val subStyle = MaterialTheme.typography.bodySmall
        val block = with(LocalDensity.current) { titleStyle.lineHeight.toDp() * 2 + subStyle.lineHeight.toDp() }
        Column(Modifier.height(block)) {
            Text(item.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.typedSubtitle(),
                style = subStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    ItemAction.Download -> R.string.action_download to Icons.Outlined.Download
    ItemAction.RemoveDownload -> R.string.action_remove_download to Icons.Outlined.Delete
}

@Composable
fun ItemActionsSheet(item: MusicItem, onAction: (ItemAction) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    // ListItem paints the surface colour by default, which showed as bands across the sheet's own
    // (container) colour, starkest on pure black. Transparent rows take the sheet's colour.
    val rowColors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        ListItem(
            headlineContent = { Text(item.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(item.typedSubtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                ArtworkFrame(item.thumbnail, ArtKind.Sheet, Modifier.size(56.dp))
            },
            colors = rowColors,
        )
        itemActions(item, dev.thathunky.meteora.ui.downloads.rememberDownloaded(item)).forEach { action ->
            val (label, icon) = action.label()
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { Icon(icon, null) },
                colors = rowColors,
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
