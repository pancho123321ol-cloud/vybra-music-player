package com.vybra.music.playback.mix

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.vybra.music.AppContainer
import com.vybra.music.data.prefs.MixSettings
import com.vybra.music.playback.SleepTimerState
import com.vybra.music.playback.audio.VybraAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Estado visible del mix para la interfaz. */
sealed interface MixRuntime {
    data object Idle : MixRuntime
    data class Ready(val nextTitle: String, val plan: TransitionPlan) : MixRuntime
    data class Mixing(val nextTitle: String, val plan: TransitionPlan, val progress: Float) : MixRuntime
}

/**
 * Motor de transiciones con dos ExoPlayer: la canción entrante se pre-carga en el
 * reproductor en espera (en su punto de entrada, alineado al pulso y con time-stretch
 * al BPM de la saliente), ambas suenan a la vez con curvas de ganancia y filtros
 * aplicados por muestra en [VybraAudioProcessor], y al terminar la MediaSession pasa
 * al reproductor entrante sin cortes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MixEngine(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val players: Array<ExoPlayer>,
    private val processors: Array<VybraAudioProcessor>,
    private val audioAttributes: AudioAttributes,
    private val onSwap: (newActive: Int) -> Unit,
) {
    var active = 0
        private set
    private val current get() = players[active]
    private val standby get() = players[1 - active]
    private val curProc get() = processors[active]
    private val sbProc get() = processors[1 - active]

    private var settings = MixSettings()
    private var loopJob: Job? = null
    private var planJob: Job? = null
    private var transitionJob: Job? = null
    private var glideJob: Job? = null
    private var planKey: Pair<Long, Long>? = null
    private var plan: TransitionPlan? = null
    private var nextIndex = C.INDEX_UNSET
    private var nextTitle = ""
    private var prepared = false

    /** Fuerza el mix para un par concreto (vista previa del editor). */
    private var previewPair: Pair<Long, Long>?
        get() = container.mixPreviewPair
        set(value) { container.mixPreviewPair = value }

    private val _runtime = MutableStateFlow<MixRuntime>(MixRuntime.Idle)
    val runtime: StateFlow<MixRuntime> = _runtime.asStateFlow()

    init {
        scope.launch {
            container.settings.mix.collect {
                val changed = it != settings
                settings = it
                if (changed && transitionJob == null) resetPlan()
                evaluateLoop()
            }
        }
    }

    // ---------- Eventos del reproductor activo (reenviados por el servicio) ----------

    fun onIsPlayingChanged() = evaluateLoop()

    fun onMediaItemTransition() {
        // Cambio manual de canción en A (siguiente/anterior): se cancela lo preparado.
        if (transitionJob != null) abort("cambio de canción")
        resetPlan()
        evaluateLoop()
    }

    fun onSeek() {
        if (transitionJob != null) abort("búsqueda")
        else if (prepared) unprepare()
    }

    fun onQueueChanged() {
        if (transitionJob != null) abort("cola modificada")
        resetPlan()
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        // Durante la mezcla, la pausa/reanudación de A se refleja en B.
        if (transitionJob != null) standby.playWhenReady = playWhenReady
    }

    fun onModesChanged() {
        if (transitionJob == null) resetPlan()
    }

    // ---------- Bucle de seguimiento ----------

    private fun evaluateLoop() {
        val shouldRun = current.isPlaying && (settings.enabled || previewPair != null)
        if (shouldRun && loopJob?.isActive != true) {
            loopJob = scope.launch { monitor() }
        } else if (!shouldRun && transitionJob == null) {
            loopJob?.cancel(); loopJob = null
        }
    }

    private suspend fun monitor() {
        while (scope.isActive && current.isPlaying) {
            val waitMs = step()
            delay(waitMs)
        }
    }

    /** Un paso del seguimiento; devuelve cuánto esperar hasta el siguiente. */
    private fun step(): Long {
        if (transitionJob != null) return 250
        val item = current.currentMediaItem ?: return 1_000
        val fromId = item.mediaId.toLongOrNull() ?: return 1_000
        val next = current.nextMediaItemIndex
        val skip = next == C.INDEX_UNSET || next == current.currentMediaItemIndex ||
            current.repeatMode == Player.REPEAT_MODE_ONE ||
            container.sleepTimer.state.value == SleepTimerState.EndOfTrack
        if (skip) { resetPlan(); return 1_000 }
        val toId = current.getMediaItemAt(next).mediaId.toLongOrNull() ?: return 1_000
        val key = fromId to toId
        if (!settings.enabled && previewPair != key) { resetPlan(); return 1_000 }

        if (planKey != key) {
            resetPlan()
            planKey = key
            nextIndex = next
            nextTitle = current.getMediaItemAt(next).mediaMetadata.title?.toString() ?: ""
            val durationA = current.duration.takeIf { it > 0 } ?: 0L
            planJob = scope.launch { computePlan(fromId, toId, durationA) }
            return 250
        }
        val p = plan ?: return 500
        val pos = current.currentPosition
        if (!prepared && pos >= p.exitMs - PREPARE_AHEAD_MS) {
            if (pos > p.exitMs + 1_000) return 1_000 // ya pasó el punto de salida
            prepare(p)
        }
        if (prepared && pos >= p.exitMs - START_LEAD_MS) {
            if (standby.playbackState == Player.STATE_READY) {
                start(p)
                return 250
            }
            if (pos > p.exitMs + 1_500) { unprepare(); plan = null }
        }
        val remaining = p.exitMs - pos
        return when {
            remaining < 2_000 -> 15
            remaining < 20_000 -> 200
            else -> 1_000
        }
    }

    private suspend fun computePlan(fromId: Long, toId: Long, durationAHint: Long) {
        val music = container.music
        val songA = music.song(fromId) ?: return
        val songB = music.song(toId) ?: return
        // En la vista previa del editor se usa el borrador sin guardar.
        val override = container.mixPreviewDraft?.takeIf { it.fromSongId == fromId && it.toSongId == toId }
            ?: container.database.mixTransitionDao().get(fromId, toId)
        val durationA = if (durationAHint > 0) durationAHint else songA.duration
        val key = fromId to toId

        // 1) Plan inmediato con lo que ya esté analizado (o valores por defecto),
        //    para no perder nunca la transición aunque el análisis tarde.
        val cachedA = container.analysis.cached(fromId)
        val cachedB = container.analysis.cached(toId)
        publishPlan(key, MixPlanner.plan(cachedA, cachedB, durationA, songB.duration, settings, override))
        if (cachedA != null && cachedB != null) return

        // 2) Análisis completo y plan refinado, si aún no se ha preparado la transición.
        val analysisA = cachedA ?: runCatching { container.analysis.ensure(songA) }.getOrNull()
        val analysisB = cachedB ?: runCatching { container.analysis.ensure(songB) }.getOrNull()
        if (!prepared) publishPlan(key, MixPlanner.plan(analysisA, analysisB, durationA, songB.duration, settings, override))
    }

    private fun publishPlan(key: Pair<Long, Long>, p: TransitionPlan?) {
        if (planKey != key) return
        plan = p
        _runtime.value = if (p != null) MixRuntime.Ready(nextTitle, p) else MixRuntime.Idle
        Log.i(TAG, "plan ${key.first}→${key.second}: $p")
    }

    // ---------- Preparación y transición ----------

    private fun prepare(p: TransitionPlan) {
        val sb = standby
        sbProc.targetGain = 0f
        val first = MixPlanner.frame(p, 0f)
        sbProc.bassDb = first.bassB
        sbProc.highPassHz = first.highPassB
        sbProc.trebleDb = first.trebleB
        // Si hay bucle, A guarda su audio reciente para poder repetirlo al ritmo.
        curProc.captureEnabled = p.loopMs > 0
        curProc.echoDelayMs = p.echoDelayMs
        sbProc.feedAnalyzer = false
        sb.setAudioAttributes(audioAttributes, false)
        sb.setHandleAudioBecomingNoisy(false)
        sb.repeatMode = current.repeatMode
        sb.shuffleModeEnabled = current.shuffleModeEnabled
        val items = (0 until current.mediaItemCount).map { current.getMediaItemAt(it) }
        sb.setMediaItems(items, nextIndex, p.entryMs)
        if (current.shuffleModeEnabled) sb.setShuffleOrder(copyShuffleOrder())
        sb.playbackParameters = PlaybackParameters(p.speedB)
        sb.volume = current.volume
        sb.playWhenReady = false
        sb.prepare()
        prepared = true
    }

    /** Copia el orden aleatorio actual para que la cola siga igual tras el cambio. */
    private fun copyShuffleOrder(): ShuffleOrder {
        val tl = current.currentTimeline
        val order = ArrayList<Int>(current.mediaItemCount)
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && order.size < current.mediaItemCount) {
            order += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime())
    }

    private fun start(p: TransitionPlan) {
        standby.play()
        // Bucle exacto en beats: A repite su fragmento final mientras entra B.
        if (p.loopMs > 0) curProc.loopMs = p.loopMs.toFloat()
        var speedA = 1f
        transitionJob = scope.launch {
            try {
                while (isActive) {
                    // El progreso se mide con la canción entrante: el frenado o el bucle de A
                    // no alteran la duración real de la transición.
                    val elapsedB = (standby.currentPosition - p.entryMs) / p.speedB
                    val progress = (elapsedB / p.durationMs).coerceAtLeast(0f)
                    val f = MixPlanner.frame(p, progress)
                    curProc.targetGain = f.gainA
                    curProc.bassDb = f.bassA
                    curProc.trebleDb = f.trebleA
                    curProc.lowPassHz = f.lowPassA
                    sbProc.highPassHz = f.highPassB
                    sbProc.targetGain = f.gainB
                    sbProc.bassDb = f.bassB
                    sbProc.trebleDb = f.trebleB
                    curProc.echoSend = f.echoSendA
                    if (abs(f.speedA - speedA) > 0.01f) {
                        speedA = f.speedA
                        current.playbackParameters = PlaybackParameters(speedA, speedA)
                    }
                    if (progress >= 0.5f && !sbProc.feedAnalyzer) { sbProc.feedAnalyzer = true; curProc.feedAnalyzer = false }
                    _runtime.value = MixRuntime.Mixing(nextTitle, p, progress.coerceAtMost(1f))
                    if (progress >= 1f) break
                    delay(20)
                }
                complete(p)
            } finally {
                transitionJob = null
            }
        }
    }

    private fun complete(p: TransitionPlan) {
        val old = active
        val oldPlayer = players[old]
        active = 1 - old
        oldPlayer.setAudioAttributes(audioAttributes, false)
        oldPlayer.setHandleAudioBecomingNoisy(false)
        onSwap(active)
        current.setAudioAttributes(audioAttributes, true)
        current.setHandleAudioBecomingNoisy(true)
        oldPlayer.stop()
        oldPlayer.clearMediaItems()
        oldPlayer.playbackParameters = PlaybackParameters.DEFAULT
        processors[old].resetMix(feed = false)
        curProc.resetMix(feed = true)
        prepared = false
        planKey = null
        plan = null
        planJob?.cancel()
        previewPair = null
        container.mixPreviewDraft = null
        _runtime.value = MixRuntime.Idle
        if (p.speedB != 1f) glideToNormalSpeed(p.speedB)
        Log.i(TAG, "transición completada (${p.style})")
        evaluateLoop()
    }

    /** Devuelve gradualmente el tempo de la canción a su velocidad original. */
    private fun glideToNormalSpeed(from: Float) {
        glideJob?.cancel()
        val player = current
        glideJob = scope.launch {
            val steps = 40
            for (i in 1..steps) {
                delay(250)
                if (player !== current) return@launch
                player.playbackParameters = PlaybackParameters(from + (1f - from) * i / steps)
            }
        }
    }

    private fun abort(reason: String) {
        Log.i(TAG, "transición cancelada: $reason")
        transitionJob?.cancel()
        transitionJob = null
        curProc.resetMix(feed = true)
        // El frenado de vinilo cambia velocidad y tono a la vez; si se cancela, se restaura.
        if (current.playbackParameters.pitch != 1f) current.playbackParameters = PlaybackParameters.DEFAULT
        unprepare()
    }

    private fun unprepare() {
        if (!prepared) return
        sbProc.targetGain = 0f
        standby.stop()
        standby.clearMediaItems()
        standby.playbackParameters = PlaybackParameters.DEFAULT
        sbProc.resetMix(feed = false)
        sbProc.targetGain = 0f
        prepared = false
    }

    private fun resetPlan() {
        planJob?.cancel()
        planJob = null
        planKey = null
        plan = null
        unprepare()
        _runtime.value = MixRuntime.Idle
    }

    fun release() {
        loopJob?.cancel()
        planJob?.cancel()
        transitionJob?.cancel()
        glideJob?.cancel()
    }

    private companion object {
        const val TAG = "MixEngine"
        const val PREPARE_AHEAD_MS = 12_000L
        /** Adelanto para compensar la latencia de arranque del reproductor en espera. */
        const val START_LEAD_MS = 60L
    }
}

/** Deja el procesador sin efectos de mezcla (ganancia normal, sin filtros, bucle ni eco). */
@androidx.annotation.OptIn(UnstableApi::class)
private fun VybraAudioProcessor.resetMix(feed: Boolean) {
    targetGain = 1f
    bassDb = 0f
    trebleDb = 0f
    lowPassHz = 20_000f
    highPassHz = 0f
    loopMs = 0f
    captureEnabled = false
    echoSend = 0f
    feedAnalyzer = feed
}
