package com.vybra.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.analysis.AnalysisProgress
import com.vybra.music.data.db.MixTransitionEntity
import com.vybra.music.data.db.Song
import com.vybra.music.data.db.TrackAnalysis
import com.vybra.music.data.prefs.MixSettings
import com.vybra.music.data.prefs.MixStyle
import com.vybra.music.playback.mix.Compatibility
import com.vybra.music.playback.mix.MixPlanner
import com.vybra.music.playback.mix.TransitionPlan
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.common.formatDuration
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.ConfirmDialog
import com.vybra.music.ui.components.EmptyState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ===================== Datos =====================

data class TransitionRow(
    val a: Song,
    val b: Song,
    val analysisA: TrackAnalysis?,
    val analysisB: TrackAnalysis?,
    val override: MixTransitionEntity?,
    val plan: TransitionPlan?,
    val compatibility: Compatibility?,
)

data class PlaylistMixState(
    val name: String = "",
    val songs: List<Song> = emptyList(),
    val analyzed: Int = 0,
    val rows: List<TransitionRow> = emptyList(),
    val settings: MixSettings = MixSettings(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMixViewModel(private val c: AppContainer, private val playlistId: Long) : ViewModel() {
    private val songs = c.playlists.songs(playlistId)
    private val analyses = songs.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyMap()) else c.analysis.observe(list.map { it.id }).map { a -> a.associateBy { it.songId } }
    }
    private val overrides = songs.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyMap())
        else c.database.mixTransitionDao().observeFrom(list.map { it.id }).map { o -> o.associateBy { it.fromSongId to it.toSongId } }
    }
    val progress = c.analysis.progress

    val state = combine(c.playlists.playlist(playlistId), songs, analyses, overrides, c.settings.mix) { p, list, an, ov, mix ->
        val rows = list.zipWithNext().map { (a, b) ->
            val aa = an[a.id]; val ab = an[b.id]; val o = ov[a.id to b.id]
            TransitionRow(a, b, aa, ab, o, MixPlanner.plan(aa, ab, a.duration, b.duration, mix, o), MixPlanner.compatibility(aa, ab))
        }
        PlaylistMixState(p?.name ?: "", list, list.count { an.containsKey(it.id) }, rows, mix, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistMixState())

    fun analyze() = c.analysis.analyzeAll(state.value.songs)
    fun cancelAnalysis() = c.analysis.cancelBatch()
    fun setMix(m: MixSettings) = viewModelScope.launch { c.settings.saveMix(m) }

    fun playMix() = viewModelScope.launch {
        val s = state.value
        if (!s.settings.enabled) c.settings.saveMix(s.settings.copy(enabled = true))
        c.player.playSongs(s.songs, 0, shuffle = false, source = s.name)
    }

    fun applyHarmonicOrder(onDone: () -> Unit) = viewModelScope.launch {
        val s = state.value
        val an = c.analysis.observe(s.songs.map { it.id }).first().associateBy { it.songId }
        val ordered = MixPlanner.harmonicOrder(s.songs) { an[it.id] }
        c.playlists.reorder(playlistId, ordered.map { it.id })
        onDone()
    }
}

class MixSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.mix.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress = c.analysis.progress
    val analyzedCount = c.analysis.observeAll().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val songCount = c.music.songCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun save(m: MixSettings) = viewModelScope.launch { c.settings.saveMix(m) }
    fun analyzeLibrary() = viewModelScope.launch { c.analysis.analyzeAll(c.music.songs.filterNotNull().first()) }
    fun cancel() = c.analysis.cancelBatch()
}

// ===================== Ajustes globales =====================

@Composable
fun MixSettingsScreen() {
    val vm = vybraViewModel { MixSettingsViewModel(it) }
    val ui = LocalUi.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val analyzed by vm.analyzedCount.collectAsStateWithLifecycle()
    val total by vm.songCount.collectAsStateWithLifecycle()
    val s = settings ?: return

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Playlist Mix", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Transiciones entre canciones con análisis de BPM y tonalidad, sincronización de tempo y fundidos procesados en tiempo real.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        MixSettingsPanel(s, vm::save)
        AnalysisCard(progress, analyzed, total, onAnalyze = { vm.analyzeLibrary() }, onCancel = vm::cancel)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MixSettingsPanel(s: MixSettings, onChange: (MixSettings) -> Unit) {
    var duration by remember(s.durationMs) { mutableStateOf(s.durationMs / 1000f) }
    Column(
        Modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp),
    ) {
        SwitchRow("Mezcla automática", "Aplica transiciones al pasar de una canción a otra", s.enabled) { onChange(s.copy(enabled = it)) }
        Text("Estilo predeterminado", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        StyleChips(s.style) { onChange(s.copy(style = it)) }
        Text(
            s.style.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "Duración del crossfade: ${duration.toInt()} s",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        Slider(
            value = duration,
            onValueChange = { duration = it },
            onValueChangeFinished = { onChange(s.copy(durationMs = (duration * 1000).toLong())) },
            valueRange = 2f..20f,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SwitchRow("Sincronizar BPM", "Ajusta el tempo de la canción entrante (sin cambiar el tono) en Beat Match y DJ Mix", s.beatSync) {
            onChange(s.copy(beatSync = it))
        }
        SwitchRow("Volumen automático", "Iguala el volumen percibido entre canciones durante la transición", s.autoGain) {
            onChange(s.copy(autoGain = it))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StyleChips(selected: MixStyle, onSelect: (MixStyle) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MixStyle.entries.forEach { style ->
            val sel = style == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onSelect(style) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    style.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(progress: AnalysisProgress?, analyzed: Int, total: Int, onAnalyze: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Análisis de audio", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$analyzed de $total canciones analizadas (BPM, tonalidad, entradas y salidas)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress?.running == true) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${progress.done}/${progress.total}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Detener") }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Las canciones también se analizan solas justo antes de mezclarlas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAnalyze, enabled = analyzed < total, shape = CircleShape) {
                Text(if (analyzed < total) "Analizar ${total - analyzed} canciones" else "Todo analizado")
            }
        }
    }
}

// ===================== Playlist Mix =====================

@Composable
fun PlaylistMixScreen(playlistId: Long) {
    val vm = vybraViewModel(key = "mix$playlistId") { PlaylistMixViewModel(it, playlistId) }
    val ui = LocalUi.current
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirmOrder by remember { mutableStateOf(false) }

    if (confirmOrder) {
        ConfirmDialog(
            "Ordenar para mezcla armónica",
            "Se reordenará la playlist para que cada canción enlace con la siguiente por BPM y tonalidad compatibles.",
            "Ordenar",
            onDismiss = { confirmOrder = false },
            onConfirm = { confirmOrder = false; vm.applyHarmonicOrder { ui.message("Playlist ordenada para mezcla") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                Column(Modifier.weight(1f)) {
                    Text("Playlist Mix", style = MaterialTheme.typography.titleLarge)
                    Text(state.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.loaded && state.songs.size < 2) {
            item { EmptyState(Icons.Rounded.GraphicEq, "Añade más canciones", "Necesitas al menos dos canciones en la playlist para mezclarlas.") }
            return@LazyColumn
        }
        item { MixSettingsPanel(state.settings, vm::setMix) }
        item {
            AnalysisCard(
                progress, state.analyzed, state.songs.size,
                onAnalyze = { vm.analyze() }, onCancel = { vm.cancelAnalysis() },
            )
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.playMix() }, shape = CircleShape) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reproducir mix")
                }
                OutlinedButton(onClick = { confirmOrder = true }, shape = CircleShape, enabled = state.analyzed >= 3) {
                    Icon(Icons.Rounded.AutoFixHigh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Orden armónico")
                }
            }
        }
        item {
            Text(
                "Transiciones (${state.rows.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        items(state.rows, key = { "${it.a.id}-${it.b.id}" }) { row ->
            TransitionCard(row, onClick = { ui.navigate(Routes.transitionEditor(row.a.id, row.b.id)) })
        }
    }
}

private fun analysisLabel(a: TrackAnalysis?): String =
    if (a == null) "Sin analizar"
    else listOfNotNull("${a.bpm.toInt()} BPM", MixPlanner.camelotLabel(a), MixPlanner.keyName(a)).joinToString(" • ")

@Composable
private fun TransitionCard(row: TransitionRow, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongMini(row.a, analysisLabel(row.analysisA), Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
            SongMini(row.b, analysisLabel(row.analysisB), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.compatibility?.let { c ->
                Badge("Compatibilidad: ${c.label}", compatColor(c.score))
            }
            row.plan?.let { p ->
                Badge(p.style.label + if (p.custom) " • editada" else "", MaterialTheme.colorScheme.surfaceContainerHighest)
                Badge("${p.durationMs / 1000} s", MaterialTheme.colorScheme.surfaceContainerHighest)
                if (p.bpmSynced) Badge("BPM ×${"%.3f".format(p.speedB)}", MaterialTheme.colorScheme.surfaceContainerHighest)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Tune, "Editar transición", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun compatColor(score: Float): Color = when {
    score >= 0.8f -> Color(0xFF15803D)
    score >= 0.6f -> Color(0xFF3B82F6).copy(alpha = 0.7f)
    score >= 0.4f -> Color(0xFFB45309)
    else -> Color(0xFF991B1B)
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 8.dp, vertical = 4.dp),
        maxLines = 1,
    )
}

@Composable
private fun SongMini(song: Song, subtitle: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.id, song.albumId, Modifier.size(40.dp), RoundedCornerShape(6.dp), 16.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

