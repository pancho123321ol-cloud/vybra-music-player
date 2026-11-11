package com.vybra.music.playback.engine

import com.vybra.music.data.scanner.MediaScanner
import com.vybra.music.playback.audio.SpectrumAnalyzer
import com.vybra.music.playback.audio.VybraAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

class DeckTest {
    private fun items(): List<MediaItem> {
        val root = MediaScanner.defaultMusicFolder()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("mp3", "flac") }
            .take(3).mapIndexed { i, f ->
                MediaItem("${i + 1}", MediaScanner.normalize(f), f.name, "", "", null, i.toLong(), 0)
            }.toList()
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timeout", cond())
    }

    @Test
    fun rendersSeeksAndAdvances() {
        val list = items()
        assumeTrue(list.size >= 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deck = Deck("T", scope, VybraAudioProcessor(SpectrumAnalyzer())) { null }
        deck.setMediaItems(list, 0, 0)
        deck.prepare()
        deck.play()
        waitFor { deck.isPlaying }

        val mix = FloatArray(1024 * 2)
        var energy = 0.0
        repeat(200) {
            java.util.Arrays.fill(mix, 0f)
            assertTrue(deck.render(mix, 1024))
            for (s in mix) energy += abs(s)
        }
        assertTrue("hay audio", energy > 1.0)
        assertEquals(200 * 1024 * 1000.0 / 44_100, deck.currentPosition.toDouble(), 50.0)

        // Time-stretch al 110 %: la posición avanza más rápido.
        deck.playbackParameters = PlaybackParameters(1.1f)
        val before = deck.currentPosition
        repeat(100) { deck.render(mix, 1024) }
        val advanced = deck.currentPosition - before
        assertEquals(100 * 1024 * 1000.0 / 44_100 * 1.1, advanced.toDouble(), 150.0)
        deck.playbackParameters = PlaybackParameters.DEFAULT

        // Búsqueda cerca del final y paso automático a la siguiente.
        val dur = deck.duration
        assertTrue(dur > 5000)
        deck.seekTo(dur - 500)
        repeat(60) { deck.render(mix, 1024) }
        assertEquals(1, deck.currentMediaItemIndex)
        assertTrue(deck.currentPosition in 0..2000)
        deck.release()
    }
}
