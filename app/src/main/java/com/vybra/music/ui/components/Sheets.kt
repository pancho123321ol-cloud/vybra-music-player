package com.vybra.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vybra.music.appContainer
import com.vybra.music.data.db.Song
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.common.formatDuration
import com.vybra.music.ui.common.formatSize
import com.vybra.music.ui.common.mimeLabel
import com.vybra.music.ui.common.songCountLabel
import kotlinx.coroutines.launch

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Menú de opciones de una canción. */
@Composable
fun SongOptionsSheet() {
    val ui = LocalUi.current
    val request = ui.songMenu ?: return
    val song = request.song
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val isFavorite by remember(song.id) { container.music.isFavorite(song.id) }.collectAsState(initial = false)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun dismiss() { ui.songMenu = null }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.id, song.albumId, Modifier.size(56.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artist} • ${song.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SheetAction(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavorite) "Quitar de favoritas" else "Añadir a favoritas",
                onClick = {
                    scope.launch {
                        val added = container.music.toggleFavorite(song.id)
                        ui.message(if (added) "Añadida a favoritas" else "Eliminada de favoritas")
                    }
                    dismiss()
                },
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            SheetAction(Icons.Rounded.SkipNext, "Reproducir a continuación", {
                container.player.playNext(listOf(song)); ui.message("Se reproducirá a continuación"); dismiss()
            })
            SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Agregar a la cola", {
                container.player.addToQueue(listOf(song)); ui.message("Agregada a la cola"); dismiss()
            })
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar a una playlist", {
                dismiss(); ui.addToPlaylist = listOf(song)
            })
            if (request.playlistId != null) {
                SheetAction(Icons.Rounded.PlaylistRemove, "Quitar de esta playlist", {
                    scope.launch { container.playlists.removeSong(request.playlistId, song.id) }
                    ui.message("Eliminada de la playlist"); dismiss()
                })
            }
            SheetAction(Icons.Rounded.Album, "Ir al álbum", {
                dismiss(); ui.showNowPlaying = false; ui.navigate(Routes.album(song.albumId))
            })
            SheetAction(Icons.Rounded.Person, "Ir al artista", {
                dismiss(); ui.showNowPlaying = false; ui.navigate(Routes.artist(song.artist))
            })
            SheetAction(Icons.Rounded.Info, "Detalles", { dismiss(); ui.songDetails = song })
        }
    }
}

/** Hoja para agregar canciones a una playlist existente o nueva. */
@Composable
fun AddToPlaylistSheet() {
    val ui = LocalUi.current
    val songs = ui.addToPlaylist ?: return
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.playlists.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    fun dismiss() { ui.addToPlaylist = null }

    if (creating) {
        TextInputDialog(
            title = "Nueva playlist",
            initial = "",
            confirmLabel = "Crear",
            onDismiss = { creating = false },
            onConfirm = { name ->
                scope.launch {
                    container.playlists.create(name, songs.map { it.id })
                    ui.message("Playlist \"$name\" creada")
                }
                creating = false
                dismiss()
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Agregar a playlist",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                songCountLabel(songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    MediaRow(
                        title = "Nueva playlist",
                        subtitle = "Crear y agregar",
                        onClick = { creating = true },
                    ) {
                        GradientIconCover(
                            Icons.Rounded.Add,
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            Modifier.size(56.dp),
                        )
                    }
                }
                items(playlists, key = { it.playlist.id }) { info ->
                    MediaRow(
                        title = info.playlist.name,
                        subtitle = songCountLabel(info.songCount),
                        onClick = {
                            scope.launch {
                                val added = container.playlists.addSongs(info.playlist.id, songs.map { it.id })
                                ui.message(
                                    when {
                                        added == 0 -> "Ya estaba en \"${info.playlist.name}\""
                                        songs.size == 1 -> "Agregada a \"${info.playlist.name}\""
                                        else -> "$added canciones agregadas a \"${info.playlist.name}\""
                                    },
                                )
                            }
                            dismiss()
                        },
                    ) {
                        PlaylistCover(
                            info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id,
                            Modifier.size(56.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
fun SongDetailsDialog() {
    val ui = LocalUi.current
    val song = ui.songDetails ?: return
    AlertDialog(
        onDismissRequest = { ui.songDetails = null },
        confirmButton = { TextButton(onClick = { ui.songDetails = null }) { Text("Cerrar") } },
        title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                DetailLine("Artista", song.artist)
                DetailLine("Álbum", song.album)
                if (song.year > 0) DetailLine("Año", song.year.toString())
                if (song.track > 0) DetailLine("Pista", song.track.toString())
                DetailLine("Duración", formatDuration(song.duration))
                DetailLine("Formato", mimeLabel(song.mimeType, song.path))
                DetailLine("Tamaño", formatSize(song.size))
                DetailLine("Ubicación", song.path)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "Nombre",
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
