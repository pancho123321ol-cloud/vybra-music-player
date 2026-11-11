package com.vybra.music.data.lyrics

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

/**
 * Mide la sincronización por voz contra una letra con tiempos reales. Solo se ejecuta si
 * existe VYBRA_SYNC_DIR con: features.bin (volcado del teléfono), ref.lrc y lyrics.txt.
 */
class VocalSyncTuningTest {
    private val hopMs = 220 * 1000.0 / 11_025

    @Test
    fun measure() {
        val dir = System.getenv("VYBRA_SYNC_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "features.bin").exists())
        dir!!
        val raw = DataInputStream(File(dir, "features.bin").inputStream().buffered()).use { inp ->
            FloatArray(inp.readInt()) { inp.readFloat() }
        }
        val ref = LrcParser.parse(File(dir, "ref.lrc").readText()).lines.filter { it.text.isNotBlank() }.map { it.timeMs }
        val lines = File(dir, "lyrics.txt").readText().replace("\r", "").lines()
        if (System.getenv("VYBRA_SYNC_DIAG") != null) {
            val score = raw.copyOf() // diagnóstico con la misma detección que usa el alineador
            val sm = FloatArray(raw.size) { f -> (maxOf(0, f - 2)..minOf(raw.size - 1, f + 2)).map { raw[it] }.average().toFloat() }
            val voiced = VocalSync.detectVoice(sm)
            val onsets = VocalSync.syllableOnsets(score, voiced)
            val texts = lines.filter { it.isNotBlank() }
            for (i in ref.indices) {
                val a = (ref[i] / hopMs).toInt()
                val b = if (i + 1 < ref.size) (ref[i + 1] / hopMs).toInt() else minOf(raw.size, a + 200)
                val v = (a until b).count { voiced[it] }
                val o = (a until b).count { onsets[it] }
                val syl = VocalSync.syllables(texts[i])
                println("%3d %6.1fs dur=%4.1fs voiced=%4.1fs syl=%2d v/syl=%4.2f on=%2d on/syl=%4.2f %s".format(
                    i, ref[i] / 1000.0, (b - a) * hopMs / 1000, v * hopMs / 1000, syl, v * hopMs / 1000 / syl, o, o.toDouble() / syl, texts[i].take(28)))
            }
            println("voiced before first line: ${(0 until (ref[0] / hopMs).toInt()).count { voiced[it] } * hopMs / 1000}s, total voiced ${voiced.count { it } * hopMs / 1000}s of ${raw.size * hopMs / 1000}s")
        }
        val configs = ArrayList<DoubleArray>()
        for (wv in listOf(0.0, 0.3, 1.0)) for (wo in listOf(0.0, 0.5)) for (wa in listOf(0.0, 1.0, 2.0, 4.0)) for (ws in listOf(0.8, 1.6)) for (g in listOf(50.0, 100.0, 200.0))
            configs += doubleArrayOf(wv, wo, wa, ws, g)
        for (cfg in configs) {
            val (wv, wo, wa) = Triple(cfg[0], cfg[1], cfg[2]); val ws = cfg[3]
            VocalSync.wVoiced = wv; VocalSync.wOnsets = wo; VocalSync.wActive = wa; VocalSync.wStart = ws; VocalSync.activeGap = cfg[4].toInt()
            val got = VocalSync.alignFeatures(lines, raw)!!.map { (it * hopMs).toLong() }
            val errs = ref.zip(got) { a, b -> abs(a - b) }.sorted()
            val n = errs.size
            fun pct(ms: Long) = errs.count { it <= ms } * 100 / n
            println(
                "RES wv=$wv wo=$wo wa=$wa ws=$ws gap=${cfg[4]}  median=${errs[n / 2] / 1000.0}s  ≤0.5s=${pct(500)}%  ≤1s=${pct(1000)}%  ≤2s=${pct(2000)}%  ≤5s=${pct(5000)}%",
            )
        }
    }
}
