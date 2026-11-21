package dev.thathunky.meteora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.thathunky.meteora.R
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.QueueEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The PC's queue. Drag to reorder (one queueMove on drop), swipe to remove, tap to jump. */
@Composable
fun QueueSheet(s: PlaybackState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = LocalHapticFeedback.current

    // Local copy so the list moves under the finger; re-synced from every PC update.
    var items by remember { mutableStateOf(s.queue.withIndex().toList()) }
    var dragFrom by remember { mutableStateOf<IndexedValue<QueueEntry>?>(null) }
    LaunchedEffect(s.queue) { if (dragFrom == null) items = s.queue.withIndex().toList() }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.selectedIndex - 1).coerceAtLeast(0))
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text(
            stringResource(R.string.queue),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, it -> "${it.index}:${it.value.videoId}" }) { position, entry ->
                ReorderableItem(reorder, key = "${entry.index}:${entry.value.videoId}") { isDragging ->
                    val dismiss = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        onDismiss = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                vm.remove(entry.value.videoId, position)
                            }
                        },
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer) }
                        },
                    ) {
                        val current = entry.index == s.selectedIndex
                        ListItem(
                            headlineContent = { Text(entry.value.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${entry.value.artist} · ${entry.value.duration}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                AsyncImage(
                                    model = entry.value.artworkUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(if (current) 24.dp else 8.dp)),
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            dragFrom = entry
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            val from = dragFrom
                                            dragFrom = null
                                            val to = items.indexOfFirst { it === from }
                                            if (from != null && to >= 0 && to != from.index) vm.move(from.value.videoId, from.index, to)
                                        },
                                    ),
                                ) { Icon(Icons.Outlined.DragHandle, stringResource(R.string.reorder)) }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = when {
                                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    current -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                            modifier = Modifier.fillMaxWidth().clickable { vm.playAt(entry.value.videoId, position) },
                        )
                    }
                }
            }
            if (s.automix.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.automix),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                itemsIndexed(s.automix, key = { i, it -> "automix:$i:${it.videoId}" }) { _, e ->
                    ListItem(
                        headlineContent = { Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(e.artist, maxLines = 1) },
                        leadingContent = {
                            AsyncImage(e.artworkUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
    }
}
