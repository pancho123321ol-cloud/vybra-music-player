package com.vybra.music.data.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.analysis.TrackAnalyzer
import com.vybra.music.playback.audio.Fft
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Pasa los tiempos de una letra sincronizada de referencia (por ejemplo, de internet)
 * a la letra del usuario, emparejando las líneas por su texto (alineación de secuencias).
 * Las líneas sin pareja se interpolan entre sus vecinas.
 */
object LyricsTextAligner {
    class Result(val times: List<Long>, val matched: Int)

    fun transfer(userLines: List<String>, reference: List<LyricLine>, durationMs: Long): Result? {
        val ref = reference.filter { it.text.isNotBlank() }
        val n = userLines.size
        val m = ref.size
        if (n == 0 || m == 0) return null
        val uk = userLines.map(::tokens)
        val rk = ref.map { tokens(it.text) }
        val sim = Array(n) { i -> DoubleArray(m) { j -> dice(uk[i], rk[j]) } }
        // Máxima suma de similitudes con emparejamiento en orden (tipo LCS ponderado).
        val s = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            var best = max(s[i - 1][j], s[i][j - 1])
            val w = sim[i - 1][j - 1]
            if (w >= 0.45) best = max(best, s[i - 1][j - 1] + w)
            s[i][j] = best
        }
        val pair = IntArray(n) { -1 }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val w = sim[i - 1][j - 1]
            when {
                w >= 0.45 && s[i][j] == s[i - 1][j - 1] + w -> { pair[i - 1] = j - 1; i--; j-- }
                s[i][j] == s[i - 1][j] -> i--
                else -> j--
            }
        }
        val matched = pair.count { it >= 0 }
        if (matched < max(2, (n * 0.5).toInt())) return null
        val times = LongArray(n) { -1 }
        for (k in 0 until n) if (pair[k] >= 0) times[k] = ref[pair[k]].timeMs
        fillGaps(times, durationMs)
        return Result(times.toList(), matched)
    }

    /** Completa las líneas sin tiempo repartiéndolas entre las vecinas que sí lo tienen. */
    fun fillGaps(times: LongArray, durationMs: Long) {
        val n = times.size
        var k = 0
        while (k < n) {
            if (times[k] >= 0) { k++; continue }
            var e = k
            while (e < n && times[e] < 0) e++
            val before = if (k > 0) times[k - 1] else -1L
            val after = if (e < n) times[e] else -1L
            val count = e - k
            for (x in 0 until count) {
                times[k + x] = when {
                    before >= 0 && after >= 0 -> before + (after - before) * (x + 1) / (count + 1)
                    after >= 0 -> (after - (count - x) * 2_500L).coerceAtLeast(0)
                    before >= 0 -> min(before + (x + 1) * 2_500L, max(before, durationMs - 1_000))
                    else -> x * 2_500L
                }
            }
            k = e
        }
        for (x in 1 until n) if (times[x] < times[x - 1]) times[x] = times[x - 1]
    }

    private fun tokens(s: String): Set<String> =
        MusicRepository.normalize(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)
}

/**
 * Sincronización automática analizando el audio:
 *  1. Aísla lo que suena en el centro de la mezcla (donde suele ir la voz) en la banda
 *     de la voz (200 Hz–4 kHz) y descarta los sonidos ruidosos tipo percusión.
 *  2. Detecta los tramos con voz y dónde empieza cada frase.
 *  3. Reparte las líneas entre esas frases con programación dinámica, de forma que
 *     cada línea dure lo que corresponde a su número de sílabas y empiece en una entrada
 *     de la voz (y que las estrofas empiecen tras una pausa).
 * El resultado es una estimación: después se revisa y ajusta en el editor.
 */
object VocalSync {
    private const val TAG = "VocalSync"
    private const val SR = 11_025
    private const val FRAME = 512
    private const val HOP = 220 // ≈ 20 ms
    private const val HOP_MS = HOP * 1000.0 / SR

    /** [lines]: letra completa, con líneas en blanco entre estrofas. Devuelve un tiempo por línea no vacía. */
    fun align(context: Context, uri: Uri, lines: List<String>, durationMs: Long): List<Long>? {
        val started = System.currentTimeMillis()
        if (lines.none { it.isNotBlank() }) return null
        val pcm = TrackAnalyzer.decode(context, uri, SR, stereo = true) ?: return null
        val left = pcm[0]; val right = pcm[1]
        val frames = (left.size - FRAME) / HOP
        if (frames < 200) return null

        val raw = vocalFeatures(left, right, frames)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            // Volcado para ajustar el algoritmo fuera del teléfono.
            runCatching {
                java.io.DataOutputStream(java.io.File(context.cacheDir, "vocal_features.bin").outputStream().buffered()).use { out ->
                    out.writeInt(frames)
                    raw.forEach { out.writeFloat(it) }
                }
            }
        }
        val texts = lines.filter { it.isNotBlank() }.map { it.trim() }
        val times = alignFeatures(lines, raw)?.map { (it * HOP_MS).toLong().coerceIn(0, max(0, durationMs - 500)) }
        Log.i(TAG, "lines=${texts.size} frames=$frames took=${System.currentTimeMillis() - started}ms")
        if (Log.isLoggable(TAG, Log.DEBUG)) times?.forEachIndexed { i, t -> Log.d(TAG, "line $i $t ${texts[i]}") }
        return times
    }

    /** Parte pura (sin Android): de las características por bloque al bloque de inicio de cada línea. */
    internal fun alignFeatures(lines: List<String>, raw: FloatArray): List<Int>? {
        val texts = ArrayList<String>()
        val stanzaStart = ArrayList<Boolean>()
        var pendingBreak = true
        for (l in lines) {
            if (l.isBlank()) { pendingBreak = true; continue }
            texts += l.trim(); stanzaStart += pendingBreak; pendingBreak = false
        }
        if (texts.isEmpty()) return null
        val score = smooth(raw)
        val voiced = detectVoice(score)
        return assign(texts, stanzaStart, voiced, raw.size, syllableOnsets(raw, voiced))
    }

    /** Energía (dB) de la parte central y tonal de la mezcla en la banda vocal, por bloque de 20 ms. */
    private fun vocalFeatures(l: FloatArray, r: FloatArray, frames: Int): FloatArray {
        val window = Fft.hann(FRAME)
        val lr = FloatArray(FRAME); val li = FloatArray(FRAME)
        val rr = FloatArray(FRAME); val ri = FloatArray(FRAME)
        val b0 = (200.0 / SR * FRAME).toInt()
        val b1 = (4_000.0 / SR * FRAME).toInt()
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FRAME) {
                lr[i] = l[off + i] * window[i]; li[i] = 0f
                rr[i] = r[off + i] * window[i]; ri[i] = 0f
            }
            Fft.transform(lr, li)
            Fft.transform(rr, ri)
            var center = 0.0
            var logSum = 0.0
            for (k in b0..b1) {
                val pl = lr[k] * lr[k] + li[k] * li[k]
                val pr = rr[k] * rr[k] + ri[k] * ri[k]
                val cross = lr[k] * rr[k] + li[k] * ri[k]
                val sum = pl + pr + 1e-12f
                // 1 cuando el bin suena igual en ambos canales (centrado), 0 cuando está a un lado.
                val sim = (2 * cross / sum).coerceIn(0f, 1f)
                val c = sum * 0.5 * sim * sim * sim
                center += c
                logSum += ln(c + 1e-12)
            }
            val n = b1 - b0 + 1
            val mean = center / n
            // Planitud espectral: alta en ruido/percusión, baja en sonidos con tono como la voz.
            val flatness = (exp(logSum / n) / (mean + 1e-12)).coerceIn(0.0, 1.0)
            val tonal = 1 - flatness
            out[f] = (10 * log10(center * tonal * tonal + 1e-10)).toFloat()
        }
        return out
    }

    /** Suavizado de ~100 ms. */
    private fun smooth(raw: FloatArray): FloatArray {
        val n = raw.size
        val sm = FloatArray(n)
        for (f in 0 until n) {
            var s = 0f; var c = 0
            for (k in max(0, f - 2)..min(n - 1, f + 2)) { s += raw[k]; c++ }
            sm[f] = s / c
        }
        return sm
    }

    /**
     * Núcleos silábicos: picos de la energía vocal (suavizada ~60 ms) que sobresalen
     * al menos 2 dB respecto al valle anterior, separados ≥ 120 ms, dentro de tramos con voz.
     * Sirven de "reloj" de sílabas: en un rap rápido hay muchos más por segundo que en un coro.
     */
    internal fun syllableOnsets(raw: FloatArray, voiced: BooleanArray): BooleanArray {
        val n = raw.size
        val s = FloatArray(n)
        for (f in 0 until n) {
            var acc = 0f; var c = 0
            for (k in max(0, f - 1)..min(n - 1, f + 1)) { acc += raw[k]; c++ }
            s[f] = acc / c
        }
        val peaks = BooleanArray(n)
        var valley = Float.MAX_VALUE
        var last = -100
        for (f in 1 until n - 1) {
            valley = min(valley, s[f])
            if (s[f] >= s[f - 1] && s[f] > s[f + 1] && voiced[f] && s[f] - valley >= 2f && f - last >= 6) {
                peaks[f] = true
                last = f
                valley = s[f]
            }
        }
        return peaks
    }

    /** Decide en qué bloques hay voz, con un umbral adaptado a cada tramo de la canción. */
    internal fun detectVoice(score: FloatArray): BooleanArray {
        val n = score.size
        // Suelo local: percentil 20 en una ventana de ±6 s, calculado cada 0,5 s e interpolado.
        val step = 25
        val half = 300
        val floors = FloatArray(n / step + 2)
        val buf = FloatArray(2 * half + 1)
        for (b in floors.indices) {
            val c = min(n - 1, b * step)
            var cnt = 0
            for (k in max(0, c - half)..min(n - 1, c + half)) buf[cnt++] = score[k]
            java.util.Arrays.sort(buf, 0, cnt)
            floors[b] = buf[(cnt * 0.2).toInt().coerceAtMost(cnt - 1)]
        }
        val rel = FloatArray(n) { f ->
            val p = f.toFloat() / step
            val i0 = p.toInt().coerceAtMost(floors.size - 2)
            val t = p - i0
            score[f] - (floors[i0] * (1 - t) + floors[i0 + 1] * t)
        }
        // Umbral de Otsu sobre la altura respecto al suelo (con mínimo de 4 dB).
        val thr = max(4f, otsu(rel))
        // Además, los tramos muy por debajo del nivel general no cuentan como voz.
        val sorted = score.copyOf().also { it.sort() }
        val global = sorted[(n * 0.35).toInt()]
        val voiced = BooleanArray(n) { rel[it] > thr && score[it] > global }
        // Cierra huecos de < 160 ms y elimina tramos de < 140 ms.
        fillRuns(voiced, false, 8)
        fillRuns(voiced, true, 7)
        return voiced
    }

    private fun fillRuns(a: BooleanArray, value: Boolean, maxLen: Int) {
        var i = 0
        while (i < a.size) {
            if (a[i] != value) { i++; continue }
            var e = i
            while (e < a.size && a[e] == value) e++
            if (e - i < maxLen && i > 0 && e < a.size) for (k in i until e) a[k] = !value
            i = e
        }
    }

    private fun otsu(v: FloatArray): Float {
        val lo = v.min(); val hi = v.max()
        if (hi - lo < 1e-3f) return hi
        val bins = 64
        val hist = IntArray(bins)
        for (x in v) hist[((x - lo) / (hi - lo) * (bins - 1)).toInt()]++
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (b in 0 until bins) sumAll += b * hist[b].toDouble()
        var wB = 0.0; var sumB = 0.0; var best = -1.0; var bestB = 0
        for (b in 0 until bins) {
            wB += hist[b]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += b * hist[b].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestB = b }
        }
        return lo + (bestB + 0.5f) / (bins - 1) * (hi - lo)
    }

    /** Número aproximado de sílabas (grupos de vocales). */
    fun syllables(text: String): Int {
        val s = MusicRepository.normalize(text)
        var count = 0
        var inVowel = false
        for (ch in s) {
            val v = ch in "aeiouy"
            if (v && !inVowel) count++
            inVowel = v
        }
        return max(1, count)
    }

    private class Candidate(val frame: Int, val strength: Float, val gapBefore: Float)

    /** Devuelve el bloque de inicio de cada línea. */
    /** Pesos del alineador (ajustables en las pruebas). */
    internal var wVoiced = 1.0
    internal var wOnsets = 0.5
    internal var wStart = 1.6
    internal var wActive = 0.0
    internal var activeGap = 75

    internal fun assign(
        texts: List<String>,
        stanzaStart: List<Boolean>,
        voiced: BooleanArray,
        frames: Int,
        onsets: BooleanArray? = null,
    ): List<Int>? {
        val n = texts.size
        val prefix = IntArray(frames + 1)
        for (f in 0 until frames) prefix[f + 1] = prefix[f] + if (voiced[f]) 1 else 0
        val totalVoiced = prefix[frames]
        val onsetPrefix = IntArray(frames + 1)
        if (onsets != null) for (x in 0 until frames) onsetPrefix[x + 1] = onsetPrefix[x] + if (onsets[x]) 1 else 0
        val totalOnsets = onsetPrefix[frames]
        // Tiempo "activo": todo menos las pausas largas sin voz (intro, puentes instrumentales).
        // Es más estable que la voz detectada, que puede fallar en estrofas con mucha base.
        val active = BooleanArray(frames)
        run {
            var x = 0
            while (x < frames) {
                if (voiced[x]) { active[x] = true; x++; continue }
                var e = x
                while (e < frames && !voiced[e]) e++
                val inner = x > 0 && e < frames
                if (inner && e - x < activeGap) for (k in x until e) active[k] = true
                x = e
            }
        }
        val activePrefix = IntArray(frames + 1)
        for (x in 0 until frames) activePrefix[x + 1] = activePrefix[x] + if (active[x]) 1 else 0
        if (totalVoiced < 50) return null
        val firstVoiced = voiced.indexOfFirst { it }
        val lastVoiced = voiced.indexOfLast { it }

        // Candidatos: comienzo de cada tramo con voz (más fuerte cuanto más larga la pausa previa)
        // y, dentro de tramos largos, un punto cada ~0,8 s para frases encadenadas.
        val cands = ArrayList<Candidate>()
        var f = 0
        var lastEnd = -1
        while (f < frames) {
            if (!voiced[f]) { f++; continue }
            var e = f
            while (e < frames && voiced[e]) e++
            val gapSec = if (lastEnd < 0) 10f else (f - lastEnd) * HOP_MS.toFloat() / 1000f
            cands += Candidate(f, (0.45f + 0.55f * min(1f, gapSec / 0.8f)), gapSec)
            var k = f + 40
            while (k < e - 20) { cands += Candidate(k, 0.08f, 0f); k += 40 }
            lastEnd = e
            f = e
        }
        if (cands.size < n) {
            // Muy pocas entradas detectadas: se añaden puntos regulares para poder repartir.
            val extra = n - cands.size + 4
            val stepF = max(1, (lastVoiced - firstVoiced) / (extra + 1))
            for (x in 1..extra) cands += Candidate(firstVoiced + x * stepF, 0f, 0f)
            cands.sortBy { it.frame }
        }
        val c = cands.size
        val syl = IntArray(n) { syllables(texts[it]) }
        val rate = totalVoiced.toDouble() / syl.sum() // bloques con voz por sílaba
        val onsetRate = totalOnsets.toDouble() / syl.sum() // núcleos detectados por sílaba escrita
        val useOnsets = onsets != null && totalOnsets > syl.sum() / 4
        val activeRate = activePrefix[frames].toDouble() / syl.sum()
        val minGap = 25 // una línea dura al menos 0,5 s
        val maxSpan = 1_500 // y como mucho 30 s

        fun durCost(i: Int, a: Int, bFrame: Int): Double {
            val v = (prefix[bFrame] - prefix[cands[a].frame]).toDouble()
            val expected = syl[i] * rate
            val r = ln((v + 15) / (expected + 15))
            // Tolerante: cantar más lento o más rápido (hasta ~1,4×) casi no penaliza.
            val over = max(0.0, abs(r) - 0.35)
            var cost = (over * over * 1.5 + r * r * 0.1) * wVoiced
            run {
                val act = (activePrefix[bFrame] - activePrefix[cands[a].frame]).toDouble()
                val ra = ln((act + 10) / (syl[i] * activeRate + 10))
                val overA = max(0.0, abs(ra) - 0.3)
                cost += (overA * overA * 1.5 + ra * ra * 0.1) * wActive
            }
            if (useOnsets) {
                val o = (onsetPrefix[bFrame] - onsetPrefix[cands[a].frame]).toDouble()
                val ro = ln((o + 2) / (syl[i] * onsetRate + 2))
                val overO = max(0.0, abs(ro) - 0.3)
                cost += (overO * overO * 1.5 + ro * ro * 0.1) * wOnsets
            }
            return cost
        }
        fun startCost(i: Int, a: Int): Double {
            val cand = cands[a]
            var cost = (1 - cand.strength) * wStart
            if (i > 0 && stanzaStart[i]) cost += 0.8 * (1 - min(1f, cand.gapBefore / 2f))
            if (i > 0 && !stanzaStart[i] && cand.gapBefore > 4f) cost += 0.4 * (cand.gapBefore - 4f) / 4f
            return cost
        }

        val inf = Double.MAX_VALUE / 4
        val cost = Array(n) { DoubleArray(c) { inf } }
        val back = Array(n) { IntArray(c) { -1 } }
        for (a in 0 until c) {
            // La primera línea: penaliza dejar mucha voz sin letra antes de ella.
            val skipped = (prefix[cands[a].frame] - prefix[0]) / 50.0
            cost[0][a] = startCost(0, a) + skipped * 0.6
        }
        for (i in 1 until n) {
            val prev = cost[i - 1]
            for (b in 0 until c) {
                val fb = cands[b].frame
                var best = inf; var arg = -1
                for (a in b - 1 downTo 0) {
                    val fa = cands[a].frame
                    if (fb - fa < minGap) continue
                    if (fb - fa > maxSpan) break
                    if (prev[a] >= inf) continue
                    val v = prev[a] + durCost(i - 1, a, fb)
                    if (v < best) { best = v; arg = a }
                }
                if (arg >= 0) { cost[i][b] = best + startCost(i, b); back[i][b] = arg }
            }
        }
        var bestEnd = inf; var arg = -1
        val endFrame = min(frames, lastVoiced + 1)
        for (a in 0 until c) {
            if (cost[n - 1][a] >= inf || cands[a].frame >= endFrame) continue
            val v = cost[n - 1][a] + durCost(n - 1, a, endFrame)
            if (v < bestEnd) { bestEnd = v; arg = a }
        }
        if (arg < 0) return null
        val starts = IntArray(n)
        var a = arg
        for (i in n - 1 downTo 0) {
            starts[i] = cands[a].frame
            a = back[i][a]
            if (i > 0 && a < 0) return null
        }
        // Pequeño adelanto para que la línea aparezca justo al empezar a cantarse.
        return starts.map { max(0, it - 3) }.also {
            Log.d(TAG, "cands=$c rate=${"%.1f".format(rate)} cost=${"%.2f".format(bestEnd)} first=${abs(it[0])}")
        }
    }
}
