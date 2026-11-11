package com.vybra.music.playback.engine

/** Fuente de audio intercalado estéreo: rellena [out] desde [offset] (en muestras) y devuelve los fotogramas escritos. */
typealias PcmSource = (out: FloatArray, offset: Int, frames: Int) -> Int

/**
 * Cambio de tempo sin cambiar el tono (WSOLA, al estilo de SoundTouch): se copian
 * secuencias de ~60 ms de la entrada y se solapan buscando en cada unión el punto donde
 * la forma de onda coincide mejor, para que no se oigan saltos.
 */
class TimeStretcher(private val sampleRate: Int) {
    private val ch = 2
    private val seq = sampleRate * 60 / 1000
    private val seek = sampleRate * 15 / 1000
    private val overlap = sampleRate * 10 / 1000

    var tempo = 1f

    /** Entrada pendiente (intercalada). */
    private var inBuf = FloatArray((seq + seek + overlap) * ch * 4)
    private var inFrames = 0
    /** Posición fraccional (en fotogramas) del inicio de la siguiente secuencia dentro de [inBuf]. */
    private var inPos = 0.0

    /** Salida ya calculada pendiente de entregar. */
    private var outBuf = FloatArray(seq * ch * 2)
    private var outStart = 0
    private var outEnd = 0

    /** Cola de la secuencia anterior que se mezcla con el comienzo de la siguiente. */
    private val tail = FloatArray(overlap * ch)
    private var hasTail = false
    private var eof = false

    /** Fotogramas de entrada leídos de la fuente pero aún no reproducidos (para calcular la posición). */
    val pendingInputFrames: Double get() = (inFrames - inPos) + (outEnd - outStart) / ch * tempo

    fun reset() {
        inFrames = 0; inPos = 0.0; outStart = 0; outEnd = 0; hasTail = false; eof = false
    }

    fun read(out: FloatArray, offset: Int, frames: Int, source: PcmSource): Int {
        var written = 0
        while (written < frames) {
            if (outEnd - outStart == 0) {
                if (!produce(source)) break
                continue
            }
            val n = minOf((outEnd - outStart) / ch, frames - written)
            System.arraycopy(outBuf, outStart, out, offset + written * ch, n * ch)
            outStart += n * ch
            written += n
        }
        return written
    }

    private fun fill(source: PcmSource, need: Int) {
        if (inFrames >= need || eof) return
        if (inBuf.size < need * ch) inBuf = inBuf.copyOf(need * ch * 2)
        while (inFrames < need) {
            val got = source(inBuf, inFrames * ch, need - inFrames)
            if (got <= 0) { eof = true; break }
            inFrames += got
        }
    }

    /** Calcula la siguiente secuencia. Devuelve false si no queda entrada. */
    private fun produce(source: PcmSource): Boolean {
        val need = inPos.toInt() + seek + seq + overlap
        fill(source, need)
        val base = inPos.toInt()
        val available = inFrames - base
        if (available <= overlap) {
            // Final: se entrega lo que quede sin procesar.
            if (available <= 0) return false
            ensureOut(available)
            System.arraycopy(inBuf, base * ch, outBuf, 0, available * ch)
            outStart = 0; outEnd = available * ch
            inPos = inFrames.toDouble()
            return true
        }
        val best = if (hasTail) bestOffset(base, minOf(seek, available - overlap - 1)) else 0
        val start = base + best
        val len = minOf(seq, inFrames - start)
        if (len <= overlap) {
            inPos = inFrames.toDouble()
            return produce(source)
        }
        // Salida: overlap mezclado + resto de la secuencia menos el último overlap (que se guarda como cola).
        val body = len - overlap
        ensureOut(body)
        var o = 0
        for (i in 0 until overlap) {
            val a = i.toFloat() / overlap
            for (c in 0 until ch) {
                val s = inBuf[(start + i) * ch + c]
                outBuf[o++] = if (hasTail) tail[i * ch + c] * (1 - a) + s * a else s
            }
        }
        val copyFrames = body - overlap
        if (copyFrames > 0) {
            System.arraycopy(inBuf, (start + overlap) * ch, outBuf, o, copyFrames * ch)
            o += copyFrames * ch
        }
        System.arraycopy(inBuf, (start + body) * ch, tail, 0, overlap * ch)
        hasTail = true
        outStart = 0; outEnd = o
        // Avance nominal en la entrada: lo producido multiplicado por el tempo.
        inPos += (body.toDouble()) * tempo
        compact()
        return o > 0
    }

    private fun ensureOut(frames: Int) {
        if (outBuf.size < frames * ch) outBuf = FloatArray(frames * ch * 2)
    }

    /** Busca, en [base, base+range], el desplazamiento cuya forma de onda se parece más a la cola guardada. */
    private fun bestOffset(base: Int, range: Int): Int {
        if (range <= 0) return 0
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var k = 0
        while (k <= range) {
            var corr = 0.0
            var norm = 1e-9
            var i = 0
            val p = (base + k) * ch
            while (i < overlap) {
                val idx = p + i * ch
                val m = inBuf[idx] + inBuf[idx + 1]
                val t = tail[i * ch] + tail[i * ch + 1]
                corr += m * t
                norm += m * m
                i += 4
            }
            val score = corr / Math.sqrt(norm)
            if (score > bestScore) { bestScore = score; best = k }
            k += 2
        }
        return best
    }

    /** Descarta la entrada ya consumida. */
    private fun compact() {
        val drop = inPos.toInt()
        if (drop <= 0) return
        val keep = inFrames - drop
        if (keep > 0) System.arraycopy(inBuf, drop * ch, inBuf, 0, keep * ch)
        inFrames = keep.coerceAtLeast(0)
        inPos -= drop
    }
}

/** Remuestreo lineal: cambia velocidad y tono a la vez (frenado de vinilo). */
class VarispeedResampler {
    private val ch = 2
    var rate = 1.0
    private var buf = FloatArray(4096 * ch)
    private var frames = 0
    private var pos = 0.0
    private var eof = false

    val pendingInputFrames: Double get() = frames - pos

    fun reset() { frames = 0; pos = 0.0; eof = false }

    fun read(out: FloatArray, offset: Int, count: Int, source: PcmSource): Int {
        var written = 0
        while (written < count) {
            if (pos + 1 >= frames) {
                // Conserva el último fotograma para interpolar con el siguiente bloque.
                val keepFrom = pos.toInt().coerceAtMost(frames).coerceAtLeast(0)
                val keep = frames - keepFrom
                if (keep > 0) System.arraycopy(buf, keepFrom * ch, buf, 0, keep * ch)
                frames = keep
                pos -= keepFrom
                if (eof) break
                val got = source(buf, frames * ch, (buf.size / ch) - frames)
                if (got <= 0) { eof = true; if (frames <= 1) break else continue }
                frames += got
                continue
            }
            val i = pos.toInt()
            val f = (pos - i).toFloat()
            val o = offset + written * ch
            for (c in 0 until ch) {
                val a = buf[i * ch + c]
                val b = buf[(i + 1) * ch + c]
                out[o + c] = a + (b - a) * f
            }
            written++
            pos += rate
        }
        return written
    }
}
