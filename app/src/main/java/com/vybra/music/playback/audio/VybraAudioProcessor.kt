package com.vybra.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procesador de audio PCM 16 bits insertado en la cadena de ExoPlayer:
 * - ganancia con rampa suave por muestra (transiciones del mix, sin "clics"),
 * - filtros shelving de graves y agudos (intercambio de graves estilo DJ),
 * - filtros paso bajo/alto, bucles exactos al pulso y eco con realimentación,
 * - análisis espectral (FFT) para los visualizadores, solo cuando se solicita.
 */
@UnstableApi
class VybraAudioProcessor(private val analyzer: SpectrumAnalyzer) : BaseAudioProcessor() {

    /** Ganancia objetivo 0..1 (se alcanza suavemente en ~15 ms). */
    @Volatile var targetGain: Float = 1f

    /** Ganancia de graves en dB (shelf a 180 Hz), p. ej. -30 para cortar graves. */
    @Volatile var bassDb: Float = 0f

    /** Ganancia de agudos en dB (shelf a 4 kHz). */
    @Volatile var trebleDb: Float = 0f

    /** Corte del filtro paso bajo en Hz (≥ 20 000 = desactivado). */
    @Volatile var lowPassHz: Float = 20_000f

    /** Corte del filtro paso alto en Hz (≤ 0 = desactivado). */
    @Volatile var highPassHz: Float = 0f

    /** Si este reproductor alimenta el visualizador (solo uno a la vez durante el mix). */
    @Volatile var feedAnalyzer: Boolean = true

    /** Guarda los últimos 16 s de audio para poder crear bucles exactos al pulso. */
    @Volatile var captureEnabled: Boolean = false

    /** Longitud del bucle en ms (> 0 lo activa desde el punto actual; 0 lo desactiva). */
    @Volatile var loopMs: Float = 0f

    /** Cantidad de señal enviada al eco 0..1 (0 = sin eco; la cola sigue sonando unos segundos). */
    @Volatile var echoSend: Float = 0f

    /** Retardo del eco en ms (normalmente 1/2 o 3/4 de pulso). */
    @Volatile var echoDelayMs: Float = 375f

    private var sampleRate = 44_100
    private var channels = 2
    private var gain = 1f
    private var gainCoef = 0.999f

    private val low = Array(MAX_CH) { Biquad() }
    private val high = Array(MAX_CH) { Biquad() }
    private val lp = Array(MAX_CH) { Biquad() }
    private val hp = Array(MAX_CH) { Biquad() }
    private var appliedLp = 20_000f
    private var appliedHp = 0f
    private var appliedBassDb = 0f
    private var appliedTrebleDb = 0f

    // Bucle: búfer circular de entrada (intercalado por canales).
    private var ring: ShortArray? = null
    private var ringFrames = 0
    private var ringWrite = 0
    private var ringFilled = 0
    private var loopActive = false
    private var loopStart = 0
    private var loopLen = 0
    private var loopIdx = 0

    // Eco: línea de retardo con realimentación.
    private var echoBuf: FloatArray? = null
    private var echoFrames = 0
    private var echoPos = 0
    private var echoTail = 0
    private val frame = FloatArray(MAX_CH)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate != sampleRate || inputAudioFormat.channelCount != channels) {
            ring = null; echoBuf = null
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        // Constante de tiempo ~15 ms para la rampa de ganancia.
        gainCoef = exp(-1.0 / (0.015 * sampleRate)).toFloat()
        updateFilters(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        updateFilters(force = false)
        val target = targetGain.coerceIn(0f, 1f)
        val sweeping = appliedLp < 19_000f || appliedHp > 15f
        val filtering = appliedBassDb != 0f || appliedTrebleDb != 0f || sweeping
        val analyze = analyzer.enabled && feedAnalyzer
        val ch = channels.coerceAtMost(MAX_CH)
        val inShorts = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = remaining / (2 * channels)
        val capture = captureEnabled
        val send = echoSend.coerceIn(0f, 1f)
        if (send > 0f) echoTail = sampleRate * 4
        val echo = send > 0f || echoTail > 0
        prepareLoop(capture)
        var g = gain
        val fast = !filtering && !capture && !loopActive && !echo && !analyze &&
            abs(g - target) < 1e-4f && abs(target - 1f) < 1e-4f
        if (fast) {
            out.put(inputBuffer)
            gain = target
            out.flip()
            return
        }
        if (echo) prepareEcho()
        val rb = ring
        val eb = echoBuf
        for (f in 0 until frames) {
            g = target + (g - target) * gainCoef
            for (c in 0 until channels) frame[c.coerceAtMost(MAX_CH - 1)] = inShorts.getShort().toFloat()

            // Bucle: se sustituye la entrada por el fragmento guardado (con fundido en la unión).
            if (loopActive && rb != null) {
                val base = (loopStart + loopIdx) % ringFrames
                val xfStart = loopLen - LOOP_XF
                for (c in 0 until ch) {
                    var s = rb[base * channels + c].toFloat()
                    if (loopIdx >= xfStart) {
                        val a = (loopIdx - xfStart).toFloat() / LOOP_XF
                        val preIdx = ((loopStart - (loopLen - loopIdx)) % ringFrames + ringFrames) % ringFrames
                        s = s * (1 - a) + rb[preIdx * channels + c] * a
                    }
                    frame[c] = s
                }
                loopIdx = (loopIdx + 1) % loopLen
            } else if (capture && rb != null) {
                for (c in 0 until ch) rb[ringWrite * channels + c] = frame[c].toInt().toShort()
                ringWrite = (ringWrite + 1) % ringFrames
                if (ringFilled < ringFrames) ringFilled++
            }

            var mono = 0f
            for (c in 0 until channels) {
                val idx = c.coerceAtMost(MAX_CH - 1)
                var s = frame[idx]
                if (c < ch && filtering) {
                    s = low[c].process(s)
                    s = high[c].process(s)
                    if (sweeping) {
                        if (appliedLp < 19_000f) s = lp[c].process(s)
                        if (appliedHp > 15f) s = hp[c].process(s)
                    }
                }
                var o = s * g
                // Eco: la cola no depende de la ganancia, así sigue sonando mientras la canción se apaga.
                if (echo && eb != null && c < ch) {
                    val p = echoPos * channels + c
                    val d = eb[p]
                    eb[p] = s * send + d * ECHO_FEEDBACK
                    o += d * ECHO_WET
                }
                mono += o
                out.putShort(o.coerceIn(-32768f, 32767f).toInt().toShort())
            }
            if (echo && eb != null) echoPos = (echoPos + 1) % echoFrames
            if (analyze) analyzer.push(mono / (channels * 32768f), sampleRate)
        }
        if (echo && send <= 0f) {
            echoTail -= frames
            if (echoTail <= 0) { echoTail = 0; eb?.fill(0f) }
        }
        gain = g
        out.flip()
    }

    /** Reserva el búfer de captura y arranca/detiene el bucle según [loopMs]. */
    private fun prepareLoop(capture: Boolean) {
        if ((capture || loopMs > 0f) && ring == null) {
            ringFrames = sampleRate * RING_SECONDS
            ring = ShortArray(ringFrames * channels)
            ringWrite = 0; ringFilled = 0
        }
        val requested = loopMs
        if (requested > 0f && !loopActive) {
            val len = (requested * sampleRate / 1000f).toInt()
            if (len > LOOP_XF * 2 && ringFilled >= len + LOOP_XF) {
                loopLen = len
                loopStart = ((ringWrite - len) % ringFrames + ringFrames) % ringFrames
                loopIdx = 0
                loopActive = true
            }
        } else if (requested <= 0f && loopActive) {
            loopActive = false
        }
        if (!capture && !loopActive && requested <= 0f) ringFilled = 0
    }

    private fun prepareEcho() {
        val frames = (echoDelayMs.coerceIn(30f, 1_900f) * sampleRate / 1000f).toInt().coerceAtLeast(1)
        if (echoBuf == null || frames != echoFrames) {
            echoBuf = FloatArray(frames * channels)
            echoFrames = frames
            echoPos = 0
        }
    }

    override fun onFlush() {
        low.forEach { it.reset() }
        high.forEach { it.reset() }
        lp.forEach { it.reset() }
        hp.forEach { it.reset() }
        gain = targetGain
        // Una búsqueda invalida el audio guardado y el eco.
        ringFilled = 0
        loopActive = false
        echoBuf?.fill(0f)
        echoTail = 0
    }

    override fun onReset() {
        // Los parámetros (ganancia, graves, agudos) los controla el motor de mezcla:
        // aquí solo se reinicia el estado interno para no reproducir con volumen indebido.
        gain = targetGain
        low.forEach { it.reset() }
        high.forEach { it.reset() }
        lp.forEach { it.reset() }
        hp.forEach { it.reset() }
        ringFilled = 0
        loopActive = false
        echoBuf?.fill(0f)
        echoTail = 0
    }

    private fun updateFilters(force: Boolean) {
        val b = bassDb.coerceIn(-40f, 12f)
        val t = trebleDb.coerceIn(-40f, 12f)
        if (force || b != appliedBassDb) {
            appliedBassDb = b
            low.forEach { it.setLowShelf(sampleRate, 180.0, b.toDouble()) }
        }
        if (force || t != appliedTrebleDb) {
            appliedTrebleDb = t
            high.forEach { it.setHighShelf(sampleRate, 4000.0, t.toDouble()) }
        }
        val nyquist = sampleRate * 0.45f
        val lpHz = lowPassHz.coerceIn(40f, 20_000f)
        // Solo se recalculan los coeficientes si el corte cambia más de un 1 %.
        if (force || abs(lpHz - appliedLp) > appliedLp * 0.01f) {
            appliedLp = lpHz
            if (lpHz < 19_000f) lp.forEach { it.setLowPass(sampleRate, minOf(lpHz, nyquist).toDouble()) } else lp.forEach { it.reset() }
        }
        val hpHz = highPassHz.coerceIn(0f, 8_000f)
        if (force || abs(hpHz - appliedHp) > maxOf(appliedHp * 0.01f, 0.5f)) {
            appliedHp = hpHz
            if (hpHz > 15f) hp.forEach { it.setHighPass(sampleRate, hpHz.toDouble()) } else hp.forEach { it.reset() }
        }
    }

    /** Filtro biquad (RBJ Audio EQ Cookbook) en forma directa I transpuesta. */
    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun reset() { z1 = 0.0; z2 = 0.0 }

        fun process(x: Float): Float {
            val xin = x.toDouble()
            val y = b0 * xin + z1
            z1 = b1 * xin - a1 * y + z2
            z2 = b2 * xin - a2 * y
            return y.toFloat()
        }

        /** Paso bajo Butterworth de 2.º orden (Q = 0,707). Conserva el estado para barridos sin clics. */
        fun setLowPass(fs: Int, f0: Double) {
            val w0 = 2 * PI * f0 / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / (2 * 0.7071)
            val a0 = 1 + alpha
            b0 = (1 - cosW) / 2 / a0; b1 = (1 - cosW) / a0; b2 = (1 - cosW) / 2 / a0
            a1 = -2 * cosW / a0; a2 = (1 - alpha) / a0
        }

        /** Paso alto Butterworth de 2.º orden (Q = 0,707). */
        fun setHighPass(fs: Int, f0: Double) {
            val w0 = 2 * PI * f0 / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / (2 * 0.7071)
            val a0 = 1 + alpha
            b0 = (1 + cosW) / 2 / a0; b1 = -(1 + cosW) / a0; b2 = (1 + cosW) / 2 / a0
            a1 = -2 * cosW / a0; a2 = (1 - alpha) / a0
        }

        fun setLowShelf(fs: Int, f0: Double, db: Double) = setShelf(fs, f0, db, low = true)
        fun setHighShelf(fs: Int, f0: Double, db: Double) = setShelf(fs, f0, db, low = false)

        private fun setShelf(fs: Int, f0: Double, db: Double, low: Boolean) {
            if (db == 0.0) { b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0; return }
            val a = 10.0.pow(db / 40.0)
            val w0 = 2 * PI * f0 / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0) // pendiente S = 1
            val sq = 2 * sqrt(a) * alpha
            val nb0: Double; val nb1: Double; val nb2: Double
            val na0: Double; val na1: Double; val na2: Double
            if (low) {
                nb0 = a * ((a + 1) - (a - 1) * cosW + sq)
                nb1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                nb2 = a * ((a + 1) - (a - 1) * cosW - sq)
                na0 = (a + 1) + (a - 1) * cosW + sq
                na1 = -2 * ((a - 1) + (a + 1) * cosW)
                na2 = (a + 1) + (a - 1) * cosW - sq
            } else {
                nb0 = a * ((a + 1) + (a - 1) * cosW + sq)
                nb1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                nb2 = a * ((a + 1) + (a - 1) * cosW - sq)
                na0 = (a + 1) - (a - 1) * cosW + sq
                na1 = 2 * ((a - 1) - (a + 1) * cosW)
                na2 = (a + 1) - (a - 1) * cosW - sq
            }
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
            a1 = na1 / na0; a2 = na2 / na0
        }
    }

    private companion object {
        const val MAX_CH = 8
        const val RING_SECONDS = 16
        /** Fundido en la unión del bucle (~3 ms) para que no haya clics. */
        const val LOOP_XF = 128
        const val ECHO_FEEDBACK = 0.55f
        const val ECHO_WET = 0.7f
    }
}
