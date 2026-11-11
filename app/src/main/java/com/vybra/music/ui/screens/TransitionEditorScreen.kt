package com.vybra.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.db.MixTransitionEntity
import com.vybra.music.data.db.Song
import com.vybra.music.data.db.TrackAnalysis
import com.vybra.music.data.db.TrackWaveform
import com.vybra.music.data.prefs.MixSettings
import com.vybra.music.data.prefs.MixStyle
import com.vybra.music.playback.mix.EqMode
import com.vybra.music.playback.mix.MixEffect
import com.vybra.music.playback.mix.MixFx
import com.vybra.music.playback.mix.MixPlanner
import com.vybra.music.playback.mix.TransitionPlan
import com.vybra.music.playback.mix.VolumeCurve
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.common.formatDuration
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.components.Artwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

// Colores de cada capa (como en las mezcladoras profesionales).
private val VolumeColor = Color(0xFF4FD1FF)
private val EqColor = Color(0xFFF5C542)
private val FilterColor = Color(0xFFFF7AD9)
private val FxColor = Color(0xFFA78BFA)
private val LoopColor = Color(0xFFFFA94D)
private val GridColor = Color(0xFF39E07A)
private val PlayheadColor = Color(0xFF39E07A)
private val PanelColor = Color(0xFF1C1C21)

data class EditorData(
    val a: Song,
    val b: Song,
    val analysisA: TrackAnalysis?,
    val analysisB: TrackAnalysis?,
    val waveA: TrackWaveform?,
    val waveB: TrackWaveform?,
    val settings: MixSettings,
    /** Transición guardada antes de abrir el editor (null = automática). */
    val saved: MixTransitionEntity?,
)

class TransitionEditorViewModel(private val c: AppContainer, private val fromId: Long, private val toId: Long) : ViewModel() {
    val data = MutableStateFlow<EditorData?>(null)
    /** Borrador actual; null = transición automática. */
    val draft = MutableStateFlow<MixTransitionEntity?>(null)
    val previewing = MutableStateFlow(false)
    val connection = c.player

    init {
        viewModelScope.launch {
            val a = c.music.song(fromId) ?: return@launch
            val b = c.music.song(toId) ?: return@launch
            val settings = c.settings.mix.first()
            val saved = c.database.mixTransitionDao().get(fromId, toId)
            draft.value = saved
            data.value = EditorData(a, b, c.analysis.cached(fromId), c.analysis.cached(toId), null, null, settings, saved)
            // Análisis y formas de onda (se calculan si aún no existen).
            val wa = async { c.analysis.waveform(a) }
            val wb = async { c.analysis.waveform(b) }
            val waveA = wa.await(); val waveB = wb.await()
            data.value = data.value?.copy(
                analysisA = c.analysis.cached(fromId), analysisB = c.analysis.cached(toId),
                waveA = waveA, waveB = waveB,
            )
        }
    }

    fun planFor(d: EditorData, draft: MixTransitionEntity?): TransitionPlan? =
        MixPlanner.plan(d.analysisA, d.analysisB, d.a.duration, d.b.duration, d.settings, draft)

    /** Parte del plan actual para que cualquier cambio conserve lo demás. */
    private fun base(): MixTransitionEntity {
        draft.value?.let { return it }
        val d = data.value!!
        return MixTransitionEntity(fromId, toId, d.settings.style.name, 0, -1, -1)
    }

    fun setAuto() { draft.value = null }

    fun setPreset(style: MixStyle) {
        // Un preset carga su receta completa (conserva solo los puntos de entrada y salida).
        draft.value = base().copy(
            style = style.name, volume = "AUTO", eq = "AUTO", effect = "AUTO", fx = "AUTO",
            loopBeats = -1, beatSync = -1, bars = 0, durationMs = 0,
        )
    }

    fun setVolume(v: VolumeCurve) { draft.value = base().copy(volume = v.name) }
    fun setEq(v: EqMode) { draft.value = base().copy(eq = v.name) }
    fun setFilter(v: MixEffect) { draft.value = base().copy(effect = v.name) }
    fun setFx(v: MixFx) { draft.value = base().copy(fx = v.name) }
    fun setLoop(beats: Int) { draft.value = base().copy(loopBeats = beats) }
    fun setBeatSync(on: Boolean) { draft.value = base().copy(beatSync = if (on) 1 else 0) }
    fun setBars(bars: Int) { draft.value = base().copy(bars = bars, durationMs = 0) }
    fun setSeconds(ms: Long) { draft.value = base().copy(durationMs = ms, bars = 0) }
    fun setPoints(exitMs: Long, entryMs: Long) { draft.value = base().copy(exitMs = exitMs, entryMs = entryMs) }

    fun togglePreview(plan: TransitionPlan) {
        val d = data.value ?: return
        if (previewing.value) { stopPreview(); return }
        c.mixPreviewDraft = draft.value
        c.mixPreviewPair = fromId to toId
        // Empieza unos compases antes para escuchar la llegada de la transición.
        val lead = d.analysisA?.takeIf { it.bpm > 0 }?.let { (MixPlanner.barMs(it) * 2).toLong() } ?: 4_000L
        connection.playFrom(listOf(d.a, d.b), 0, plan.exitMs - lead, "Vista previa del mix")
        previewing.value = true
    }

    fun stopPreview() {
        if (!previewing.value) return
        previewing.value = false
        connection.pause()
        c.mixPreviewPair = null
        c.mixPreviewDraft = null
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val dao = c.database.mixTransitionDao()
        val d = draft.value
        if (d == null) dao.delete(fromId, toId) else dao.upsert(d)
        stopPreview()
        onDone()
    }

    override fun onCleared() { stopPreview() }
}

@Composable
fun TransitionEditorScreen(fromId: Long, toId: Long) {
    val vm = vybraViewModel(key = "edit$fromId-$toId") { TransitionEditorViewModel(it, fromId, toId) }
    val ui = LocalUi.current
    val data by vm.data.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    Column(Modifier.fillMaxSize().background(EditorBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.stopPreview(); ui.back() }) { Icon(Icons.Rounded.Close, "Cancelar", tint = Color.White) }
            Text(
                "Editar transición",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .clip(CircleShape)
                    .background(if (data != null) MaterialTheme.colorScheme.primary else Color.DarkGray)
                    .clickable(enabled = data != null) { vm.save { ui.message("Transición guardada"); ui.back() } }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("Guardar", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val plan = vm.planFor(d, draft)
        if (plan == null) {
            TrackHeader(d.a, d.analysisA)
            Text("Estas canciones son demasiado cortas para mezclarlas.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TrackHeader(d.a, d.analysisA)
            MixDeck(d, plan, previewing, vm)
            TrackHeader(d.b, d.analysisB)
            PresetRow(draft, plan, vm)
            ControlList(d, plan, draft, vm)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrackHeader(song: Song, analysis: TrackAnalysis?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.id, song.albumId, Modifier.size(48.dp), RoundedCornerShape(6.dp), 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                analysis?.takeIf { it.bpm > 0 }?.let { "${it.bpm.roundToInt()} BPM" } ?: "Analizando…",
                style = MaterialTheme.typography.labelLarge, color = Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                analysis?.let { MixPlanner.camelotLabel(it) }?.let { cam ->
                    Text(
                        cam,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(camelotColor(analysis)).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(formatDuration(song.duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** Color de la rueda Camelot (cada número tiene su tono). */
private fun camelotColor(a: TrackAnalysis): Color {
    val n = MixPlanner.camelot(a)?.first ?: return Color.Gray
    val hue = ((n - 1) * 30f + 120f) % 360f
    return Color.hsv(hue, if (a.minor) 0.45f else 0.6f, 0.95f)
}

/** Curvas precalculadas (dependen solo del tipo de transición, no de su posición). */
private class CurveSamples(plan: TransitionPlan) {
    val n = 40
    val gainA = FloatArray(n + 1); val gainB = FloatArray(n + 1)
    val bassA = FloatArray(n + 1); val bassB = FloatArray(n + 1)
    val filterA = FloatArray(n + 1); val filterB = FloatArray(n + 1)
    val fxA = FloatArray(n + 1)
    val hasEq = plan.eq != EqMode.NONE
    val hasFilter = plan.effect != MixEffect.NONE
    val hasFx = plan.fx != MixFx.NONE

    init {
        fun cutoffNorm(hz: Float) = ((ln(hz.coerceIn(20f, 20_000f)) - ln(20f)) / (ln(20_000f) - ln(20f)))
        for (i in 0..n) {
            val f = MixPlanner.frame(plan, i / n.toFloat())
            gainA[i] = f.gainA; gainB[i] = f.gainB
            bassA[i] = 1f + f.bassA / 30f; bassB[i] = 1f + f.bassB / 30f
            filterA[i] = cutoffNorm(f.lowPassA); filterB[i] = 1f - cutoffNorm(maxOf(f.highPassB, 20f))
            fxA[i] = when (plan.fx) {
                MixFx.ECHO_OUT -> f.echoSendA
                MixFx.VINYL_BRAKE -> f.speedA
                MixFx.NONE -> 0f
            }
        }
    }
}

/**
 * Mesa de mezcla: minimapas, las dos formas de onda (dibujadas una sola vez en imágenes),
 * rejilla de pulsos, curvas y controles para arrastrar, ajustar al pulso y escuchar.
 */
@Composable
private fun MixDeck(d: EditorData, plan: TransitionPlan, previewing: Boolean, vm: TransitionEditorViewModel) {
    val exitState = remember { mutableLongStateOf(plan.exitMs) }
    val entryState = remember { mutableLongStateOf(plan.entryMs) }
    LaunchedEffect(plan.exitMs, plan.entryMs) {
        exitState.longValue = plan.exitMs
        entryState.longValue = plan.entryMs
    }
    val currentPlan by rememberUpdatedState(plan)
    val currentData by rememberUpdatedState(d)
    val scope = rememberCoroutineScope()
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var selectedLane by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var barsMenu by remember { mutableStateOf(false) }
    val curves = remember(plan.volume, plan.eq, plan.effect, plan.fx, plan.gainMatchB) { CurveSamples(plan) }
    val barMs = d.analysisA?.takeIf { it.bpm > 0 }?.let { MixPlanner.barMs(it) }
    val margin = max(plan.durationMs / 2, barMs?.toLong() ?: 2_000L)
    val windowMs = plan.durationMs + margin * 2
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val laneHeight = 132.dp
    val stripHeight = 36.dp
    val miniHeight = 30.dp

    /** Anima el ajuste al pulso y lo guarda en el borrador. */
    fun commit(exit: Long, entry: Long) {
        val fromExit = exitState.longValue
        val fromEntry = entryState.longValue
        snapJob?.cancel()
        snapJob = scope.launch {
            Animatable(0f).animateTo(1f, tween(140)) {
                exitState.longValue = fromExit + ((exit - fromExit) * value).toLong()
                entryState.longValue = fromEntry + ((entry - fromEntry) * value).toLong()
            }
            vm.setPoints(exit, entry)
        }
    }

    fun clampExit(v: Long) = v.coerceIn(currentData.a.duration / 3, currentData.a.duration - currentPlan.durationMs - 300)
    fun clampEntry(v: Long) = v.coerceIn(0, currentData.b.duration / 2)

    fun snapAndCommit() {
        var exit = exitState.longValue; var entry = entryState.longValue
        currentData.analysisA?.takeIf { it.bpm > 0 }?.let { exit = MixPlanner.nearestBeat(it, exit) }
        currentData.analysisB?.takeIf { it.bpm > 0 }?.let { entry = MixPlanner.nearestBeat(it, entry) }
        commit(clampExit(exit), clampEntry(entry))
    }

    /** Mueve la pista seleccionada un pulso (o 250 ms sin BPM). */
    fun nudge(dir: Int) {
        val dd = currentData
        if (selectedLane == 0) {
            val step = dd.analysisA?.takeIf { it.bpm > 0 }?.let { MixPlanner.beatMs(it) } ?: 250.0
            commit(clampExit((exitState.longValue + dir * step).toLong()), entryState.longValue)
        } else {
            val step = dd.analysisB?.takeIf { it.bpm > 0 }?.let { MixPlanner.beatMs(it) } ?: 250.0
            commit(exitState.longValue, clampEntry((entryState.longValue + dir * step).toLong()))
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val widthPx = constraints.maxWidth
        val laneHpx = with(density) { laneHeight.roundToPx() }
        val stripHpx = with(density) { stripHeight.toPx() }
        val miniHpx = with(density) { miniHeight.roundToPx() }
        val pxPerMs = widthPx.toDouble() / windowMs

        // Formas de onda dibujadas en segundo plano; mientras se recalculan se reescala la anterior.
        val tilesA = remember { mutableStateOf<WaveTiles?>(null) }
        val tilesB = remember { mutableStateOf<WaveTiles?>(null) }
        LaunchedEffect(d.waveA, windowMs, widthPx) { d.waveA?.let { tilesA.value = renderWaveTiles(it, d.a.duration, pxPerMs, laneHpx) } }
        LaunchedEffect(d.waveB, windowMs, widthPx, plan.speedB) {
            d.waveB?.let { tilesB.value = renderWaveTiles(it, d.b.duration, pxPerMs / plan.speedB, laneHpx) }
        }
        val overviewA = remember { mutableStateOf<ImageBitmap?>(null) }
        val overviewB = remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(d.waveA, widthPx) { d.waveA?.let { overviewA.value = renderOverview(it, d.a.duration, widthPx, miniHpx) } }
        LaunchedEffect(d.waveB, widthPx) { d.waveB?.let { overviewB.value = renderOverview(it, d.b.duration, widthPx, miniHpx) } }

        Column(Modifier.fillMaxWidth()) {
            // Minimapa de la saliente: toca o arrastra para saltar a otra parte de la canción.
            Minimap(
                image = overviewA.value, durationMs = d.a.duration, active = dragging && selectedLane == 0,
                viewport = { (exitState.longValue - margin).toDouble() to (exitState.longValue - margin + windowMs).toDouble() },
                zone = { exitState.longValue.toDouble() to (exitState.longValue + plan.durationMs).toDouble() },
                accent = accent,
                onSeek = { t, done ->
                    selectedLane = 0
                    exitState.longValue = clampExit((t - (windowMs / 2 - margin)).toLong())
                    if (done) snapAndCommit()
                },
                modifier = Modifier.padding(horizontal = 12.dp).height(miniHeight),
            )
            Spacer(Modifier.height(6.dp))

            Box(Modifier.fillMaxWidth().height(laneHeight * 2 + stripHeight)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { o -> selectedLane = if (o.y < size.height / 2) 0 else 1 }
                        }
                        .pointerInput(windowMs, widthPx) {
                            var lane = 0
                            var exitF = 0.0
                            var entryF = 0.0
                            detectHorizontalDragGestures(
                                onDragStart = { o ->
                                    snapJob?.cancel()
                                    lane = if (o.y < size.height / 2) 0 else 1
                                    selectedLane = lane
                                    dragging = true
                                    exitF = exitState.longValue.toDouble()
                                    entryF = entryState.longValue.toDouble()
                                },
                                onDragEnd = { dragging = false; snapAndCommit() },
                                onDragCancel = { dragging = false; snapAndCommit() },
                            ) { change, dx ->
                                change.consume()
                                val dt = dx.toDouble() / size.width * windowMs
                                val p = currentPlan
                                val dd = currentData
                                if (lane == 0) {
                                    exitF = (exitF - dt).coerceIn((dd.a.duration / 3).toDouble(), (dd.a.duration - p.durationMs - 300).toDouble())
                                    exitState.longValue = exitF.toLong()
                                } else {
                                    entryF = (entryF - dt * p.speedB).coerceIn(0.0, (dd.b.duration / 2).toDouble())
                                    entryState.longValue = entryF.toLong()
                                }
                            }
                        },
                ) {
                    drawDeck(
                        d = d, plan = plan, curves = curves,
                        exit = exitState.longValue, entry = entryState.longValue,
                        margin = margin, pxPerMs = pxPerMs,
                        tilesA = tilesA.value, tilesB = tilesB.value,
                        laneH = laneHpx.toFloat(), stripH = stripHpx,
                        selectedLane = selectedLane, accent = accent,
                    )
                }

                if (previewing) PlayheadOverlay(d, margin, windowMs, exitState, entryState, vm)

                // Controles centrales: mover un pulso a la izquierda/derecha y escuchar.
                Row(
                    Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Mover un pulso antes") { nudge(+1) }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(60.dp, 40.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .clickable { vm.togglePreview(plan) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (previewing) "Detener" else "Escuchar transición", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    RoundIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Mover un pulso después") { nudge(-1) }
                }

                if (d.waveA == null || d.waveB == null) {
                    Row(Modifier.align(Alignment.TopCenter).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Analizando el audio…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Minimap(
                image = overviewB.value, durationMs = d.b.duration, active = dragging && selectedLane == 1,
                viewport = {
                    val e = entryState.longValue.toDouble()
                    (e - margin * plan.speedB) to (e + (windowMs - margin) * plan.speedB)
                },
                zone = { entryState.longValue.toDouble() to entryState.longValue + plan.durationMs * plan.speedB.toDouble() },
                accent = accent,
                onSeek = { t, done ->
                    selectedLane = 1
                    entryState.longValue = clampEntry((t - (windowMs / 2 - margin) * plan.speedB).toLong())
                    if (done) snapAndCommit()
                },
                modifier = Modifier.padding(horizontal = 12.dp).height(miniHeight),
            )

            // Duración en compases
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A30))
                            .clickable { barsMenu = true }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (plan.bars > 0) "${plan.bars} ${if (plan.bars == 1) "compás" else "compases"}" else "${plan.durationMs / 1000} s",
                            color = Color.White, style = MaterialTheme.typography.labelLarge,
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = barsMenu, onDismissRequest = { barsMenu = false }) {
                        if (barMs != null) {
                            MixPlanner.BAR_OPTIONS.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text("$n ${if (n == 1) "compás" else "compases"} • ${formatDuration((n * barMs).toLong())}") },
                                    onClick = { barsMenu = false; vm.setBars(n) },
                                )
                            }
                        } else {
                            listOf(2, 4, 6, 8, 12, 16).forEach { s ->
                                DropdownMenuItem(text = { Text("$s segundos") }, onClick = { barsMenu = false; vm.setSeconds(s * 1000L) })
                            }
                        }
                    }
                }
            }
            Text(
                "Toca una pista para seleccionarla • arrástrala o usa ◀ ▶ para moverla un pulso",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White)
    }
}

/** Dibuja la mesa completa: imágenes de onda, zonas, rejilla y curvas. Solo operaciones baratas. */
private fun DrawScope.drawDeck(
    d: EditorData,
    plan: TransitionPlan,
    curves: CurveSamples,
    exit: Long,
    entry: Long,
    margin: Long,
    pxPerMs: Double,
    tilesA: WaveTiles?,
    tilesB: WaveTiles?,
    laneH: Float,
    stripH: Float,
    selectedLane: Int,
    accent: Color,
) {
    val w = size.width
    val t0 = exit - margin
    fun xOfA(tA: Double) = ((tA - t0) * pxPerMs).toFloat()
    // Tiempo de A en el que suena el instante tB de la canción entrante.
    fun aOfB(tB: Double) = exit + (tB - entry) / plan.speedB
    val zoneStart = xOfA(exit.toDouble())
    val zoneEnd = xOfA((exit + plan.durationMs).toDouble())
    val laneBTop = laneH + stripH
    // Sin pintar el fondo otra vez: ya lo pone la pantalla y las imágenes son opacas (menos trabajo de GPU).

    // Pista A
    clipRect(0f, 0f, w, laneH) {
        tilesA?.tiles?.forEach { tile ->
            val x0 = xOfA(tile.startMs).roundToInt()
            val x1 = xOfA(tile.endMs).roundToInt()
            if (x1 >= 0 && x0 <= w) drawImage(tile.image, dstOffset = IntOffset(x0, 0), dstSize = IntSize(x1 - x0, laneH.toInt()))
        }
        // Después de la transición la saliente ya no suena.
        drawRect(Color.Black.copy(alpha = 0.6f), Offset(zoneEnd, 0f), Size(w - zoneEnd, laneH))
    }
    // Pista B
    clipRect(0f, laneBTop, w, laneBTop + laneH) {
        tilesB?.tiles?.forEach { tile ->
            val x0 = xOfA(aOfB(tile.startMs)).roundToInt()
            val x1 = xOfA(aOfB(tile.endMs)).roundToInt()
            if (x1 >= 0 && x0 <= w) drawImage(tile.image, dstOffset = IntOffset(x0, laneBTop.toInt()), dstSize = IntSize(x1 - x0, laneH.toInt()))
        }
        // Antes de la transición la entrante todavía no suena; antes de su inicio no hay audio.
        drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, laneBTop), Size(zoneStart, laneH))
    }

    // Zona de la transición
    drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(zoneStart, 0f), Size(zoneEnd - zoneStart, size.height), CornerRadius(16f))
    drawLine(Color.White.copy(alpha = 0.7f), Offset(zoneStart, 0f), Offset(zoneStart, size.height), 2f)
    drawLine(Color.White.copy(alpha = 0.7f), Offset(zoneEnd, 0f), Offset(zoneEnd, size.height), 2f)

    // Pista seleccionada
    val selTop = if (selectedLane == 0) 0f else laneBTop
    drawRoundRect(accent.copy(alpha = 0.8f), Offset(1f, selTop + 1f), Size(w - 2f, laneH - 2f), CornerRadius(14f), style = Stroke(width = 3f))

    // Rejilla de pulsos: A arriba, B abajo (si coinciden, la mezcla está a tempo).
    val stripMid = laneH + stripH / 2
    d.analysisA?.takeIf { it.bpm > 0 }?.let { an ->
        val beat = MixPlanner.beatMs(an)
        var k = kotlin.math.ceil((t0 - an.beatOffsetMs) / beat)
        while (true) {
            val t = an.beatOffsetMs + k * beat
            val x = xOfA(t)
            if (x > w) break
            val bar = ((k % 4) + 4) % 4 == 0.0
            drawLine(GridColor.copy(alpha = if (bar) 0.95f else 0.5f), Offset(x, stripMid - (if (bar) 12f else 7f)), Offset(x, stripMid - 1f), if (bar) 3f else 2f)
            k++
        }
    }
    d.analysisB?.takeIf { it.bpm > 0 }?.let { bn ->
        val beat = MixPlanner.beatMs(bn)
        val tBstart = entry + (t0 - exit) * plan.speedB
        var k = kotlin.math.ceil((tBstart - bn.beatOffsetMs) / beat)
        while (true) {
            val tB = bn.beatOffsetMs + k * beat
            val x = xOfA(aOfB(tB))
            if (x > w) break
            val bar = ((k % 4) + 4) % 4 == 0.0
            drawLine(GridColor.copy(alpha = if (bar) 0.95f else 0.5f), Offset(x, stripMid + 1f), Offset(x, stripMid + (if (bar) 12f else 7f)), if (bar) 3f else 2f)
            k++
        }
    }

    // Bucle de la saliente
    if (plan.loopMs > 0) {
        drawRect(LoopColor.copy(alpha = 0.16f), Offset(zoneStart, 0f), Size(zoneEnd - zoneStart, laneH))
        val loopStartX = xOfA((exit - plan.loopMs).toDouble())
        drawLine(LoopColor, Offset(loopStartX, 0f), Offset(loopStartX, laneH), 3f)
        drawLine(LoopColor, Offset(loopStartX, 4f), Offset(zoneStart, 4f), 4f)
    }

    // Curvas sobre cada pista
    val pad = laneH * 0.1f
    fun y(top: Float, v: Float) = top + pad + (1f - v.coerceIn(0f, 1f)) * (laneH - pad * 2)
    fun curve(values: FloatArray, top: Float, color: Color, width: Float) {
        val path = Path()
        path.moveTo(0f, y(top, values[0]))
        for (i in 0..curves.n) path.lineTo(zoneStart + (zoneEnd - zoneStart) * i / curves.n, y(top, values[i]))
        path.lineTo(w, y(top, values[curves.n]))
        drawPath(path, color, style = Stroke(width = width))
    }
    if (curves.hasFx) curve(curves.fxA, 0f, FxColor, 4f)
    if (curves.hasFilter) { curve(curves.filterA, 0f, FilterColor, 4f); curve(curves.filterB, laneBTop, FilterColor, 4f) }
    if (curves.hasEq) { curve(curves.bassA, 0f, EqColor, 4f); curve(curves.bassB, laneBTop, EqColor, 4f) }
    curve(curves.gainA, 0f, VolumeColor, 5f)
    curve(curves.gainB, laneBTop, VolumeColor, 5f)
}

/** Minimapa de una canción con la ventana visible y la zona de transición. */
@Composable
private fun Minimap(
    image: ImageBitmap?,
    durationMs: Long,
    active: Boolean,
    viewport: () -> Pair<Double, Double>,
    zone: () -> Pair<Double, Double>,
    accent: Color,
    onSeek: (Double, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PanelColor)
            .pointerInput(durationMs) {
                // Tocar o arrastrar salta a esa parte de la canción; al soltar se ajusta al pulso.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: continue
                        val t = (c.position.x / size.width).coerceIn(0f, 1f) * durationMs.toDouble()
                        when {
                            c.pressed -> { c.consume(); seek(t, false) }
                            c.previousPressed -> seek(t, true)
                        }
                    }
                }
            },
    ) {
        image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = if (active) 1f else 0.75f) }
        val (z0, z1) = zone()
        drawRect(accent.copy(alpha = 0.35f), Offset((z0 / durationMs * size.width).toFloat(), 0f), Size(((z1 - z0) / durationMs * size.width).toFloat().coerceAtLeast(2f), size.height))
        val (v0, v1) = viewport()
        val x0 = (v0 / durationMs * size.width).toFloat()
        val x1 = (v1 / durationMs * size.width).toFloat()
        drawRoundRect(Color.White.copy(alpha = if (active) 1f else 0.7f), Offset(x0, 1f), Size(x1 - x0, size.height - 2f), CornerRadius(6f), style = Stroke(width = 3f))
    }
}

/** Línea verde que sigue la reproducción de la vista previa (~30 fps, solo redibuja esta capa). */
@Composable
private fun PlayheadOverlay(
    d: EditorData,
    margin: Long,
    windowMs: Long,
    exitState: MutableLongState,
    entryState: MutableLongState,
    vm: TransitionEditorViewModel,
) {
    val playhead = remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        while (true) {
            val pos = vm.connection.currentPosition
            playhead.longValue = when (vm.connection.state.value.nowPlaying?.songId) {
                d.a.id -> pos
                d.b.id -> exitState.longValue + (pos - entryState.longValue)
                else -> -1L
            }
            delay(33)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val ph = playhead.longValue
        if (ph < 0) return@Canvas
        val xx = ((ph - (exitState.longValue - margin)).toFloat() / windowMs) * size.width
        if (xx in 0f..size.width) drawLine(PlayheadColor, Offset(xx, 0f), Offset(xx, size.height), 5f)
    }
}

// ===================== Controles =====================

@Composable
private fun PresetRow(draft: MixTransitionEntity?, plan: TransitionPlan, vm: TransitionEditorViewModel) {
    val isAuto = draft == null
    val custom = draft != null && isCustom(draft)
    Text(
        "Presets",
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetChip("Auto", selected = isAuto, icon = true) { vm.setAuto() }
        if (custom) PresetChip("Personalizada", selected = true) {}
        listOf(
            MixStyle.FADE, MixStyle.RISE, MixStyle.BLEND, MixStyle.ECHO_OUT, MixStyle.VINYL,
            MixStyle.DJ_MIX, MixStyle.SMOOTH, MixStyle.BEAT_MATCH,
        ).forEach { style ->
            PresetChip(style.label, selected = !isAuto && !custom && plan.style == style) { vm.setPreset(style) }
        }
    }
}

private fun isCustom(o: MixTransitionEntity) =
    o.volume != "AUTO" || o.eq != "AUTO" || o.effect != "AUTO" || o.fx != "AUTO" ||
        o.loopBeats >= 0 || o.beatSync >= 0 || o.exitMs >= 0 || o.entryMs >= 0

@Composable
private fun PresetChip(label: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White else Color(0xFF2A2A30))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

private class Choice(val label: String, val description: String, val selected: Boolean, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
private fun ControlList(d: EditorData, plan: TransitionPlan, draft: MixTransitionEntity?, vm: TransitionEditorViewModel) {
    var open by remember { mutableStateOf<String?>(null) }
    val hasBpm = d.analysisA?.bpm?.let { it > 0 } == true
    val compat = MixPlanner.compatibility(d.analysisA, d.analysisB)

    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(PanelColor)) {
        ControlRow("Volumen", VolumeColor, plan.volume.label) { open = "vol" }
        ControlRow("Ecualizador", EqColor, plan.eq.label) { open = "eq" }
        ControlRow("Filtrar", FilterColor, plan.effect.label) { open = "filter" }
        ControlRow("Efectos", FxColor, plan.fx.label) { open = "fx" }
        ControlRow("Bucles", LoopColor, if (plan.loopBeats > 0) "Bucle de ${plan.loopBeats} beats" else "Sin bucle") { open = "loop" }
        val syncWanted = when (draft?.beatSync) { 1 -> true; 0 -> false; else -> plan.bpmSynced }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = hasBpm) { vm.setBeatSync(!syncWanted) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sincronización de ritmos", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        !hasBpm -> "Necesita el análisis de BPM"
                        syncWanted && !plan.bpmSynced -> "Los BPM son demasiado distintos para igualarlos"
                        plan.bpmSynced -> "Tempo de la entrante ×${"%.3f".format(plan.speedB)}, pulsos alineados"
                        else -> "Cada canción conserva su tempo"
                    },
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = syncWanted && hasBpm, onCheckedChange = { vm.setBeatSync(it) }, enabled = hasBpm)
        }
    }
    Text(
        listOfNotNull(
            compat?.let { "Compatibilidad: ${it.label}" },
            "Sale en ${formatDuration(plan.exitMs)}",
            "entra en ${formatDuration(plan.entryMs)}",
            if (plan.gainMatchB < 0.99f) "volumen de entrada ${(plan.gainMatchB * 100).toInt()} %" else null,
        ).joinToString(" • "),
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )

    when (open) {
        "vol" -> OptionSheet("Volumen", VolumeColor, VolumeCurve.entries.map { Choice(it.label, it.description, plan.volume == it) { vm.setVolume(it) } }) { open = null }
        "eq" -> OptionSheet("Ecualizador", EqColor, EqMode.entries.map { Choice(it.label, it.description, plan.eq == it) { vm.setEq(it) } }) { open = null }
        "filter" -> OptionSheet("Filtrar", FilterColor, MixEffect.entries.map { Choice(it.label, it.description, plan.effect == it) { vm.setFilter(it) } }) { open = null }
        "fx" -> OptionSheet("Efectos", FxColor, MixFx.entries.map { Choice(it.label, it.description, plan.fx == it) { vm.setFx(it) } }) { open = null }
        "loop" -> OptionSheet(
            "Bucles", LoopColor,
            MixPlanner.LOOP_OPTIONS.map { beats ->
                Choice(
                    if (beats == 0) "Sin bucle" else "Bucle de $beats beats",
                    if (beats == 0) "La saliente sigue sonando normalmente"
                    else if (hasBpm) "Repite los últimos $beats pulsos de la saliente, exactos al ritmo, mientras entra la nueva"
                    else "Necesita el análisis de BPM",
                    plan.loopBeats == beats,
                    enabled = beats == 0 || hasBpm,
                ) { vm.setLoop(beats) }
            },
        ) { open = null }
    }
}

@Composable
private fun ControlRow(label: String, color: Color, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OptionSheet(title: String, color: Color, choices: List<Choice>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1A1A1F),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            choices.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = c.enabled) { c.onClick(); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, color = if (c.enabled) Color.White else Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyLarge)
                        Text(c.description, color = Color.White.copy(alpha = if (c.enabled) 0.55f else 0.3f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (c.selected) Icon(Icons.Rounded.Check, null, tint = color)
                }
            }
        }
    }
}

/** Etiqueta corta de una transición para mostrarla entre canciones. */
fun transitionLabel(override: MixTransitionEntity?): String {
    if (override == null) return "Auto"
    return if (isCustom(override)) "Personalizada"
    else MixStyle.entries.firstOrNull { it.name == override.style }?.label ?: "Auto"
}
