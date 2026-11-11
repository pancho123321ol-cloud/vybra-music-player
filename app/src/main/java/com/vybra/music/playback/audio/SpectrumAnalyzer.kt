package com.vybra.music.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT radix-2 in situ (partes real e imaginaria separadas). */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
}

/** Instantánea del espectro de audio para los visualizadores. */
class SpectrumFrame(
    val timeNanos: Long,
    /** Bandas logarítmicas 0..1 (graves → agudos). */
    val bands: FloatArray,
    val bass: Float,
    val mid: Float,
    val treble: Float,
)

/**
 * Recibe muestras mono desde [VybraAudioProcessor] (hilo de audio) y publica
 * espectros con marca de tiempo. Solo trabaja mientras [enabled] es true, es decir,
 * mientras un visualizador está en pantalla.
 */
class SpectrumAnalyzer {
    @Volatile var enabled = false
        private set
    private var users = 0

    private val size = 1024
    private val window = Fft.hann(size)
    private val ring = FloatArray(size)
    private var ringPos = 0
    private var sinceLast = 0
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    private val history = arrayOfNulls<SpectrumFrame>(64)
    private var historyPos = 0
    private val lock = Any()

    @Synchronized fun acquire() { users++; enabled = true }
    @Synchronized fun release() { users = max(0, users - 1); enabled = users > 0 }

    /** Llamado desde el hilo de audio por cada muestra (mezcla mono, -1..1). */
    fun push(sample: Float, sampleRate: Int) {
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % size
        if (++sinceLast >= size / 2) {
            sinceLast = 0
            compute(sampleRate)
        }
    }

    private fun compute(sampleRate: Int) {
        for (i in 0 until size) {
            re[i] = ring[(ringPos + i) % size] * window[i]
            im[i] = 0f
        }
        Fft.transform(re, im)
        val half = size / 2
        val binHz = sampleRate.toFloat() / size
        val bands = FloatArray(BANDS)
        // Bandas logarítmicas entre 40 Hz y 16 kHz.
        val fMin = 40.0
        val fMax = min(16_000.0, sampleRate / 2.0)
        for (b in 0 until BANDS) {
            val lo = fMin * (fMax / fMin).pow(b.toDouble() / BANDS)
            val hi = fMin * (fMax / fMin).pow((b + 1).toDouble() / BANDS)
            val i0 = (lo / binHz).toInt().coerceIn(1, half - 1)
            val i1 = (hi / binHz).toInt().coerceIn(i0 + 1, half)
            var peak = 0f
            for (k in i0 until i1) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            bands[b] = toLevel(peak)
        }
        fun rangeLevel(f0: Float, f1: Float): Float {
            val i0 = (f0 / binHz).toInt().coerceIn(1, half - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0 + 1, half)
            var sum = 0f
            for (k in i0 until i1) sum += re[k] * re[k] + im[k] * im[k]
            return toLevel(sqrt(sum / (i1 - i0)))
        }
        val frame = SpectrumFrame(
            System.nanoTime(),
            bands,
            bass = rangeLevel(30f, 160f),
            mid = rangeLevel(400f, 2_500f),
            treble = rangeLevel(4_000f, 12_000f),
        )
        synchronized(lock) {
            history[historyPos] = frame
            historyPos = (historyPos + 1) % history.size
        }
    }

    /** Magnitud FFT → nivel 0..1 en escala logarítmica (~60 dB de rango). */
    private fun toLevel(mag: Float): Float {
        val db = 20 * ln(max(mag, 1e-6f) / (size / 4f)) / ln(10f)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    /** Espectro que se está oyendo ahora, compensando la latencia del búfer de audio. */
    fun frameAt(nowNanos: Long, latencyMs: Long = DEFAULT_LATENCY_MS): SpectrumFrame? {
        val target = nowNanos - latencyMs * 1_000_000
        synchronized(lock) {
            var best: SpectrumFrame? = null
            for (f in history) {
                if (f == null || f.timeNanos > target) continue
                if (best == null || f.timeNanos > best.timeNanos) best = f
            }
            // Si el audio se detuvo hace tiempo, no hay nada que mostrar.
            return best?.takeIf { target - it.timeNanos < 400_000_000L }
        }
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)

    companion object {
        const val BANDS = 32
        const val DEFAULT_LATENCY_MS = 260L
    }
}
