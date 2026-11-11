package com.vybra.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vybra.music.appContainer
import com.vybra.music.data.prefs.VisualMode
import com.vybra.music.data.prefs.VisualPalette
import com.vybra.music.data.prefs.VisualSettings
import com.vybra.music.playback.audio.SpectrumAnalyzer
import com.vybra.music.ui.components.rememberArtworkPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

private val Base = Color(0xFF0A0A0C)
private val FallbackPalette = listOf(Color(0xFF3A3A44), Color(0xFF26262E), Color(0xFF4A4458), Color(0xFF2E3440))
private val NeonPalette = listOf(Color(0xFFFF2E88), Color(0xFF7B2FF7), Color(0xFF00E5FF), Color(0xFF2962FF))
private val MonoPalette = listOf(Color(0xFF6B6B76), Color(0xFF3A3A42), Color(0xFF9A9AA6), Color(0xFF26262C))

/** Niveles de audio suavizados que leen los efectos en cada fotograma. */
class AudioLevels {
    var bass = 0f; var mid = 0f; var treble = 0f; var energy = 0f
    /** 1 en cada golpe detectado; decae exponencialmente. */
    var pulse = 0f
    val bands = FloatArray(SpectrumAnalyzer.BANDS)
    private var avgBass = 0f
    private var lastBeatNanos = 0L

    fun update(analyzer: SpectrumAnalyzer, now: Long, dt: Float) {
        val f = analyzer.frameAt(now)
        val tb = f?.bass ?: 0f; val tm = f?.mid ?: 0f; val tt = f?.treble ?: 0f
        // Ataque rápido, caída lenta.
        fun follow(cur: Float, target: Float) = if (target > cur) cur + (target - cur) * 0.6f else cur + (target - cur) * (1 - exp(-dt / 0.25f))
        val prevBass = bass
        bass = follow(bass, tb); mid = follow(mid, tm); treble = follow(treble, tt)
        energy = (bass * 0.5f + mid * 0.3f + treble * 0.2f)
        for (i in bands.indices) bands[i] = follow(bands[i], f?.bands?.get(i) ?: 0f)
        avgBass += (bass - avgBass) * (1 - exp(-dt / 1.2f))
        if (bass - avgBass > 0.06f && bass > prevBass && now - lastBeatNanos > 280_000_000L) {
            pulse = 1f
            lastBeatNanos = now
        } else {
            pulse *= exp(-dt / 0.22f)
        }
    }
}

/**
 * Fondo inmersivo del reproductor y de las letras. Según los ajustes muestra un
 * degradado de la carátula, degradados animados (Ambient), efectos al ritmo (Pulse)
 * o el espectro de frecuencias (Spectrum). Solo anima mientras está visible.
 */
@Composable
fun PlayerBackground(
    songId: Long,
    albumId: Long,
    modifier: Modifier = Modifier,
    overrideSettings: VisualSettings? = null,
) {
    val container = LocalContext.current.appContainer
    val stored by container.settings.visuals.collectAsStateWithLifecycle(initialValue = VisualSettings())
    val vis = overrideSettings ?: stored
    val accent = MaterialTheme.colorScheme.primary
    val artPalette = rememberArtworkPalette(songId, albumId, FallbackPalette)
    val colors = when (vis.palette) {
        VisualPalette.ARTWORK -> artPalette
        VisualPalette.ACCENT -> listOf(accent, lerp(accent, Color.Black, 0.45f), lerp(accent, Color.White, 0.25f), lerp(accent, Color(0xFF3B1E6E), 0.5f))
        VisualPalette.NEON -> NeonPalette
        VisualPalette.MONO -> MonoPalette
    }
    val mode = if (vis.enabled) vis.mode else VisualMode.ARTWORK
    val animated = vis.enabled && mode in setOf(VisualMode.AMBIENT, VisualMode.PULSE, VisualMode.SPECTRUM)
    val reactive = vis.enabled && mode in setOf(VisualMode.AMBIENT, VisualMode.PULSE, VisualMode.SPECTRUM)
    val levels = remember { AudioLevels() }
    val clock = rememberVisualClock(animated, reactive, container.spectrum, levels)
    val speed = 0.15f + vis.speed * 1.6f
    val intensity = vis.intensity.coerceIn(0f, 1f)

    Canvas(modifier.fillMaxSize()) {
        drawRect(Base)
        val t = clock.value / 1_000_000_000f * speed
        when (mode) {
            VisualMode.MINIMAL -> drawMinimal(colors[0])
            VisualMode.ARTWORK -> drawArtworkGradient(colors[0])
            VisualMode.AMBIENT -> {
                drawBlobs(colors, t, intensity, levels.energy * 0.6f)
                drawSparkles(colors, t, levels.treble, intensity)
            }
            VisualMode.PULSE -> {
                drawBlobs(colors, t, intensity, levels.bass)
                drawPulse(colors, levels, intensity)
                drawSparkles(colors, t, levels.treble, intensity)
            }
            VisualMode.SPECTRUM -> {
                drawBlobs(colors, t, intensity * 0.7f, levels.energy * 0.4f)
                drawSparkles(colors, t, levels.treble, intensity * 0.8f)
            }
        }
        // Velo inferior para mantener legibles los controles.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.45f to Base.copy(alpha = 0.15f),
                1f to Base.copy(alpha = 0.85f),
            ),
        )
        // Las barras del espectro van por encima del velo para que se vean nítidas.
        if (mode == VisualMode.SPECTRUM) drawSpectrum(colors, levels, intensity)
    }
}

/**
 * Reloj de fotogramas (~60 fps) que solo corre con la pantalla visible y la app en
 * primer plano. Mientras corre, mantiene activo el análisis espectral si [reactive].
 */
@Composable
private fun rememberVisualClock(
    animated: Boolean,
    reactive: Boolean,
    analyzer: SpectrumAnalyzer,
    levels: AudioLevels,
): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animated, reactive, lifecycle) {
        if (!animated) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (reactive) analyzer.acquire()
            try {
                var last = 0L
                var elapsed = clock.longValue
                while (true) {
                    withFrameNanos { now ->
                        if (last == 0L) last = now
                        val dtNanos = now - last
                        // Limita a ~60 fps aunque la pantalla sea de 90/120 Hz.
                        if (dtNanos >= 15_000_000L || clock.longValue == 0L) {
                            val dt = (dtNanos / 1e9f).coerceIn(0f, 0.1f)
                            if (reactive) levels.update(analyzer, System.nanoTime(), dt)
                            elapsed += dtNanos
                            clock.longValue = elapsed + 1
                            last = now
                        }
                    }
                }
            } finally {
                if (reactive) analyzer.release()
            }
        }
    }
    return clock
}

private fun DrawScope.drawMinimal(c: Color) {
    drawRect(Brush.radialGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent), Offset(size.width / 2, 0f), size.maxDimension * 0.8f))
}

private fun DrawScope.drawArtworkGradient(c: Color) {
    drawRect(
        Brush.verticalGradient(
            0f to lerp(c, Color.Black, 0.25f),
            0.55f to lerp(c, Color.Black, 0.7f),
            1f to Base,
        ),
    )
}

/** Manchas de color difusas que se desplazan lentamente (degradado tipo malla). */
private fun DrawScope.drawBlobs(colors: List<Color>, t: Float, intensity: Float, boost: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(lerp(colors[0], Color.Black, 0.55f), Base)))
    for (i in 0 until 4) {
        val fi = i.toFloat()
        val cx = w * (0.5f + 0.38f * sin(t * 0.21f * (1 + fi * 0.37f) + fi * 1.7f))
        val cy = h * (0.32f + 0.26f * cos(t * 0.17f * (1 + fi * 0.29f) + fi * 2.1f))
        val r = max(w, h) * (0.42f + 0.08f * sin(t * 0.3f + fi)) * (1f + boost * 0.35f * intensity)
        val alpha = (0.35f + 0.35f * intensity + boost * 0.2f).coerceAtMost(0.9f)
        drawCircle(
            Brush.radialGradient(listOf(colors[i % colors.size].copy(alpha = alpha), Color.Transparent), Offset(cx, cy), r),
            radius = r, center = Offset(cx, cy),
        )
    }
}

/** Resplandor y anillo que laten con cada golpe de graves. */
private fun DrawScope.drawPulse(colors: List<Color>, levels: AudioLevels, intensity: Float) {
    val center = Offset(size.width / 2, size.height * 0.4f)
    val base = size.width * 0.5f
    val p = levels.pulse
    val glowR = base * (1f + p * 0.35f * intensity + levels.bass * 0.15f)
    drawCircle(
        Brush.radialGradient(listOf(colors[0].copy(alpha = 0.25f + 0.45f * p * intensity), Color.Transparent), center, glowR),
        radius = glowR, center = center,
    )
    if (p > 0.05f) {
        val ringR = base * (0.75f + (1 - p) * 0.6f)
        drawCircle(
            colors[2 % colors.size].copy(alpha = p * 0.5f * intensity),
            radius = ringR, center = center,
            style = Stroke(width = 6f + 10f * p),
        )
    }
}

/** Barras del espectro (simétricas) en la parte inferior. */
private fun DrawScope.drawSpectrum(colors: List<Color>, levels: AudioLevels, intensity: Float) {
    val bands = levels.bands
    val n = bands.size * 2
    val w = size.width; val h = size.height
    val gap = w * 0.006f
    val barW = (w - gap * (n + 1)) / n
    val maxH = h * (0.12f + 0.16f * intensity)
    val bottom = h * 0.97f
    val brush = Brush.verticalGradient(listOf(colors[0], colors[1 % colors.size]), startY = bottom - maxH, endY = bottom)
    for (i in 0 until n) {
        // Graves en el centro, agudos hacia los lados.
        val b = if (i < bands.size) bands[bands.size - 1 - i] else bands[i - bands.size]
        val bh = max(barW * 0.6f, b * b * maxH)
        val x = gap + i * (barW + gap)
        drawRoundRect(brush, Offset(x, bottom - bh), Size(barW, bh), CornerRadius(barW / 2), alpha = 0.55f + 0.35f * intensity)
    }
    // Brillo de graves detrás de la carátula.
    val c = Offset(w / 2, h * 0.4f)
    val r = w * (0.45f + levels.bass * 0.3f)
    drawCircle(Brush.radialGradient(listOf(colors[0].copy(alpha = 0.18f + levels.bass * 0.3f * intensity), Color.Transparent), c, r), r, c)
}

/** Destellos que brillan con las frecuencias agudas. */
private fun DrawScope.drawSparkles(colors: List<Color>, t: Float, treble: Float, intensity: Float) {
    val level = (treble * treble * intensity).coerceIn(0f, 1f)
    if (level < 0.02f) return
    val w = size.width; val h = size.height
    for (i in 0 until 36) {
        val sx = ((i * 0.618034f) % 1f)
        val sy = ((i * 0.381966f + 0.13f) % 1f)
        val speedY = 0.008f + (i % 5) * 0.004f
        val x = ((sx + 0.01f * sin(t + i)) % 1f) * w
        val y = (((sy - t * speedY) % 1f + 1f) % 1f) * h
        val tw = 0.5f + 0.5f * sin(t * 3f + i * 1.3f)
        drawCircle(
            lerp(Color.White, colors[i % colors.size], 0.3f).copy(alpha = level * tw * 0.8f),
            radius = 1.5f + 2.5f * tw * level * (1 + (i % 3)),
            center = Offset(x, y),
        )
    }
}
