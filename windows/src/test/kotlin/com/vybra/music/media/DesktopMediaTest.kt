package com.vybra.music.media

import com.vybra.music.data.analysis.TrackAnalyzer
import com.vybra.music.data.scanner.MediaScanner
import com.vybra.music.playback.engine.TimeStretcher
import com.vybra.music.playback.engine.VarispeedResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DesktopMediaTest {
    /** Primeros archivos de audio de la carpeta Música (si existe) para pruebas con archivos reales. */
    private fun sampleFiles(): List<File> {
        val root = MediaScanner.defaultMusicFolder()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension.lowercase() in MediaScanner.AUDIO_EXTENSIONS }
            .take(3000).toList()
    }

    @Test
    fun decodesAndTagsRealFiles() {
        val files = sampleFiles()
        assumeTrue("Sin música en la carpeta del usuario", files.isNotEmpty())
        var decoded = 0
        for (f in files.groupBy { it.extension.lowercase() }.values.map { it.first() }) {
            val path = MediaScanner.normalize(f)
            val song = AudioTags.read(f, MediaScanner.idFor(path), path, f.lastModified() / 1000)
            assertNotNull("tags ${f.name}", song)
            println("${f.extension}: ${song!!.title} — ${song.artist} — ${song.album} (${song.duration} ms)")
            val dec = AudioDecoder.open(path) ?: continue
            dec.use { d ->
                val buf = FloatArray(4096 * 2)
                var total = 0
                repeat(50) { total += d.read(buf, 0, 4096) }
                assertTrue("lee audio de ${f.name}", total > 0)
                d.seek(30_000)
                val n = d.read(buf, 0, 4096)
                assertTrue(n > 0)
                assertTrue("posición tras buscar ${d.positionMs}", abs(d.positionMs - 30_093) < 200)
            }
            decoded++
        }
        assertTrue(decoded > 0)
    }

    @Test
    fun analyzerRunsOnRealFile() {
        val f = sampleFiles().firstOrNull { it.length() > 2_000_000 }
        assumeTrue(f != null)
        val t0 = System.currentTimeMillis()
        val r = TrackAnalyzer.analyze(1, MediaScanner.normalize(f!!), 0)
        println("análisis ${f.name}: bpm=${r?.analysis?.bpm} key=${r?.analysis?.keyIndex} en ${System.currentTimeMillis() - t0} ms")
        assertNotNull(r)
        assertTrue(r!!.analysis.bpm in 60f..200f)
    }

    private fun sine(frames: Int, hz: Double): FloatArray = FloatArray(frames * 2) { i ->
        (sin(2 * PI * hz * (i / 2) / 44_100) * 0.5).toFloat()
    }

    @Test
    fun timeStretchKeepsDurationRatio() {
        val src = sine(44_100 * 4, 440.0)
        var pos = 0
        val source: (FloatArray, Int, Int) -> Int = { out, off, n ->
            val k = minOf(n, src.size / 2 - pos)
            System.arraycopy(src, pos * 2, out, off, k * 2)
            pos += k
            k
        }
        val ts = TimeStretcher(44_100).apply { tempo = 1.25f }
        val out = FloatArray(4096 * 2)
        var produced = 0
        while (true) {
            val n = ts.read(out, 0, 4096, source)
            if (n == 0) break
            produced += n
        }
        // 4 s a 1,25× ≈ 3,2 s.
        assertEquals(44_100 * 3.2, produced.toDouble(), 44_100 * 0.1)
    }

    @Test
    fun varispeedChangesLength() {
        val src = sine(44_100, 440.0)
        var pos = 0
        val source: (FloatArray, Int, Int) -> Int = { out, off, n ->
            val k = minOf(n, src.size / 2 - pos)
            System.arraycopy(src, pos * 2, out, off, k * 2)
            pos += k
            k
        }
        val vs = VarispeedResampler().apply { rate = 0.5 }
        val out = FloatArray(4096 * 2)
        var produced = 0
        while (true) {
            val n = vs.read(out, 0, 4096, source)
            if (n == 0) break
            produced += n
        }
        assertEquals(88_200.0, produced.toDouble(), 100.0)
    }
}
