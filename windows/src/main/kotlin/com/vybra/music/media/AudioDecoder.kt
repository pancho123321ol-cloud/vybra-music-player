package com.vybra.music.media

import com.vybra.music.Log
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable

/**
 * Decodificador de audio basado en FFmpeg: abre cualquier formato habitual y entrega
 * PCM float intercalado a [outRate] Hz con [outChannels] canales (1 o 2).
 * No es seguro entre hilos: cada instancia se usa desde un único hilo a la vez.
 */
class AudioDecoder private constructor(
    private val fmt: AVFormatContext,
    private val ctx: AVCodecContext,
    private val streamIndex: Int,
    val outRate: Int,
    val outChannels: Int,
    val durationMs: Long,
) : Closeable {
    private val timeBase: Double
    private var swr: SwrContext? = null
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var outCapacity = 0
    private var outBuf: FloatPointer? = null
    private val outPtr = PointerPointer<FloatPointer>(1)

    /** Muestras decodificadas pendientes de entregar (intercaladas). */
    private var pending = FloatArray(0)
    private var pendingStart = 0
    private var pendingEnd = 0

    /** Fotogramas (muestras por canal) que quedan por descartar tras una búsqueda precisa. */
    private var discardFrames = 0L
    private var discardUntilMs = -1L
    private var inputDone = false
    private var flushed = false
    var ended = false
        private set
    private var closed = false

    /** Posición (en fotogramas de salida) del siguiente fotograma que devolverá [read]. */
    var positionFrames: Long = 0
        private set

    val positionMs: Long get() = positionFrames * 1000 / outRate

    init {
        val st = fmt.streams(streamIndex)
        timeBase = st.time_base().num().toDouble() / st.time_base().den()
        initSwr()
    }

    private fun initSwr() {
        swr?.let { swr_free(it) }
        val outLayout = AVChannelLayout()
        av_channel_layout_default(outLayout, outChannels)
        val inLayout = ctx.ch_layout()
        if (inLayout.nb_channels() <= 0) av_channel_layout_default(inLayout, 2)
        // Puntero nulo: FFmpeg reserva el contexto y lo devuelve en este objeto.
        val s = SwrContext(null as org.bytedeco.javacpp.Pointer?)
        check(
            swr_alloc_set_opts2(
                s, outLayout, AV_SAMPLE_FMT_FLT, outRate,
                inLayout, ctx.sample_fmt(), ctx.sample_rate(), 0, null,
            ) >= 0 && !s.isNull,
        ) { "swr_alloc_set_opts2" }
        check(swr_init(s) >= 0) { "swr_init" }
        swr = s
    }

    /**
     * Rellena [out] con hasta [frames] fotogramas intercalados empezando en [offset] (en muestras).
     * Devuelve los fotogramas escritos; 0 significa fin del archivo.
     */
    fun read(out: FloatArray, offset: Int, frames: Int): Int {
        if (closed) return 0
        var written = 0
        while (written < frames) {
            if (pendingEnd - pendingStart == 0) {
                if (!decodeMore()) break
                continue
            }
            val availFrames = (pendingEnd - pendingStart) / outChannels
            val n = minOf(availFrames, frames - written)
            System.arraycopy(pending, pendingStart, out, offset + written * outChannels, n * outChannels)
            pendingStart += n * outChannels
            written += n
        }
        positionFrames += written
        if (written == 0) ended = true
        return written
    }

    /** Decodifica el siguiente bloque. Devuelve false al llegar al final. */
    private fun decodeMore(): Boolean {
        while (true) {
            val r = avcodec_receive_frame(ctx, frame)
            if (r >= 0) {
                convert(frame)
                av_frame_unref(frame)
                if (pendingEnd - pendingStart > 0) return true
                continue
            }
            if (r == AVERROR_EOF()) {
                if (!flushed) {
                    flushed = true
                    // Vacía lo que quede dentro del remuestreador.
                    convert(null)
                    if (pendingEnd - pendingStart > 0) return true
                }
                return false
            }
            // EAGAIN: hace falta otro paquete.
            if (inputDone) return false
            val rr = av_read_frame(fmt, packet)
            if (rr < 0) {
                inputDone = true
                avcodec_send_packet(ctx, null)
                continue
            }
            if (packet.stream_index() == streamIndex) avcodec_send_packet(ctx, packet)
            av_packet_unref(packet)
        }
    }

    private fun convert(f: AVFrame?) {
        val s = swr ?: return
        val inSamples = f?.nb_samples() ?: 0
        val maxOut = (inSamples.toLong() * outRate / maxOf(1, ctx.sample_rate()) + 4096).toInt()
        if (maxOut > outCapacity) {
            outBuf?.close()
            outBuf = FloatPointer((maxOut * outChannels).toLong())
            outCapacity = maxOut
            outPtr.put(0, outBuf)
        }
        // Frame start time for discarding samples after a precise seek.
        var frameStartMs = -1L
        if (f != null && discardUntilMs >= 0) {
            val ts = f.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE() } ?: f.pts()
            if (ts != AV_NOPTS_VALUE()) frameStartMs = (ts * timeBase * 1000).toLong()
        }
        val produced = if (f != null) swr_convert(s, outPtr, maxOut, f.extended_data(), inSamples)
        else swr_convert(s, outPtr, maxOut, null as PointerPointer<*>?, 0)
        if (produced <= 0) return
        val count = produced * outChannels
        if (pending.size < count) pending = FloatArray(count)
        outBuf!!.position(0).get(pending, 0, count)
        pendingStart = 0
        pendingEnd = count
        if (discardUntilMs >= 0) {
            if (frameStartMs >= 0 && discardFrames == 0L) {
                discardFrames = ((discardUntilMs - frameStartMs) * outRate / 1000).coerceAtLeast(0)
            }
            val drop = minOf(discardFrames, produced.toLong()).toInt()
            pendingStart = drop * outChannels
            discardFrames -= drop
            if (discardFrames <= 0) discardUntilMs = -1
        }
    }

    /** Búsqueda precisa a [ms]. */
    fun seek(ms: Long) {
        if (closed) return
        val target = ms.coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        val ts = target * 1000 // AV_TIME_BASE = 1 000 000
        av_seek_frame(fmt, -1, ts, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(ctx)
        initSwr()
        pendingStart = 0; pendingEnd = 0
        inputDone = false; flushed = false; ended = false
        discardUntilMs = target
        discardFrames = 0
        positionFrames = target * outRate / 1000
    }

    override fun close() {
        if (closed) return
        closed = true
        swr?.let { swr_free(it) }
        swr = null
        av_frame_free(frame)
        av_packet_free(packet)
        avcodec_free_context(ctx)
        avformat_close_input(fmt)
        outBuf?.close()
    }

    companion object {
        init {
            av_log_set_level(AV_LOG_QUIET)
        }

        /** Abre [path]; devuelve null si no tiene audio decodificable. */
        fun open(path: String, outRate: Int = 44_100, outChannels: Int = 2): AudioDecoder? {
            val fmt = AVFormatContext(null)
            val p = BytePointer(path, "UTF-8")
            if (avformat_open_input(fmt, p, null, null as AVDictionary?) < 0) {
                p.close()
                Log.w("AudioDecoder", "No se pudo abrir $path")
                return null
            }
            p.close()
            try {
                if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) throw IllegalStateException("stream info")
                val idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
                if (idx < 0) throw IllegalStateException("sin audio")
                val st = fmt.streams(idx)
                val codec = avcodec_find_decoder(st.codecpar().codec_id()) ?: throw IllegalStateException("sin códec")
                val ctx = avcodec_alloc_context3(codec)
                avcodec_parameters_to_context(ctx, st.codecpar())
                ctx.pkt_timebase(st.time_base())
                if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
                    avcodec_free_context(ctx)
                    throw IllegalStateException("avcodec_open2")
                }
                val duration = when {
                    fmt.duration() > 0 -> fmt.duration() / 1000
                    st.duration() > 0 -> (st.duration() * st.time_base().num() * 1000.0 / st.time_base().den()).toLong()
                    else -> 0L
                }
                return AudioDecoder(fmt, ctx, idx, outRate, outChannels, duration)
            } catch (e: Exception) {
                Log.w("AudioDecoder", "No se pudo preparar $path: ${e.message}")
                avformat_close_input(fmt)
                return null
            }
        }

        /** Lee duración y etiquetas del contenedor sin decodificar. */
        fun probe(path: String): Probe? {
            val fmt = AVFormatContext(null)
            val p = BytePointer(path, "UTF-8")
            if (avformat_open_input(fmt, p, null, null as AVDictionary?) < 0) { p.close(); return null }
            p.close()
            try {
                avformat_find_stream_info(fmt, null as PointerPointer<*>?)
                val idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
                if (idx < 0) return null
                val tags = HashMap<String, String>()
                fun collect(d: AVDictionary?) {
                    if (d == null || d.isNull) return
                    var e: AVDictionaryEntry? = null
                    while (true) {
                        e = av_dict_get(d, "", e, AV_DICT_IGNORE_SUFFIX) ?: break
                        if (e.isNull) break
                        val k = e.key().getString("UTF-8").lowercase()
                        if (k !in tags) tags[k] = e.value().getString("UTF-8")
                    }
                }
                collect(fmt.metadata())
                collect(fmt.streams(idx).metadata())
                val duration = if (fmt.duration() > 0) fmt.duration() / 1000 else 0L
                return Probe(duration, tags)
            } finally {
                avformat_close_input(fmt)
            }
        }
    }

    class Probe(val durationMs: Long, val tags: Map<String, String>)
}

/**
 * Decodifica un archivo completo a PCM a [rate] Hz: un canal (mezcla mono) o dos
 * (izquierdo y derecho). Se usa para el análisis de BPM/tonalidad y la sincronización de letras.
 */
fun decodeWholeFile(path: String, rate: Int, stereo: Boolean, maxMs: Long): Array<FloatArray>? {
    val dec = AudioDecoder.open(path, rate, 2) ?: return null
    dec.use { d ->
        val maxFrames = (maxMs / 1000 * rate).toInt()
        var l = FloatArray(rate * 240)
        var r = if (stereo) FloatArray(rate * 240) else null
        var n = 0
        val buf = FloatArray(8192 * 2)
        while (n < maxFrames) {
            val got = d.read(buf, 0, 8192)
            if (got <= 0) break
            if (n + got > l.size) {
                l = l.copyOf(l.size * 2)
                r = r?.copyOf(r.size * 2)
            }
            for (i in 0 until got) {
                val a = buf[i * 2]
                val b = buf[i * 2 + 1]
                if (r != null) { l[n + i] = a; r[n + i] = b } else l[n + i] = (a + b) * 0.5f
            }
            n += got
        }
        if (n == 0) return null
        return if (r != null) arrayOf(l.copyOf(n), r.copyOf(n)) else arrayOf(l.copyOf(n))
    }
}
