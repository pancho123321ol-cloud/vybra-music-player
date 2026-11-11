package com.vybra.music.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.vybra.music.data.analysis.TrackAnalyzer
import com.vybra.music.data.db.TrackWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Colores de la forma de onda de 3 bandas. */
val WaveLow = Color(0xFF3D5CFF)
val WaveMid = Color(0xFFFF9B3D)
val WaveHigh = Color(0xFFF6F2FF)
val EditorBg = Color(0xFF121214)

/** Lectura de la forma de onda empaquetada (pico, graves, medios, agudos por bloque). */
class WaveReader(private val wave: TrackWaveform) {
    private val stride = if (wave.data.size % TrackAnalyzer.WAVEFORM_STRIDE == 0 && wave.data.size >= 64) TrackAnalyzer.WAVEFORM_STRIDE else 1
    val blocks = wave.data.size / stride
    val hopMs = wave.hopMs

    /** Valor lineal 0..1 (se guarda en escala raíz para no perder detalle en lo bajo). */
    private fun v(i: Int, band: Int): Float {
        if (i < 0 || i >= blocks) return 0f
        val b = if (stride == 1) 0 else band
        val s = (wave.data[i * stride + b].toInt() and 0xFF) / 255f
        return s * s
    }

    /** Máximo de cada banda en el intervalo [fromMs, toMs) (conserva los golpes al reducir). */
    fun range(fromMs: Double, toMs: Double, out: FloatArray) {
        val i0 = (fromMs / hopMs).toInt()
        val i1 = max(i0 + 1, ceil(toMs / hopMs).toInt())
        var l = 0f; var m = 0f; var h = 0f
        for (i in i0 until i1) {
            l = max(l, v(i, 1)); m = max(m, v(i, 2)); h = max(h, v(i, 3))
        }
        out[0] = l; out[1] = m; out[2] = h
    }
}

/** Un trozo de la forma de onda ya dibujado: cubre el tramo [startMs, endMs) de la canción. */
class WaveTile(val startMs: Double, val endMs: Double, val image: ImageBitmap)

/** Forma de onda de una canción completa, dividida en imágenes de 2048 px. */
class WaveTiles(val pxPerMs: Double, val tiles: List<WaveTile>)

private const val TILE_PX = 2048
/** Ancho máximo total; las canciones largas se dibujan a menor resolución. */
private const val MAX_TOTAL_PX = 16_000

/**
 * Dibuja una única vez la forma de onda de 3 bandas (graves azul, medios naranja,
 * agudos blanco) en imágenes. Después, mover la onda es solo desplazar esas imágenes.
 */
suspend fun renderWaveTiles(wave: TrackWaveform, durationMs: Long, pxPerMs: Double, heightPx: Int): WaveTiles =
    withContext(Dispatchers.Default) {
        val reader = WaveReader(wave)
        val total = durationMs * pxPerMs
        val scale = max(1.0, total / MAX_TOTAL_PX)          // px de pantalla por px dibujado
        val renderPx = ceil(total / scale).toInt().coerceAtLeast(1)
        val msPerPx = scale / pxPerMs
        val half = heightPx / 2f
        val paint = Paint().apply { strokeWidth = 1f; isAntiAlias = false }
        val bg = Paint().apply { color = EditorBg }
        val low = WaveLow; val mid = WaveMid; val high = WaveHigh
        val bands = FloatArray(3)
        val tiles = ArrayList<WaveTile>()
        var x0 = 0
        while (x0 < renderPx) {
            coroutineContext.ensureActive()
            val w = min(TILE_PX, renderPx - x0)
            val bmp = ImageBitmap(w, heightPx)
            val canvas = Canvas(bmp)
            canvas.drawRect(0f, 0f, w.toFloat(), heightPx.toFloat(), bg)
            for (x in 0 until w) {
                val t = (x0 + x) * msPerPx
                reader.range(t, t + msPerPx, bands)
                val fx = x + 0.5f
                // Capas: graves (azul) por fuera, medios (naranja) como cuerpo, agudos (blanco) en el centro.
                val hl = bands[0].coerceAtMost(1f) * half * 0.96f
                val hm = minOf(bands[1] * 0.82f, 1f) * half * 0.9f
                val hh = minOf(bands[2] * 0.5f, 1f) * half * 0.6f
                paint.color = low
                if (hl > 0.5f) canvas.drawLine(Offset(fx, half - hl), Offset(fx, half + hl), paint)
                paint.color = mid
                if (hm > 0.5f) canvas.drawLine(Offset(fx, half - hm), Offset(fx, half + hm), paint)
                paint.color = high
                if (hh > 0.5f) canvas.drawLine(Offset(fx, half - hh), Offset(fx, half + hh), paint)
            }
            tiles += WaveTile(x0 * msPerPx, (x0 + w) * msPerPx, bmp)
            x0 += w
        }
        WaveTiles(pxPerMs, tiles)
    }

/** Vista general de toda la canción (minimapa), en una sola imagen pequeña. */
suspend fun renderOverview(wave: TrackWaveform, durationMs: Long, widthPx: Int, heightPx: Int): ImageBitmap =
    withContext(Dispatchers.Default) {
        val reader = WaveReader(wave)
        val bmp = ImageBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        val canvas = Canvas(bmp)
        val paint = Paint().apply { strokeWidth = 1f }
        val msPerPx = durationMs.toDouble() / widthPx
        val bands = FloatArray(3)
        val bottom = heightPx.toFloat()
        for (x in 0 until widthPx) {
            reader.range(x * msPerPx, (x + 1) * msPerPx, bands)
            val fx = x + 0.5f
            paint.color = WaveLow.copy(alpha = 0.85f)
            canvas.drawLine(Offset(fx, bottom), Offset(fx, bottom - bands[0] * bottom), paint)
            paint.color = WaveMid.copy(alpha = 0.85f)
            canvas.drawLine(Offset(fx, bottom), Offset(fx, bottom - bands[1] * bottom * 0.8f), paint)
            paint.color = WaveHigh.copy(alpha = 0.7f)
            canvas.drawLine(Offset(fx, bottom), Offset(fx, bottom - bands[2] * bottom * 0.5f), paint)
        }
        bmp
    }
