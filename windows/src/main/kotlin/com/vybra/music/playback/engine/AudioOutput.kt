package com.vybra.music.playback.engine

import com.vybra.music.Log
import com.vybra.music.playback.audio.SpectrumAnalyzer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Salida de audio: un hilo mezcla los decks, aplica el ecualizador y el volumen general,
 * y escribe PCM de 16 bits en la tarjeta de sonido predeterminada de Windows.
 */
class AudioOutput(
    private val decks: () -> List<Deck>,
    private val equalizer: SoftwareEqualizer,
) {
    @Volatile var masterVolume: Float = 1f
    @Volatile private var running = true
    private val wakeLock = Object()
    private var line: SourceDataLine? = null
    private val format = AudioFormat(Deck.SAMPLE_RATE.toFloat(), 16, 2, true, false)
    private val bytesPerMs = Deck.SAMPLE_RATE * 4 / 1000.0

    /** Audio escrito en la tarjeta que todavía no ha sonado. */
    val latencyMs: Long
        get() {
            val l = line ?: return 0
            return ((l.bufferSize - l.available()) / bytesPerMs).toLong()
        }

    private val thread = Thread(::loop, "vybra-audio").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
    }

    fun start() = thread.start()

    fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    fun release() {
        running = false
        wake()
        runCatching { thread.join(500) }
        line?.let { runCatching { it.stop(); it.close() } }
    }

    private fun openLine(): SourceDataLine? = runCatching {
        val l = AudioSystem.getSourceDataLine(format)
        // ~120 ms de búfer: suficiente para no cortarse y poca latencia al pausar o buscar.
        l.open(format, (bytesPerMs * BUFFER_MS).toInt() / 4 * 4)
        l.start()
        Log.i(TAG, "Salida de audio abierta: ${l.bufferSize} bytes")
        l
    }.onFailure { Log.e(TAG, "No se pudo abrir la salida de audio", it) }.getOrNull()

    private fun loop() {
        val frames = CHUNK
        val mix = FloatArray(frames * 2)
        val pcm = ByteArray(frames * 4)
        var idleSince = 0L
        while (running) {
            java.util.Arrays.fill(mix, 0f)
            var any = false
            for (d in decks()) {
                if (d.render(mix, frames)) any = true
            }
            if (!any) {
                // Nada que sonar: se deja vaciar el búfer y se espera a que algo cambie.
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                // Tras un rato en silencio se libera el dispositivo (vuelve a abrirse al reproducir).
                if (line != null && System.currentTimeMillis() - idleSince > RELEASE_IDLE_MS) {
                    line?.let { runCatching { it.drain(); it.stop(); it.close() } }
                    line = null
                }
                synchronized(wakeLock) { wakeLock.wait(20) }
                continue
            }
            idleSince = 0L
            val l = line ?: openLine().also { line = it } ?: run {
                Thread.sleep(500)
                null
            } ?: continue
            SpectrumAnalyzer.DEFAULT_LATENCY_MS = latencyMs + 20
            equalizer.process(mix, frames)
            val vol = masterVolume.coerceIn(0f, 1f).let { it * it } // curva perceptual
            var o = 0
            for (i in 0 until frames * 2) {
                var s = mix[i] * vol
                // Limitador suave: evita recortes duros cuando las dos canciones o el EQ suman.
                if (abs(s) > 0.9f) s = (if (s > 0) 1f else -1f) * (0.9f + 0.1f * tanh((abs(s) - 0.9f) / 0.1f))
                val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
                pcm[o++] = (v and 0xFF).toByte()
                pcm[o++] = (v shr 8).toByte()
            }
            runCatching { l.write(pcm, 0, pcm.size) }.onFailure {
                Log.w(TAG, "Fallo al escribir audio; se reabre la salida", it)
                runCatching { l.close() }
                line = null
            }
        }
    }

    private companion object {
        const val TAG = "AudioOutput"
        const val CHUNK = 1024
        const val BUFFER_MS = 120
        const val RELEASE_IDLE_MS = 30_000L
    }
}
