package com.vybra.music.playback.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ecualizador gráfico de 5 bandas + refuerzo de graves, por software (las mismas bandas
 * y rangos que el ecualizador de Android: niveles en milibelios, -1500..1500).
 */
class SoftwareEqualizer(private val sampleRate: Int = Deck.SAMPLE_RATE) {
    val centers = intArrayOf(60, 230, 910, 3_600, 14_000)
    val minLevel = -1500
    val maxLevel = 1500

    @Volatile var enabled = false
    private val levels = IntArray(centers.size)
    @Volatile private var bassStrength = 0
    @Volatile private var dirty = true

    private val filters = Array(centers.size + 1) { Array(2) { Filter() } }
    private var active = BooleanArray(centers.size + 1)
    private var preGain = 1f

    fun setLevel(band: Int, millibels: Int) {
        if (band !in levels.indices) return
        levels[band] = millibels.coerceIn(minLevel, maxLevel)
        dirty = true
    }

    fun level(band: Int) = levels.getOrElse(band) { 0 }

    /** Fuerza 0..1000, como BassBoost de Android. */
    fun setBassStrength(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        dirty = true
    }

    fun bassStrength() = bassStrength

    private fun rebuild() {
        dirty = false
        var maxBoost = 0.0
        for (b in centers.indices) {
            val db = levels[b] / 100.0
            active[b] = db != 0.0
            if (db > maxBoost) maxBoost = db
            filters[b].forEach { it.peaking(sampleRate, centers[b].toDouble(), 1.1, db) }
        }
        val bassDb = bassStrength / 1000.0 * 12.0
        active[centers.size] = bassDb > 0
        filters[centers.size].forEach { it.lowShelf(sampleRate, 110.0, bassDb) }
        maxBoost = maxOf(maxBoost, bassDb * 0.8)
        // Margen previo para que los realces no saturen.
        preGain = 10.0.pow(-maxBoost * 0.5 / 20.0).toFloat()
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!enabled) return
        if (dirty) rebuild()
        if (active.none { it }) return
        val g = preGain
        for (i in 0 until frames) {
            for (c in 0 until 2) {
                var s = buf[i * 2 + c] * g
                for (b in filters.indices) if (active[b]) s = filters[b][c].process(s)
                buf[i * 2 + c] = s
            }
        }
    }

    private class Filter {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun process(x: Float): Float {
            val xin = x.toDouble()
            val y = b0 * xin + z1
            z1 = b1 * xin - a1 * y + z2
            z2 = b2 * xin - a2 * y
            return y.toFloat()
        }

        fun peaking(fs: Int, f0: Double, q: Double, db: Double) {
            val a = 10.0.pow(db / 40.0)
            val w0 = 2 * PI * minOf(f0, fs * 0.45) / fs
            val alpha = sin(w0) / (2 * q)
            val cosW = cos(w0)
            val a0 = 1 + alpha / a
            b0 = (1 + alpha * a) / a0; b1 = -2 * cosW / a0; b2 = (1 - alpha * a) / a0
            a1 = -2 * cosW / a0; a2 = (1 - alpha / a) / a0
        }

        fun lowShelf(fs: Int, f0: Double, db: Double) {
            val a = 10.0.pow(db / 40.0)
            val w0 = 2 * PI * f0 / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0)
            val sq = 2 * sqrt(a) * alpha
            val na0 = (a + 1) + (a - 1) * cosW + sq
            b0 = a * ((a + 1) - (a - 1) * cosW + sq) / na0
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / na0
            b2 = a * ((a + 1) - (a - 1) * cosW - sq) / na0
            a1 = -2 * ((a - 1) + (a + 1) * cosW) / na0
            a2 = ((a + 1) + (a - 1) * cosW - sq) / na0
        }
    }

    companion object {
        /** Presets equivalentes a los del ecualizador estándar de Android (milibelios por banda). */
        val PRESETS: List<Pair<String, IntArray>> = listOf(
            "Normal" to intArrayOf(300, 0, 0, 0, 300),
            "Clásica" to intArrayOf(500, 300, -200, 400, 400),
            "Dance" to intArrayOf(600, 0, 200, 400, 100),
            "Plano" to intArrayOf(0, 0, 0, 0, 0),
            "Folk" to intArrayOf(300, 0, 0, 200, -100),
            "Heavy metal" to intArrayOf(400, 100, 900, 300, 0),
            "Hip hop" to intArrayOf(500, 300, 0, 100, 300),
            "Jazz" to intArrayOf(400, 200, -200, 200, 500),
            "Pop" to intArrayOf(-100, 200, 500, 100, -200),
            "Rock" to intArrayOf(500, 300, -100, 300, 500),
        )
    }
}
