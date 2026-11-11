package com.vybra.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.data.lyrics.LrcParser
import com.vybra.music.data.lyrics.LrcLibClient
import com.vybra.music.data.lyrics.LyricLine
import com.vybra.music.ui.LocalUi
import com.vybra.music.data.lyrics.LyricsRepository
import com.vybra.music.ui.LyricsEditorMode
import com.vybra.music.ui.LyricsEditorRequest
import com.vybra.music.ui.common.formatDuration
import com.vybra.music.ui.components.PlayPauseButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Editor de letras: permite escribir/pegar texto o LRC, y sincronizarlo con la
 * canción tocando "Marcar" cuando empieza cada línea.
 */
@Composable
fun LyricsEditorScreen(request: LyricsEditorRequest, onClose: () -> Unit) {
    val ui = LocalUi.current
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(request.mode) }
    var review by remember { mutableStateOf<ReviewData?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    /** Volver: a la escritura si se llegó desde ella; si no, se cierra. */
    val back: () -> Unit = {
        if (mode != LyricsEditorMode.EDIT && request.mode == LyricsEditorMode.EDIT) mode = LyricsEditorMode.EDIT else onClose()
    }

    fun runAuto(source: String) {
        job?.cancel()
        working = "Preparando…"
        job = scope.launch {
            val song = container.music.song(request.songId)
            if (song == null) { working = null; ui.message("No se encontró la canción"); return@launch }
            val online = container.settings.current().onlineLyrics
            val result = try {
                container.lyrics.autoSync(song, source, online) { working = it }
            } finally {
                working = null
            }
            if (result == null) {
                ui.message("No se pudo sincronizar automáticamente. Puedes marcarla a mano.")
                mode = if (request.mode == LyricsEditorMode.AUTO) LyricsEditorMode.MANUAL else LyricsEditorMode.EDIT
                return@launch
            }
            review = ReviewData(
                result.lines,
                when (result.source) {
                    LyricsRepository.SyncSource.ONLINE ->
                        "Tiempos tomados de la versión sincronizada de LRCLIB (${result.matched} de ${result.lines.size} líneas coinciden con tu letra)."
                    LyricsRepository.SyncSource.AUDIO ->
                        (if (result.triedOnline) "No hay una versión sincronizada en internet que coincida. " else "") +
                            "Tiempos estimados analizando la voz (aproximados). Reproduce la canción para revisarlos: toca una línea para escucharla y ajústala si hace falta."
                },
            )
            mode = LyricsEditorMode.REVIEW
        }
    }

    LaunchedEffect(request.songId) {
        val content = container.lyrics.observeRaw(request.songId).first()?.content ?: ""
        text = content
        when (request.mode) {
            LyricsEditorMode.AUTO -> runAuto(content)
            LyricsEditorMode.REVIEW -> {
                val parsed = LrcParser.parse(content)
                if (parsed.synced) review = ReviewData(parsed.lines.filter { it.text.isNotBlank() }, null)
                else runAuto(content)
            }
            else -> Unit
        }
    }
    BackHandler { if (working != null) { job?.cancel(); working = null; back() } else back() }

    if (searching) {
        OnlineLyricsSheet(
            initialQuery = LrcLibClient.queryText(request.title, request.artist),
            durationMs = container.player.state.value.let { if (it.nowPlaying?.songId == request.songId) it.durationMs else 0L },
            onDismiss = { searching = false },
            onPick = { found ->
                searching = false
                text = found.content
                ui.message(if (found.isSynced) "Letra sincronizada cargada. Toca Guardar." else "Letra cargada. Puedes sincronizarla automáticamente.")
            },
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val current = text ?: return@Box
        val stage = working
        val reviewData = review
        when {
            stage != null -> AutoSyncProgress(stage, onCancel = { job?.cancel(); working = null; back() })
            mode == LyricsEditorMode.REVIEW && reviewData != null -> TimingReview(
                request = request,
                data = reviewData,
                onCancel = back,
                onSave = { lrc ->
                    scope.launch {
                        container.lyrics.save(request.songId, lrc, "sync")
                        ui.message("Letra sincronizada guardada")
                        onClose()
                    }
                },
            )
            mode == LyricsEditorMode.MANUAL || mode == LyricsEditorMode.AUTO || mode == LyricsEditorMode.REVIEW -> SyncTool(
                request = request,
                sourceText = current,
                onCancel = back,
                onSave = { lrc ->
                    scope.launch {
                        container.lyrics.save(request.songId, lrc, "sync")
                        ui.message("Letra sincronizada guardada")
                        onClose()
                    }
                },
            )
            else -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cancelar") }
                    Column(Modifier.weight(1f)) {
                        Text("Letra", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${request.title} • ${request.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.TravelExplore, "Buscar en internet") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (current.isBlank()) container.lyrics.delete(request.songId)
                                else container.lyrics.save(request.songId, current, "manual")
                                ui.message("Letra guardada")
                                onClose()
                            }
                        },
                    ) { Text("Guardar") }
                }
                Text(
                    "Escribe o pega la letra (deja una línea en blanco entre estrofas), o búscala en internet con 🔍. " +
                        "Después la app la sincroniza sola.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = current,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    placeholder = { Text("Primera línea\nSegunda línea…") },
                    shape = RoundedCornerShape(14.dp),
                )
                val hasLines = LrcParser.parse(current).lines.any { it.text.isNotBlank() }
                Button(
                    onClick = { runAuto(current) },
                    enabled = hasLines,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sincronizar automáticamente")
                }
                OutlinedButton(
                    onClick = { mode = LyricsEditorMode.MANUAL },
                    enabled = hasLines,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Rounded.TouchApp, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Marcar a mano")
                }
            }
        }
    }
}

/** Letra con tiempos propuestos, pendiente de revisar. */
class ReviewData(val lines: List<LyricLine>, val note: String?)

@Composable
private fun AutoSyncProgress(stage: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Sincronizando automáticamente", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Cancelar") }
    }
}

/**
 * Revisión de tiempos: muestra cada línea con su tiempo, resalta la que suena,
 * permite escuchar desde una línea, moverla ±0,25 s, fijarla al momento actual
 * o desplazar toda la letra.
 */
@Composable
private fun TimingReview(
    request: LyricsEditorRequest,
    data: ReviewData,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val connection = container.player
    val scope = rememberCoroutineScope()
    val state by connection.state.collectAsStateWithLifecycle()
    val texts = remember(data) { data.lines.map { it.text } }
    val times = remember(data) { mutableStateListOf<Long>().apply { addAll(data.lines.map { it.timeMs }) } }
    var selected by remember(data) { mutableIntStateOf(0) }
    var shift by remember(data) { mutableLongStateOf(0L) }
    val isThisSong = state.nowPlaying?.songId == request.songId
    val position by rememberPlaybackPosition(connection, state.isPlaying, request.songId, intervalMs = 100)
    val playing = if (isThisSong) {
        var idx = -1
        for (i in times.indices) if (times[i] <= position + 120) idx = i else break
        idx
    } else -1
    val listState = rememberLazyListState()
    // Solo se desplaza cuando la línea que suena está por salir de la pantalla, para no
    // mover la lista bajo el dedo (un toque durante la animación se perdería).
    LaunchedEffect(playing) {
        if (playing < 0 || !state.isPlaying || listState.isScrollInProgress) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return@LaunchedEffect
        val last = visible.lastOrNull()?.index ?: return@LaunchedEffect
        if (playing < first || playing >= last - 1) listState.animateScrollToItem((playing - 1).coerceAtLeast(0))
    }

    fun setTime(i: Int, value: Long) {
        val lo = if (i > 0) times[i - 1] else 0L
        val hi = if (i < times.lastIndex) times[i + 1] else Long.MAX_VALUE
        times[i] = value.coerceIn(lo, hi)
    }
    fun shiftAll(delta: Long) {
        val d = if (delta < 0) maxOf(delta, -(times.minOrNull() ?: 0L)) else delta
        for (i in times.indices) times[i] = times[i] + d
        shift += d
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancelar") }
            Column(Modifier.weight(1f)) {
                Text("Revisar sincronización", style = MaterialTheme.typography.titleLarge)
                Text(request.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = {
                // Al mover una línea se mueven con ella sus palabras.
                val out = data.lines.mapIndexed { i, l ->
                    val d = times[i] - l.timeMs
                    l.copy(
                        timeMs = times[i],
                        endMs = if (l.endMs >= 0) l.endMs + d else -1,
                        words = l.words?.map { w -> w.copy(startMs = w.startMs + d) },
                    )
                }
                onSave(LrcParser.toLrc(out, request.title, request.artist))
            }) { Text("Guardar") }
        }
        if (data.note != null) {
            Text(
                data.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!isThisSong) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Reproduce la canción para escuchar cada línea.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch { container.music.song(request.songId)?.let { connection.playSongs(listOf(it), source = "lyrics") } }
                }) { Text("Reproducir") }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            itemsIndexed(texts) { i, line ->
                val isSel = i == selected
                val isPlaying = i == playing
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                isSel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            },
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            selected = i
                            if (isThisSong) {
                                connection.seekTo((times[i] - 400).coerceAtLeast(0))
                                connection.play()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        LrcParser.format(times[i]).trim('[', ']'),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Línea ${selected + 1}: ${texts.getOrElse(selected) { "" }}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { setTime(selected, times[selected] - 250) }, modifier = Modifier.weight(1f)) { Text("−0,25 s") }
                Button(
                    onClick = { setTime(selected, connection.currentPosition); if (selected < times.lastIndex) selected++ },
                    enabled = isThisSong,
                    modifier = Modifier.weight(1.4f),
                ) { Text("Marcar ahora") }
                OutlinedButton(onClick = { setTime(selected, times[selected] + 250) }, modifier = Modifier.weight(1f)) { Text("+0,25 s") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Toda la letra", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { shiftAll(-100) }) { Text("−0,1 s") }
                Text(
                    (if (shift > 0) "+" else "") + String.format(java.util.Locale.getDefault(), "%.1f s", shift / 1000f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = { shiftAll(100) }) { Text("+0,1 s") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { connection.seekTo((connection.currentPosition - 5_000).coerceAtLeast(0)) }, enabled = isThisSong) {
                    Icon(Icons.Rounded.Replay5, "Retroceder 5 segundos")
                }
                PlayPauseButton(state.isPlaying, connection::togglePlayPause, size = 52.dp)
                Text(
                    if (isThisSong) "${formatDuration(position)} / ${formatDuration(state.durationMs)}" else "--:--",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SyncTool(
    request: LyricsEditorRequest,
    sourceText: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val connection = container.player
    val state by connection.state.collectAsStateWithLifecycle()
    val lines = remember(sourceText) { LrcParser.parse(sourceText).lines.map { it.text }.filter { it.isNotBlank() } }
    val stamps = remember { mutableStateListOf<Long>() }
    val isThisSong = state.nowPlaying?.songId == request.songId
    val listState = rememberLazyListState()
    val next = stamps.size

    LaunchedEffect(next) { if (lines.isNotEmpty()) listState.animateScrollToItem((next - 1).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancelar") }
            Column(Modifier.weight(1f)) {
                Text("Sincronizar letra", style = MaterialTheme.typography.titleLarge)
                Text("${next}/${lines.size} líneas marcadas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = { onSave(LrcParser.toLrc(lines.zip(stamps).map { (t, ms) -> LyricLine(ms, t) }, request.title, request.artist)) },
                enabled = next == lines.size && lines.isNotEmpty(),
            ) { Text("Guardar") }
        }

        if (!isThisSong) {
            Text(
                "Reproduce \"${request.title}\" para poder sincronizarla.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        Text(
            "Toca \"Marcar\" justo cuando empiece cada línea. Puedes deshacer o retroceder 5 s.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                val isNext = i == next
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isNext) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stamps.getOrNull(i)?.let { LrcParser.format(it) } ?: "[--:--.--]",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (i < next) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        color = if (i < next) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        val position by rememberPlaybackPosition(connection, state.isPlaying, request.songId, intervalMs = 100)
        Column(Modifier.padding(16.dp)) {
            Text(
                "${formatDuration(position)} / ${formatDuration(state.durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { stamps.clear(); connection.seekTo(0); connection.play() }) {
                    Icon(Icons.Rounded.Replay, "Empezar desde el inicio")
                }
                IconButton(onClick = { if (stamps.isNotEmpty()) stamps.removeAt(stamps.lastIndex) }, enabled = stamps.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Deshacer última marca")
                }
                PlayPauseButton(state.isPlaying, connection::togglePlayPause, size = 52.dp)
                IconButton(onClick = { connection.seekTo((connection.currentPosition - 5_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Rounded.Replay5, "Retroceder 5 segundos")
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (next < lines.size) {
                        // El momento de la marca nunca puede ser anterior a la marca previa.
                        val t = connection.currentPosition.coerceAtLeast(stamps.lastOrNull() ?: 0L)
                        stamps.add(t)
                    }
                },
                enabled = next < lines.size,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = CircleShape,
            ) {
                Text(
                    if (next < lines.size) "Marcar: ${lines[next].take(28)}" else "¡Listo! Toca Guardar",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
