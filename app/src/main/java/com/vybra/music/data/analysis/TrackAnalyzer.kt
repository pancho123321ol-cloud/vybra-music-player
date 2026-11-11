package com.vybra.music.data.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.vybra.music.data.db.TrackAnalysis
import com.vybra.music.playback.audio.Fft
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Análisis de audio sin conexión: decodifica la pista a PCM y estima
 * BPM y fase del pulso (flujo espectral + autocorrelación con filtro peine),
 * tonalidad (cromagrama + perfiles de Krumhansl), energía e inicio/fin del sonido útil.
 */
class AnalysisResult(val analysis: TrackAnalysis, val waveform: ByteArray)

object TrackAnalyzer {
    const val VERSION = 3
    private const val TAG = "TrackAnalyzer"
    private const val SR = 11_025
    private const val FRAME = 1024
    private const val HOP = 256
    private const val MAX_MS = 12 * 60_000L

    /** Resolución de la forma de onda guardada (un valor por bloque de análisis). */
    const val WAVEFORM_HOP_MS = HOP * 1000.0 / SR

    @Volatile private var decoderName = ""

    /**
     * Prefiere un decodificador por software: algunos fabricantes eligen por defecto
     * decodificadores de hardware de bajo consumo que decodifican casi en tiempo real.
     */
    private fun createSoftwareDecoder(mime: String): MediaCodec {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val software = infos.firstOrNull { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
            else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
        }
        return software?.let { runCatching { MediaCodec.createByCodecName(it.name) }.getOrNull() }
            ?: MediaCodec.createDecoderByType(mime)
    }

    private val MAJOR =floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    fun analyze(context: Context, songId: Long, uri: Uri, durationMs: Long): AnalysisResult? {
        val started = System.currentTimeMillis()
        val pcm = decodeMono(context, uri) ?: return null
        val decodeMs = System.currentTimeMillis() - started
        if (pcm.size < SR * 5) return null
        val hopSec = HOP.toDouble() / SR

        // --- Envolvente de ataques (flujo espectral) y energía por bloque ---
        val frames = (pcm.size - FRAME) / HOP
        val onset = FloatArray(frames)
        val rms = FloatArray(frames)
        // Forma de onda de 3 bandas: por cada bloque de 23 ms se guarda el pico y la energía
        // de graves (<200 Hz), medios (200 Hz–2 kHz) y agudos (2–5 kHz).
        val peaks = FloatArray(frames)
        val lows = FloatArray(frames)
        val mids = FloatArray(frames)
        val highs = FloatArray(frames)
        val lowEnd = (200.0 / SR * FRAME).toInt()
        val midEnd = (2_000.0 / SR * FRAME).toInt()
        val window = Fft.hann(FRAME)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val maxBin = (5_000.0 / SR * FRAME).toInt()
        var prev = FloatArray(maxBin)
        var cur = FloatArray(maxBin)
        for (f in 0 until frames) {
            val off = f * HOP
            var sum = 0f
            for (i in 0 until FRAME) {
                val s = pcm[off + i]
                sum += s * s
                re[i] = s * window[i]; im[i] = 0f
            }
            rms[f] = sqrt(sum / FRAME)
            var peak = 0f
            for (i in 0 until HOP) { val a = abs(pcm[off + i]); if (a > peak) peak = a }
            peaks[f] = peak
            Fft.transform(re, im)
            var flux = 0f
            var eLow = 0f; var eMid = 0f; var eHigh = 0f
            for (k in 1 until maxBin) {
                val p2 = re[k] * re[k] + im[k] * im[k]
                when {
                    k <= lowEnd -> eLow += p2
                    k <= midEnd -> eMid += p2
                    else -> eHigh += p2
                }
                val m = ln(1f + 100f * sqrt(p2))
                cur[k] = m
                val d = m - prev[k]
                if (d > 0) flux += d
            }
            lows[f] = sqrt(eLow); mids[f] = sqrt(eMid); highs[f] = sqrt(eHigh)
            onset[f] = flux
            val t = prev; prev = cur; cur = t
        }
        // Resta la media local y rectifica.
        val env = FloatArray(frames)
        val half = 8
        var acc = 0f
        for (i in 0 until minOf(frames, half * 2 + 1)) acc += onset[i]
        for (i in 0 until frames) {
            val lo = i - half - 1; val hi = i + half
            if (lo >= 0 && hi < frames) acc += onset[hi] - onset[lo]
            val mean = acc / (half * 2 + 1)
            env[i] = (onset[i] - mean).coerceAtLeast(0f)
        }

        // --- Tempo ---
        val segStart = (frames * 0.1).toInt()
        val segEnd = minOf(frames - 1, segStart + 3_500)
        val maxLag = 170
        val ac = FloatArray(maxLag + 2)
        for (lag in 1..maxLag + 1) {
            var s = 0f
            var n = segStart
            while (n + lag < segEnd) { s += env[n] * env[n + lag]; n++ }
            ac[lag] = s
        }
        fun acAt(p: Double): Float {
            if (p >= maxLag) return 0f
            val i = p.toInt(); val fr = (p - i).toFloat()
            return ac[i] * (1 - fr) + ac[i + 1] * fr
        }
        var bestP = 0.0
        var bestScore = -1f
        var p = 60.0 / (190 * hopSec)
        val pMax = 60.0 / (68 * hopSec)
        while (p <= pMax) {
            val bpm = 60.0 / (p * hopSec)
            val prior = exp(-0.5 * (log2(bpm / 122.0) / 0.8).let { it * it }).toFloat()
            var score = 0f
            for (k in 1..4) score += acAt(p * k) / k
            score *= prior
            if (score > bestScore) { bestScore = score; bestP = p }
            p += 0.02
        }
        if (bestP <= 0) return null
        val bpm = (60.0 / (bestP * hopSec)).toFloat()

        // --- Fase del pulso ---
        var bestPhase = 0.0
        var bestPhaseScore = -1f
        var ph = 0.0
        while (ph < bestP) {
            var s = 0f
            var t = ph
            while (t < frames - 1) { s += env[t.roundToInt()]; t += bestP }
            if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = ph }
            ph += 0.25
        }
        // El ataque queda centrado en la ventana: se suma media ventana.
        val beatOffsetMs = ((bestPhase * HOP + FRAME / 2.0) / SR * 1000).toLong()

        // --- Energía, inicio del sonido y fin del sonido útil ---
        val smooth = FloatArray(frames)
        val w = 43 // ~1 s
        var a2 = 0f
        for (i in 0 until frames) {
            a2 += rms[i]
            if (i >= w) a2 -= rms[i - w]
            smooth[i] = a2 / minOf(i + 1, w)
        }
        val sorted = smooth.copyOf().also { it.sort() }
        val ref = sorted[(sorted.size * 0.8).toInt().coerceIn(0, sorted.lastIndex)].coerceAtLeast(1e-4f)
        val firstSound = (rms.indexOfFirst { it > ref * 0.12f }).coerceAtLeast(0)
        var usefulEnd = smooth.indexOfLast { it > ref * 0.35f }
        if (usefulEnd < 0) usefulEnd = frames - 1
        val energy = (rms.average().toFloat() * 4f).coerceIn(0f, 1f)

        // --- Tonalidad ---
        val (keyIndex, minor, confidence) = detectKey(pcm)

        val toMs = { f: Int -> (f.toDouble() * HOP / SR * 1000).toLong() }
        val result = TrackAnalysis(
            songId = songId,
            bpm = bpm,
            beatOffsetMs = beatOffsetMs,
            keyIndex = keyIndex,
            minor = minor,
            keyConfidence = confidence,
            energy = energy,
            introEndMs = toMs(firstSound),
            outroStartMs = toMs(usefulEnd).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE),
            durationMs = if (durationMs > 0) durationMs else (pcm.size * 1000L / SR),
            version = VERSION,
        )
        Log.i(TAG, "song=$songId bpm=${"%.1f".format(bpm)} key=$keyIndex${if (minor) "m" else ""} conf=${"%.2f".format(confidence)} " +
            "start=${result.introEndMs} end=${result.outroStartMs} decoder=$decoderName decode=${decodeMs}ms total=${System.currentTimeMillis() - started}ms")
        return AnalysisResult(result, packWaveform(peaks, lows, mids, highs))
    }

    /** Valores por bloque en la forma de onda empaquetada: pico, graves, medios, agudos. */
    const val WAVEFORM_STRIDE = 4

    /**
     * Normaliza cada banda por su percentil 98 (para que todas las canciones se vean con
     * contraste parecido) y empaqueta 4 bytes por bloque en escala raíz.
     */
    private fun packWaveform(peaks: FloatArray, lows: FloatArray, mids: FloatArray, highs: FloatArray): ByteArray {
        fun ref(a: FloatArray): Float {
            val s = a.copyOf().also { it.sort() }
            return s[(s.size * 0.98).toInt().coerceIn(0, s.lastIndex)].coerceAtLeast(1e-6f)
        }
        val rl = ref(lows); val rm = ref(mids); val rh = ref(highs)
        val out = ByteArray(peaks.size * WAVEFORM_STRIDE)
        fun q(v: Float) = (sqrt(v.coerceIn(0f, 1f)) * 255).toInt().toByte()
        for (i in peaks.indices) {
            val o = i * WAVEFORM_STRIDE
            out[o] = q(peaks[i])
            out[o + 1] = q(lows[i] / rl)
            out[o + 2] = q(mids[i] / rm)
            out[o + 3] = q(highs[i] / rh)
        }
        return out
    }

    private fun detectKey(pcm: FloatArray): Triple<Int, Boolean, Float> {
        val n = 4096
        val window = Fft.hann(n)
        val re = FloatArray(n); val im = FloatArray(n)
        val chroma = DoubleArray(12)
        val binHz = SR.toDouble() / n
        val k0 = (65.0 / binHz).toInt(); val k1 = (2_100.0 / binHz).toInt()
        val pcOf = IntArray(k1 + 1) { k ->
            if (k < k0) -1 else (((12 * log2(k * binHz / 440.0)).roundToInt() + 9) % 12 + 12) % 12
        }
        var off = 0
        while (off + n < pcm.size) {
            for (i in 0 until n) { re[i] = pcm[off + i] * window[i]; im[i] = 0f }
            Fft.transform(re, im)
            for (k in k0..k1) chroma[pcOf[k]] += sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
            off += n
        }
        fun corr(profile: FloatArray, rot: Int): Double {
            val pm = profile.average(); val cm = chroma.average()
            var num = 0.0; var dp = 0.0; var dc = 0.0
            for (i in 0 until 12) {
                val a = profile[(i - rot + 12) % 12] - pm
                val b = chroma[i] - cm
                num += a * b; dp += a * a; dc += b * b
            }
            return if (dp == 0.0 || dc == 0.0) 0.0 else num / sqrt(dp * dc)
        }
        val scores = ArrayList<Triple<Int, Boolean, Double>>()
        for (r in 0 until 12) {
            scores += Triple(r, false, corr(MAJOR, r))
            scores += Triple(r, true, corr(MINOR, r))
        }
        scores.sortByDescending { it.third }
        val best = scores[0]
        if (best.third < 0.3) return Triple(-1, false, 0f)
        val conf = ((best.third - scores[1].third) * 5 + best.third * 0.5).toFloat().coerceIn(0f, 1f)
        return Triple(best.first, best.second, conf)
    }

    private fun decodeMono(context: Context, uri: Uri): FloatArray? = decode(context, uri, SR, stereo = false)?.get(0)

    /**
     * Decodifica a PCM a [rate] Hz: un canal (mezcla mono) o dos (izquierdo y derecho;
     * los archivos mono devuelven el mismo canal dos veces).
     */
    fun decode(context: Context, uri: Uri, rate: Int, stereo: Boolean, maxMs: Long = MAX_MS): Array<FloatArray>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            codec = createSoftwareDecoder(mime)
            decoderName = codec.name
            codec.configure(format, null, null, 0)
            codec.start()

            val out = FloatBuilder(rate * 300)
            val outR = if (stereo) FloatBuilder(rate * 300) else null
            var accR = 0f
            var shortScratch = ShortArray(16_384)
            var floatScratch = FloatArray(0)
            var ratio = sampleRate.toDouble() / rate
            var phase = 0.0; var acc = 0f; var cnt = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxSamples = (maxMs / 1000 * rate).toInt()
            while (!outputDone && out.size < maxSamples) {
                // Alimenta todos los búferes de entrada libres sin esperar.
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    run {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        ratio = sampleRate.toDouble() / rate
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frameCount = info.size / (bytesPerSample * channels)
                        val samples = frameCount * channels
                        if (floatPcm) {
                            if (floatScratch.size < samples) floatScratch = FloatArray(samples)
                            buf.asFloatBuffer().get(floatScratch, 0, samples)
                        } else {
                            if (shortScratch.size < samples) shortScratch = ShortArray(samples)
                            buf.asShortBuffer().get(shortScratch, 0, samples)
                        }
                        var s = 0
                        for (i in 0 until frameCount) {
                            if (outR != null) {
                                val l = if (floatPcm) floatScratch[s] else shortScratch[s] / 32768f
                                val r = if (channels > 1) (if (floatPcm) floatScratch[s + 1] else shortScratch[s + 1] / 32768f) else l
                                s += channels
                                acc += l; accR += r
                            } else {
                                var m = 0f
                                for (c in 0 until channels) {
                                    m += if (floatPcm) floatScratch[s] else shortScratch[s] / 32768f
                                    s++
                                }
                                acc += m / channels
                            }
                            cnt++
                            phase += 1.0
                            if (phase >= ratio) {
                                out.add(acc / cnt)
                                outR?.add(accR / cnt)
                                acc = 0f; accR = 0f; cnt = 0; phase -= ratio
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return if (outR != null) arrayOf(out.toArray(), outR.toArray()) else arrayOf(out.toArray())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo decodificar $uri", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private class FloatBuilder(initial: Int) {
        private var data = FloatArray(initial)
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
