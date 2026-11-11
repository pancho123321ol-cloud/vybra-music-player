package com.vybra.music.playback.mix

import com.vybra.music.data.db.MixTransitionEntity
import com.vybra.music.data.db.TrackAnalysis
import com.vybra.music.data.prefs.MixSettings
import com.vybra.music.data.prefs.MixStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Curva de volumen de la transición. */
enum class VolumeCurve(val label: String, val description: String) {
    SMOOTH("Crossfade suave", "Potencia constante: ninguna de las dos baja de golpe"),
    LINEAR("Lineal", "Una baja y la otra sube al mismo ritmo"),
    OVERLAP("Superposición", "Ambas suenan completas en el centro de la mezcla"),
    FAST_IN("Entrada rápida", "La nueva entra enseguida y la anterior se va despacio"),
    SLOW_OUT("Salida lenta", "La anterior se mantiene casi hasta el final"),
    CUT("Corte en el pulso", "Cambio seco justo a mitad de la transición"),
}

/** Tratamiento de ecualización durante la transición. */
enum class EqMode(val label: String, val description: String) {
    NONE("Sin EQ", "Las dos canciones conservan todas sus frecuencias"),
    BASS_SWAP("Intercambio de graves central", "A mitad de la mezcla los graves pasan de una a otra"),
    BASS_CUT_IN("Entrada sin graves", "La nueva entra sin graves y los recupera al final"),
    BASS_CUT_OUT("Salida sin graves", "La anterior pierde los graves en cuanto empieza la mezcla"),
    FULL_SWAP("Intercambio completo", "Graves y agudos cambian de canción por separado"),
}

/** Filtro de barrido durante la transición. */
enum class MixEffect(val label: String, val description: String) {
    NONE("Sin filtro", "Sin filtros de barrido"),
    LOW_PASS_OUT("Paso bajo en la salida", "La anterior se apaga quitando agudos poco a poco"),
    HIGH_PASS_IN("Paso alto en la entrada", "La nueva aparece desde los agudos hasta sonar completa"),
    FILTER_SWEEP("Barrido doble", "Ambos filtros a la vez, típico de una mezcla de club"),
}

/** Efecto creativo sobre la canción saliente. */
enum class MixFx(val label: String, val description: String) {
    NONE("Ninguno", "Sin efectos adicionales"),
    ECHO_OUT("Eco de salida", "Eco al ritmo que se queda sonando mientras la anterior se va"),
    VINYL_BRAKE("Frenado de vinilo", "La anterior se frena como un tocadiscos (baja velocidad y tono)"),
}

/** Componentes que define cada estilo predeterminado. */
data class StyleRecipe(
    val volume: VolumeCurve,
    val eq: EqMode,
    val effect: MixEffect,
    val fx: MixFx,
    val beatSync: Boolean,
    /** Duración por defecto en compases cuando se conoce el BPM. */
    val bars: Int,
    /** Bucle de la saliente en beats (0 = sin bucle). */
    val loopBeats: Int = 0,
)

fun MixStyle.recipe(): StyleRecipe = when (this) {
    MixStyle.FADE -> StyleRecipe(VolumeCurve.LINEAR, EqMode.NONE, MixEffect.NONE, MixFx.NONE, beatSync = false, bars = 4)
    MixStyle.SMOOTH -> StyleRecipe(VolumeCurve.SMOOTH, EqMode.NONE, MixEffect.NONE, MixFx.NONE, beatSync = false, bars = 4)
    MixStyle.BEAT_MATCH -> StyleRecipe(VolumeCurve.SMOOTH, EqMode.NONE, MixEffect.NONE, MixFx.NONE, beatSync = true, bars = 4)
    MixStyle.DJ_MIX -> StyleRecipe(VolumeCurve.OVERLAP, EqMode.BASS_SWAP, MixEffect.LOW_PASS_OUT, MixFx.NONE, beatSync = true, bars = 8)
    MixStyle.RISE -> StyleRecipe(VolumeCurve.OVERLAP, EqMode.BASS_CUT_IN, MixEffect.HIGH_PASS_IN, MixFx.NONE, beatSync = true, bars = 4)
    MixStyle.BLEND -> StyleRecipe(VolumeCurve.SMOOTH, EqMode.BASS_SWAP, MixEffect.NONE, MixFx.NONE, beatSync = true, bars = 8, loopBeats = 16)
    MixStyle.ECHO_OUT -> StyleRecipe(VolumeCurve.FAST_IN, EqMode.BASS_CUT_OUT, MixEffect.NONE, MixFx.ECHO_OUT, beatSync = true, bars = 4)
    MixStyle.VINYL -> StyleRecipe(VolumeCurve.CUT, EqMode.NONE, MixEffect.NONE, MixFx.VINYL_BRAKE, beatSync = false, bars = 2)
}

/** Plan concreto de una transición entre la canción saliente (A) y la entrante (B). */
data class TransitionPlan(
    val style: MixStyle,
    val volume: VolumeCurve,
    val eq: EqMode,
    val effect: MixEffect,
    val fx: MixFx,
    /** Posición de A donde empieza la transición. */
    val exitMs: Long,
    /** Posición de B donde entra. */
    val entryMs: Long,
    val durationMs: Long,
    /** Duración expresada en compases (0 si no hay BPM). */
    val bars: Int,
    /** Velocidad aplicada a B durante la mezcla (1 = sin sincronizar). */
    val speedB: Float,
    val bpmSynced: Boolean,
    /** Ganancia inicial de B para igualar volumen percibido (≤ 1). */
    val gainMatchB: Float,
    /** Bucle de A en beats durante la mezcla (0 = sin bucle) y su duración real. */
    val loopBeats: Int,
    val loopMs: Long,
    /** Retardo del eco (3/4 de pulso, o 375 ms sin BPM). */
    val echoDelayMs: Float,
    val custom: Boolean,
)

/** Ganancias, filtros y efectos en un instante de la transición. */
data class MixFrame(
    val gainA: Float,
    val gainB: Float,
    val bassA: Float,
    val bassB: Float,
    val trebleA: Float = 0f,
    val trebleB: Float = 0f,
    /** Corte del filtro paso bajo de A en Hz (20 000 = sin filtro). */
    val lowPassA: Float = MixPlanner.FILTER_OFF_LP,
    /** Corte del filtro paso alto de B en Hz (0 = sin filtro). */
    val highPassB: Float = MixPlanner.FILTER_OFF_HP,
    /** Señal de A enviada al eco 0..1. */
    val echoSendA: Float = 0f,
    /** Velocidad de A por el frenado de vinilo (1 = normal). */
    val speedA: Float = 1f,
)

data class Compatibility(val score: Float, val bpmScore: Float, val keyScore: Float) {
    val label: String
        get() = when {
            score >= 0.8f -> "Excelente"
            score >= 0.6f -> "Buena"
            score >= 0.4f -> "Regular"
            else -> "Baja"
        }
}

object MixPlanner {
    const val FILTER_OFF_LP = 20_000f
    const val FILTER_OFF_HP = 0f
    private const val MIN_DURATION = 1_000L
    private const val MAX_TEMPO_SHIFT = 0.08f
    val BAR_OPTIONS = listOf(1, 2, 4, 8, 16)
    val LOOP_OPTIONS = listOf(0, 4, 8, 16)

    private val KEY_NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    fun keyName(a: TrackAnalysis): String? =
        if (a.keyIndex < 0) null else KEY_NAMES[a.keyIndex] + if (a.minor) "m" else ""

    /** Notación Camelot (rueda armónica): 1A–12A menores, 1B–12B mayores. */
    fun camelot(a: TrackAnalysis): Pair<Int, Boolean>? {
        if (a.keyIndex < 0) return null
        val majorPc = if (a.minor) (a.keyIndex + 3) % 12 else a.keyIndex
        val number = ((majorPc * 7) % 12 + 7) % 12 + 1
        return number to !a.minor
    }

    fun camelotLabel(a: TrackAnalysis): String? = camelot(a)?.let { (n, major) -> "$n${if (major) "B" else "A"}" }

    /** Razón de tempo que lleva B al tempo de A (considera medio/doble tiempo). */
    fun tempoRatio(a: TrackAnalysis, b: TrackAnalysis): Float {
        if (a.bpm <= 0 || b.bpm <= 0) return 1f
        val r = a.bpm / b.bpm
        return listOf(r, r * 2, r / 2).minBy { abs(it - 1f) }
    }

    fun compatibility(a: TrackAnalysis?, b: TrackAnalysis?): Compatibility? {
        if (a == null || b == null) return null
        val shift = abs(tempoRatio(a, b) - 1f)
        val bpmScore = when {
            shift <= 0.02f -> 1f
            shift <= 0.04f -> 0.85f
            shift <= MAX_TEMPO_SHIFT -> 0.6f
            shift <= 0.12f -> 0.3f
            else -> 0.1f
        }
        val ca = camelot(a); val cb = camelot(b)
        val keyScore = if (ca == null || cb == null) 0.5f else {
            val diff = ((ca.first - cb.first) % 12 + 12) % 12
            val step = minOf(diff, 12 - diff)
            when {
                step == 0 && ca.second == cb.second -> 1f
                step == 0 -> 0.85f   // relativa mayor/menor
                step == 1 && ca.second == cb.second -> 0.85f
                step == 2 && ca.second == cb.second -> 0.55f
                step == 1 -> 0.45f
                else -> 0.2f
            }
        }
        return Compatibility(bpmScore * 0.5f + keyScore * 0.5f, bpmScore, keyScore)
    }

    fun beatMs(a: TrackAnalysis) = if (a.bpm > 0) 60_000.0 / a.bpm else 0.0
    fun barMs(a: TrackAnalysis) = beatMs(a) * 4

    /** Último pulso de [a] en o antes de [t]. */
    fun beatAtOrBefore(a: TrackAnalysis, t: Long): Long {
        val p = beatMs(a); if (p <= 0) return t
        val k = floor((t - a.beatOffsetMs) / p)
        return (a.beatOffsetMs + k * p).toLong().coerceAtLeast(0)
    }

    /** Primer pulso de [a] en o después de [t]. */
    fun beatAtOrAfter(a: TrackAnalysis, t: Long): Long {
        val p = beatMs(a); if (p <= 0) return t
        val k = ceil((t - a.beatOffsetMs) / p)
        return (a.beatOffsetMs + k * p).toLong().coerceAtLeast(0)
    }

    /** Pulso de [a] más cercano a [t]. */
    fun nearestBeat(a: TrackAnalysis, t: Long): Long {
        val before = beatAtOrBefore(a, t); val after = beatAtOrAfter(a, t)
        return if (t - before <= after - t) before else after
    }

    private inline fun <reified E : Enum<E>> parse(value: String?): E? =
        value?.takeIf { it != "AUTO" }?.let { v -> enumValues<E>().firstOrNull { it.name == v } }

    fun plan(
        a: TrackAnalysis?,
        b: TrackAnalysis?,
        durationA: Long,
        durationB: Long,
        settings: MixSettings,
        override: MixTransitionEntity?,
    ): TransitionPlan? {
        if (durationA < 20_000 || durationB < 10_000) return null
        val style = parse<MixStyle>(override?.style) ?: settings.style
        val recipe = style.recipe()
        val volume = parse<VolumeCurve>(override?.volume) ?: recipe.volume
        val eq = parse<EqMode>(override?.eq) ?: recipe.eq
        val effect = parse<MixEffect>(override?.effect) ?: recipe.effect
        val fx = parse<MixFx>(override?.fx) ?: recipe.fx
        val haveBpm = a != null && b != null && a.bpm > 0 && b.bpm > 0
        val wantSync = when (override?.beatSync) {
            1 -> true
            0 -> false
            else -> recipe.beatSync && settings.beatSync
        }
        val canSync = wantSync && haveBpm && abs(tempoRatio(a!!, b!!) - 1f) <= MAX_TEMPO_SHIFT

        // Duración: en compases si hay BPM; si no, en milisegundos.
        var bars = 0
        var duration: Long
        val manualBars = override?.bars?.takeIf { it > 0 }
        if (a != null && a.bpm > 0 && (manualBars != null || canSync)) {
            bars = manualBars ?: (override?.durationMs?.takeIf { it > 0 }?.let { (it / barMs(a)).toInt() }
                ?: (if (style == MixStyle.SMOOTH || style == MixStyle.FADE) (settings.durationMs / barMs(a)).toInt() else recipe.bars))
            bars = BAR_OPTIONS.minBy { abs(it - bars.coerceAtLeast(1)) }
            duration = (bars * barMs(a)).toLong()
        } else {
            duration = override?.durationMs?.takeIf { it > 0 } ?: when (style) {
                MixStyle.SMOOTH -> (settings.durationMs * 1.25).toLong()
                MixStyle.BLEND -> settings.durationMs * 2
                MixStyle.VINYL -> 2_500L
                else -> settings.durationMs
            }
        }
        duration = duration.coerceIn(MIN_DURATION, minOf(45_000L, durationA / 3, durationB / 2))

        val usefulEndA = (a?.outroStartMs?.takeIf { it > durationA / 2 } ?: (durationA - 500)).coerceAtMost(durationA - 300)
        var exit = override?.exitMs?.takeIf { it >= 0 } ?: (usefulEndA - duration)
        exit = exit.coerceIn(durationA / 3, durationA - duration - 300)
        if (canSync && a != null) exit = beatAtOrBefore(a, exit).coerceAtLeast(durationA / 3)

        var entry = override?.entryMs?.takeIf { it >= 0 } ?: (b?.introEndMs ?: 0L)
        entry = entry.coerceIn(0, durationB / 2)
        if (canSync && b != null) entry = beatAtOrAfter(b, entry).coerceAtMost(durationB / 2)

        val speed = if (canSync) tempoRatio(a!!, b!!) else 1f
        val gainMatch = if (settings.autoGain && a != null && b != null && b.energy > 0.01f) {
            (a.energy / b.energy).coerceIn(0.5f, 1f)
        } else 1f

        // Bucle: solo con BPM conocido (debe durar beats exactos para no perder el compás).
        val loopBeats = (override?.loopBeats?.takeIf { it >= 0 } ?: recipe.loopBeats)
            .takeIf { a != null && a.bpm > 0 } ?: 0
        val loopMs = if (loopBeats > 0 && a != null) (loopBeats * beatMs(a)).toLong().coerceAtMost(15_000L) else 0L
        val echoDelay = a?.takeIf { it.bpm > 0 }?.let { (beatMs(it) * 0.75).toFloat() } ?: 375f

        return TransitionPlan(
            style = style,
            volume = volume,
            eq = eq,
            effect = effect,
            fx = fx,
            exitMs = exit,
            entryMs = entry,
            durationMs = duration,
            bars = bars,
            speedB = speed,
            bpmSynced = canSync,
            gainMatchB = gainMatch,
            loopBeats = if (loopMs > 0) loopBeats else 0,
            loopMs = loopMs,
            echoDelayMs = echoDelay,
            custom = override != null,
        )
    }

    /** Barrido exponencial de frecuencia entre [from] y [to] Hz. */
    private fun sweep(from: Float, to: Float, t: Float): Float = (from * (to / from).pow(t.coerceIn(0f, 1f)))

    private fun ramp(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

    /** Curvas de ganancia, filtros y efectos en el progreso [p] (0..1) de la transición. */
    fun frame(plan: TransitionPlan, p: Float): MixFrame {
        val t = p.coerceIn(0f, 1f)
        val match = plan.gainMatchB + (1f - plan.gainMatchB) * t
        val (ga, gb) = when (plan.volume) {
            VolumeCurve.LINEAR -> (1f - t) to t
            VolumeCurve.SMOOTH -> cos(t * PI / 2).toFloat() to sin(t * PI / 2).toFloat()
            VolumeCurve.OVERLAP -> (1f - ramp(t, 0.6f, 1f)) to ramp(t, 0f, 0.4f)
            VolumeCurve.FAST_IN -> cos(ramp(t, 0.15f, 1f) * PI / 2).toFloat() to ramp(t, 0f, 0.2f)
            VolumeCurve.SLOW_OUT -> (1f - ramp(t, 0.8f, 1f)) to sin(t * PI / 2).toFloat()
            VolumeCurve.CUT -> (if (t < 0.5f) 1f else 0f) to (if (t < 0.5f) 0f else 1f)
        }
        var bassA = 0f; var bassB = 0f; var trebleA = 0f; var trebleB = 0f
        when (plan.eq) {
            EqMode.NONE -> Unit
            EqMode.BASS_SWAP -> {
                val swap = ramp(t, 0.45f, 0.55f)
                bassA = -30f * swap
                bassB = -30f * (1f - swap)
            }
            EqMode.BASS_CUT_IN -> {
                val back = ramp(t, 0.75f, 1f)
                bassB = -30f * (1f - back)
                bassA = -30f * back
            }
            EqMode.BASS_CUT_OUT -> {
                bassA = -30f * ramp(t, 0f, 0.15f)
            }
            EqMode.FULL_SWAP -> {
                val bass = ramp(t, 0.45f, 0.55f)
                val highs = ramp(t, 0.2f, 0.35f)
                bassA = -30f * bass; bassB = -30f * (1f - bass)
                trebleA = -18f * highs; trebleB = -18f * (1f - highs)
            }
        }
        var lowPassA = FILTER_OFF_LP
        var highPassB = FILTER_OFF_HP
        when (plan.effect) {
            MixEffect.NONE -> Unit
            MixEffect.LOW_PASS_OUT -> lowPassA = sweep(FILTER_OFF_LP, 250f, t)
            MixEffect.HIGH_PASS_IN -> highPassB = if (t >= 0.98f) FILTER_OFF_HP else sweep(3_000f, 20f, t)
            MixEffect.FILTER_SWEEP -> {
                lowPassA = sweep(FILTER_OFF_LP, 250f, t)
                highPassB = if (t >= 0.98f) FILTER_OFF_HP else sweep(3_000f, 20f, t)
            }
        }
        var echoSend = 0f
        var speedA = 1f
        var gainA = ga
        when (plan.fx) {
            MixFx.NONE -> Unit
            // El eco crece en la primera mitad; la cola sigue sonando aunque A ya no se oiga.
            MixFx.ECHO_OUT -> echoSend = ramp(t, 0f, 0.5f) * (1f - ramp(t, 0.85f, 1f))
            // El frenado ocupa el tramo de salida de A: velocidad y tono bajan juntos.
            MixFx.VINYL_BRAKE -> {
                val brake = ramp(t, 0f, 0.5f)
                speedA = (1f - brake * brake * 0.92f).coerceAtLeast(0.08f)
                gainA = minOf(gainA, 1f - ramp(t, 0.4f, 0.55f))
            }
        }
        return MixFrame(
            gainA = gainA.coerceIn(0f, 1f),
            gainB = (gb * match).coerceIn(0f, 1f),
            bassA = bassA,
            bassB = bassB,
            trebleA = trebleA,
            trebleB = trebleB,
            lowPassA = lowPassA,
            highPassB = highPassB,
            echoSendA = echoSend,
            speedA = speedA,
        )
    }

    /**
     * Reordena canciones para mezcla armónica: empieza por la primera y elige siempre
     * la siguiente más compatible (BPM y tonalidad) entre las restantes.
     */
    fun <T> harmonicOrder(items: List<T>, analysisOf: (T) -> TrackAnalysis?): List<T> {
        if (items.size < 3) return items
        val remaining = items.toMutableList()
        val result = ArrayList<T>(items.size)
        var current = remaining.removeAt(0)
        result += current
        while (remaining.isNotEmpty()) {
            val ca = analysisOf(current)
            val next = remaining.maxBy { compatibility(ca, analysisOf(it))?.score ?: 0f }
            remaining.remove(next)
            result += next
            current = next
        }
        return result
    }
}
