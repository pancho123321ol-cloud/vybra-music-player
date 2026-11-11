package com.vybra.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.playback.QueueEntry
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.PlayingBars
import com.vybra.music.ui.components.SectionHeader
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Cola de reproducción editable: reordenar, quitar y saltar a una canción. */
@Composable
fun QueueSheet() {
    val ui = LocalUi.current
    if (!ui.showQueue) return
    val connection = LocalContext.current.appContainer.player
    val state by connection.state.collectAsStateWithLifecycle()
    val currentPos = state.queue.indexOfFirst { it.index == state.currentIndex }
    val current = state.queue.getOrNull(currentPos)
    var upcoming by remember(state.queue, state.currentIndex) {
        mutableStateOf(if (currentPos >= 0) state.queue.drop(currentPos + 1) else state.queue)
    }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = upcoming.indexOfFirst { it.index == from.key }
        val toIdx = upcoming.indexOfFirst { it.index == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            upcoming = upcoming.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { ui.showQueue = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cola de reproducción", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (upcoming.isNotEmpty()) {
                    TextButton(onClick = { connection.clearUpcoming() }) { Text("Limpiar") }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (current != null) {
                    item(key = "header_now") { SectionHeader("Sonando ahora") }
                    item(key = "now") { QueueRow(current, isCurrent = true, onClick = {}, trailing = {}) }
                }
                item(key = "header_next") {
                    Column {
                        SectionHeader(if (upcoming.isEmpty()) "No hay más canciones en la cola" else "A continuación")
                        if (state.shuffle && upcoming.isNotEmpty()) {
                            Text(
                                "Modo aleatorio activo: desactívalo para reordenar la cola.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
                items(upcoming, key = { it.index }) { entry ->
                    ReorderableItem(reorderState, key = entry.index, enabled = !state.shuffle) { isDragging ->
                        QueueRow(
                            entry,
                            isCurrent = false,
                            modifier = if (isDragging) {
                                Modifier.shadow(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            } else Modifier,
                            onClick = { connection.skipToIndex(entry.index) },
                            trailing = {
                                IconButton(onClick = { connection.removeFromQueue(entry.index) }) {
                                    Icon(Icons.Rounded.Close, "Quitar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!state.shuffle) {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = { draggedIndex = entry.index },
                                            onDragStopped = {
                                                val original = draggedIndex
                                                draggedIndex = null
                                                if (original != null && state.currentIndex >= 0) {
                                                    val newPos = upcoming.indexOfFirst { it.index == original }
                                                    val target = state.currentIndex + 1 + newPos
                                                    if (newPos >= 0 && target != original) connection.moveInQueue(original, target)
                                                }
                                            },
                                        ),
                                    ) {
                                        Icon(Icons.Rounded.DragHandle, "Reordenar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val np = entry.nowPlaying
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(np.songId, np.albumId, Modifier.size(46.dp), RoundedCornerShape(6.dp), 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                np.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                np.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) PlayingBars(true, Modifier.padding(end = 16.dp))
        trailing()
    }
}
