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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.PaddingValues
import dev.thathunky.meteora.ui.components.ArtKind
import dev.thathunky.meteora.ui.components.ArtworkFrame
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.SectionHeader
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import dev.thathunky.meteora.domain.PlaybackState
import dev.thathunky.meteora.domain.QueueEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The PC's queue. Drag to reorder (one queueMove on drop), swipe to remove, tap to jump. */
@Composable
fun QueueSheet(s: PlaybackState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = LocalHapticFeedback.current
    val look = LocalLook.current

    // Local copy so the list moves under the finger; re-synced from every PC update.
    var items by remember { mutableStateOf(s.queue.withIndex().toList()) }
    var dragFrom by remember { mutableStateOf<IndexedValue<QueueEntry>?>(null) }
    LaunchedEffect(s.queue) { if (dragFrom == null) items = s.queue.withIndex().toList() }
    // For the drop: a queue update that came in during the drag was skipped above.
    val queueNow by rememberUpdatedState(s.queue)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.selectedIndex - 1).coerceAtLeast(0))
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val nowPlaying = stringResource(R.string.queue_now_playing)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        SectionHeader(stringResource(R.string.queue), padding = PaddingValues(horizontal = look.screenPadding + 8.dp, vertical = 8.dp))
        if (items.isEmpty() && s.automix.isEmpty()) {
            Text(
                stringResource(R.string.queue_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = look.screenPadding + 8.dp, vertical = 32.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, it -> "${it.index}:${it.value.videoId}" }) { position, entry ->
                ReorderableItem(reorder, key = "${entry.index}:${entry.value.videoId}") { isDragging ->
                    val dismiss = rememberSwipeToDismissBoxState()
                    // The PC may refuse the removal (the queue comes back unchanged): put the row back.
                    LaunchedEffect(dismiss.currentValue) {
                        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
                            delay(2_000)
                            dismiss.reset()
                        }
                    }
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
                            // Only while the row is being swiped: at rest nothing red may sit behind it.
                            if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer) }
                        },
                    ) {
                        val current = entry.index == s.selectedIndex
                        MRow(
                            headline = entry.value.title,
                            supporting = "${entry.value.artist} · ${entry.value.duration}",
                            leading = {
                                ArtworkFrame(entry.value.artworkUrl, ArtKind.Queue, Modifier.size(if (look.id == LookId.Expressive) 48.dp else look.rowArt), current = current)
                            },
                            trailing = {
                                MIconButton(
                                    Icons.Outlined.DragHandle,
                                    stringResource(R.string.reorder),
                                    onClick = {},
                                    modifier = Modifier
                                        .draggableHandle(
                                            onDragStarted = {
                                                dragFrom = entry
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                val from = dragFrom
                                                dragFrom = null
                                                val to = items.indexOfFirst { it === from }
                                                if (from != null && to >= 0 && to != from.index) vm.move(from.value.videoId, from.index, to)
                                                // Dropped in place: nothing will come back from the PC, so catch up now.
                                                else items = queueNow.withIndex().toList()
                                            },
                                        )
                                        // A button that does nothing on a tap; TalkBack moves rows with the row's actions instead.
                                        .clearAndSetSemantics { },
                                )
                            },
                            highlight = when {
                                isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                current -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            marked = current,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Opaque in every look. Terminal's rows have no fill of their own, so the
                                // red swipe background showed through every row of the queue.
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { vm.playAt(entry.value.videoId, position) }
                                .semantics {
                                    if (current) stateDescription = nowPlaying
                                    // Only while nothing is being dragged: the indexes are the PC's then.
                                    if (dragFrom == null) {
                                        customActions = buildList {
                                            if (entry.index > 0) add(CustomAccessibilityAction(moveUp) { vm.move(entry.value.videoId, entry.index, entry.index - 1); true })
                                            if (entry.index < items.lastIndex) add(CustomAccessibilityAction(moveDown) { vm.move(entry.value.videoId, entry.index, entry.index + 1); true })
                                        }
                                    }
                                },
                        )
                    }
                }
            }
            if (s.automix.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.automix), padding = PaddingValues(start = look.screenPadding + 8.dp, end = look.screenPadding + 8.dp, top = 20.dp, bottom = 8.dp))
                }
                itemsIndexed(s.automix, key = { i, it -> "automix:$i:${it.videoId}" }) { _, e ->
                    MRow(
                        headline = e.title,
                        supporting = e.artist,
                        // The queue rows' size above: a fixed 40 dp was smaller than Soft's 60 dp
                        // queue art and larger than Terminal's 36.
                        leading = { ArtworkFrame(e.artworkUrl, ArtKind.Small, Modifier.size(if (look.id == LookId.Expressive) 48.dp else look.rowArt)) },
                        highlight = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                }
            }
        }
    }
}
