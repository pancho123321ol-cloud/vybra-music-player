package com.vybra.music.ui.player

import com.vybra.music.ui.common.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.data.lyrics.ParsedLyrics
import com.vybra.music.data.lyrics.LrcLibClient
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.LyricsEditorMode
import com.vybra.music.ui.LyricsEditorRequest
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.ConfirmDialog
import com.vybra.music.ui.components.PlayPauseButton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private class Loaded(val lyrics: ParsedLyrics?)

/** Pantalla de letras inmersiva con la línea actual resaltada y desplazamiento automático. */
@Composable
fun LyricsScreen(onClose: () -> Unit) {
    val ui = LocalUi.current
    val container = appContainer
    val connection = container.player
    val state by connection.state.collectAsStateWithLifecycle()
    val np = state.nowPlaying
    LaunchedEffect(np == null) { if (np == null) onClose() }
    if (np == null) return

    val scope = rememberCoroutineScope()
    val loaded by remember(np.songId) { container.lyrics.observe(np.songId).map { Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val importLauncher = com.vybra.music.platform.rememberOpenFile("Importar letra", com.vybra.music.platform.LYRICS_EXTENSIONS) { uri ->
        if (uri != null) scope.launch {
            val result = container.lyrics.importFor(np.songId, uri)
            ui.message(
                when {
                    result == null -> "El archivo no contiene una letra válida"
                    result.synced -> "Letra sincronizada importada"
                    else -> "Letra importada (sin sincronizar)"
                },
            )
        }
    }
    val editorRequest = LyricsEditorRequest(np.songId, np.title, np.artist)
    val lyrics = loaded?.lyrics
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var searching by remember { mutableStateOf(false) }
    var adjusting by remember(np.songId) { mutableStateOf(false) }
    var liveOffset by remember(np.songId) { mutableLongStateOf(0L) }
    // Al guardarse el ajuste, la letra ya trae el nuevo desfase: el ajuste en vivo vuelve a 0.
    LaunchedEffect(lyrics?.offsetMs) { liveOffset = 0L }
    var online by remember(np.songId) { mutableStateOf(OnlineState.IDLE) }

    // Búsqueda automática en internet cuando la canción no tiene letra.
    val missing = loaded != null && (lyrics == null || lyrics.isEmpty)
    LaunchedEffect(np.songId, missing, settings?.onlineLyrics) {
        if (!missing || settings?.onlineLyrics != true || container.lyrics.wasSearchedOnline(np.songId)) return@LaunchedEffect
        val song = container.music.song(np.songId) ?: return@LaunchedEffect
        online = OnlineState.SEARCHING
        online = try {
            val found = container.lyrics.fetchOnline(song)
            if (found != null) {
                ui.message(if (found.isSynced) "Letra sincronizada encontrada en LRCLIB" else "Letra encontrada en LRCLIB (sin tiempos)")
                OnlineState.IDLE
            } else OnlineState.NOT_FOUND
        } catch (_: java.io.IOException) {
            OnlineState.OFFLINE
        }
    }
    if (searching) {
        OnlineLyricsSheet(
            initialQuery = LrcLibClient.queryText(np.title, np.artist),
            durationMs = state.durationMs,
            onDismiss = { searching = false },
            onPick = { found ->
                searching = false
                scope.launch {
                    container.lyrics.save(np.songId, found.content ?: return@launch, "lrclib")
                    ui.message(if (found.isSynced) "Letra sincronizada guardada" else "Letra guardada. Toca Sincronizar para ajustarla sola.")
                }
            },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Eliminar letra", "¿Eliminar la letra de \"${np.title}\"?", "Eliminar",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { container.lyrics.delete(np.songId) }
                ui.message("Letra eliminada")
            },
        )
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        PlayerBackground(np.songId, np.albumId)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Cabecera
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar letras", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Artwork(np.songId, np.albumId, Modifier.size(44.dp), RoundedCornerShape(6.dp), 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "Opciones de letra", tint = Color.White)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (lyrics == null) "Escribir letra" else "Editar letra") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; ui.lyricsEditor = editorRequest },
                        )
                        DropdownMenuItem(
                            text = { Text("Buscar en internet") },
                            leadingIcon = { Icon(Icons.Rounded.TravelExplore, null) },
                            onClick = { menu = false; searching = true },
                        )
                        if (lyrics != null && !lyrics.isEmpty) {
                            if (lyrics.synced) {
                                DropdownMenuItem(
                                    text = { Text("Adelantar o retrasar letra") },
                                    leadingIcon = { Icon(Icons.Rounded.SyncAlt, null) },
                                    onClick = { menu = false; adjusting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Editar tiempos por línea") },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                    onClick = { menu = false; ui.lyricsEditor = editorRequest.copy(mode = LyricsEditorMode.REVIEW) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (lyrics.synced) "Volver a sincronizar automáticamente" else "Sincronizar automáticamente") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                onClick = { menu = false; ui.lyricsEditor = editorRequest.copy(mode = LyricsEditorMode.AUTO) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sincronizar a mano") },
                                leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                                onClick = { menu = false; ui.lyricsEditor = editorRequest.copy(mode = LyricsEditorMode.MANUAL) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Importar archivo LRC") },
                            leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                            onClick = { menu = false; importLauncher.launch(arrayOf("*/*")) },
                        )
                        if (lyrics != null) {
                            DropdownMenuItem(
                                text = { Text("Eliminar letra") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    lyrics == null || lyrics.isEmpty -> NoLyrics(
                        online = online,
                        onSearch = { searching = true },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onWrite = { ui.lyricsEditor = editorRequest },
                    )
                    lyrics.synced -> SyncedLyrics(lyrics, np.songId, state.isPlaying, liveOffset)
                    else -> PlainLyrics(lyrics, onSync = { ui.lyricsEditor = editorRequest.copy(mode = LyricsEditorMode.AUTO) })
                }
            }

            if (adjusting && lyrics != null && lyrics.synced) {
                OffsetBar(
                    totalMs = lyrics.offsetMs + liveOffset,
                    onChange = { liveOffset += it },
                    onDone = {
                        adjusting = false
                        val total = lyrics.offsetMs + liveOffset
                        if (liveOffset != 0L) scope.launch {
                            container.lyrics.setOffset(np.songId, total)
                            ui.message("Sincronización guardada")
                        }
                    },
                )
            }
            // Controles compactos
            Column(Modifier.padding(horizontal = 24.dp)) {
                PlaybackProgress(connection, state.isPlaying, np.songId, state.durationMs, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = connection::previous) { Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(34.dp)) }
                    PlayPauseButton(state.isPlaying, connection::togglePlayPause, size = 60.dp, container = Color.White, content = Color.Black)
                    IconButton(onClick = connection::next) { Icon(Icons.Rounded.SkipNext, "Siguiente", tint = Color.White, modifier = Modifier.size(34.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: ParsedLyrics, songId: Long, isPlaying: Boolean, liveOffsetMs: Long) {
    val connection = appContainer.player
    val position = rememberSmoothPosition(connection, isPlaying, songId)
    // Reloj de la letra: posición + desfase en ajuste. Las palabras se adelantan 40 ms
    // y las líneas 150 ms, para que aparezcan justo cuando empiezan a cantarse.
    val offset by rememberUpdatedState(liveOffsetMs)
    val wordClock: () -> Long = remember(position) { { position.value + offset + 40 } }
    val current by remember(lyrics, position) {
        derivedStateOf { lyrics.indexAt(position.value + offset + 150) }
    }
    // Tramo sin voz (intro o instrumental de más de 4 s): se muestran los puntos.
    val interlude by remember(lyrics, position) {
        derivedStateOf {
            val t = position.value + offset
            val next = lyrics.lines.getOrNull(current + 1)?.timeMs ?: return@derivedStateOf null
            val from = if (current < 0) 0L else lyrics.endOf(current)
            if (next - from >= 4_000 && t >= from && t < next - 300) from to next else null
        }
    }
    val listState = rememberLazyListState()
    var lastManualScroll by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) { following = false; lastManualScroll = System.currentTimeMillis() }
        }
    }
    // Retoma el seguimiento automático 4 s después de desplazar manualmente.
    LaunchedEffect(following, lastManualScroll) {
        if (!following) {
            kotlinx.coroutines.delay(4_000)
            if (!listState.isScrollInProgress) following = true
        }
    }
    // Item 0 = puntos de introducción; las líneas empiezan en el item 1.
    LaunchedEffect(current, following) {
        if (following) listState.animateScrollToItem((current + 1).coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPad = maxHeight * 0.28f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topPad, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "intro") {
                val gap = interlude
                if (current < 0 && gap != null) InterludeDots(gap.first, gap.second, wordClock)
                else Spacer(Modifier.height(1.dp))
            }
            itemsIndexed(lyrics.lines, key = { i, _ -> i }) { i, line ->
                KaraokeLine(
                    lyrics = lyrics,
                    index = i,
                    state = when {
                        i == current -> KaraokeLineState.Current
                        i < current -> KaraokeLineState.Past
                        else -> KaraokeLineState.Upcoming
                    },
                    clock = wordClock,
                    onClick = {
                        connection.seekTo((line.timeMs - offset).coerceAtLeast(0))
                        following = true
                    },
                )
                val gap = interlude
                if (i == current && gap != null) InterludeDots(gap.first, gap.second, wordClock)
            }
        }
        AnimatedVisibility(
            visible = !following,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable { following = true; scope.launch { listState.animateScrollToItem((current + 1).coerceAtLeast(0)) } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.MyLocation, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Volver a la línea actual", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Barra para adelantar o retrasar la letra en vivo y guardar el ajuste. */
@Composable
private fun OffsetBar(totalMs: Long, onChange: (Long) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            when {
                totalMs > 0 -> "La letra aparece %.1f s antes".format(totalMs / 1000f)
                totalMs < 0 -> "La letra aparece %.1f s después".format(-totalMs / 1000f)
                else -> "Sin ajuste"
            },
            color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(100) }) { Text("◀ Antes", color = Color.White) }
            TextButton(onClick = { onChange(-100) }) { Text("Después ▶", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text("Listo")
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: ParsedLyrics, onSync: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Letra sin sincronizar", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = onSync, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sincronizar")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        itemsIndexed(lyrics.lines) { _, line ->
            Text(
                line.text,
                color = Color.White,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MaterialTheme.typography.headlineMedium.fontFamily,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

private enum class OnlineState { IDLE, SEARCHING, NOT_FOUND, OFFLINE }

@Composable
private fun NoLyrics(online: OnlineState, onSearch: () -> Unit, onImport: () -> Unit, onWrite: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (online == OnlineState.SEARCHING) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("Buscando la letra en internet…", style = MaterialTheme.typography.titleMedium, color = Color.White)
            return@Column
        }
        Icon(Icons.Rounded.Lyrics, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No hay letra para esta canción", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            when (online) {
                OnlineState.NOT_FOUND -> "No se encontró automáticamente en internet. Prueba a buscarla con otras palabras, o pégala y la app la sincroniza sola."
                OnlineState.OFFLINE -> "Sin conexión para buscarla en internet. Puedes importar un .lrc o pegar la letra y la app la sincroniza sola."
                else -> "Búscala en internet, importa un .lrc, o pega la letra y la app la sincroniza sola con la canción."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSearch, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Icon(Icons.Rounded.TravelExplore, null)
            Spacer(Modifier.width(8.dp))
            Text("Buscar en internet")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onWrite) {
            Icon(Icons.Rounded.Edit, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Escribir o pegar letra", color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onImport) {
            Icon(Icons.Rounded.FileOpen, null, tint = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.width(8.dp))
            Text("Importar archivo LRC", color = Color.White.copy(alpha = 0.85f))
        }
    }
}
