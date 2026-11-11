package com.vybra.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsSyncTest {

    @Test
    fun transfersTimesFromReferenceWithDifferentSpelling() {
        val reference = listOf(
            LyricLine(1_000, "Sí, sabes que ya llevo un rato mirándote"),
            LyricLine(5_000, "Tengo que bailar contigo hoy"),
            LyricLine(9_000, "Vi que tu mirada ya estaba llamándome"),
            LyricLine(13_000, "Muéstrame el camino que yo voy"),
        )
        val user = listOf(
            "si sabes que ya llevo un rato mirandote",
            "(oh oh)",
            "tengo que bailar contigo hoy",
            "vi que tu mirada ya estaba llamandome",
            "muestrame el camino, que yo voy",
        )
        val r = LyricsTextAligner.transfer(user, reference, 200_000)
        assertNotNull(r)
        r!!
        assertEquals(4, r.matched)
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 9_000L, 13_000L), r.times)
    }

    @Test
    fun rejectsReferenceOfAnotherSong() {
        val reference = listOf(LyricLine(1_000, "Hello darkness my old friend"), LyricLine(4_000, "I've come to talk with you again"))
        assertNull(LyricsTextAligner.transfer(listOf("Tengo que bailar contigo hoy", "Muéstrame el camino"), reference, 100_000))
    }

    @Test
    fun cleansTitles() {
        assertEquals("Despacito", LrcLibClient.cleanTitle("Despacito (feat. Daddy Yankee)"))
        assertEquals("Chico", LrcLibClient.cleanTitle("Chico [Official Video]"))
        assertEquals("Bailando con la luna", LrcLibClient.cleanTitle("Bailando con la luna"))
        assertEquals("Song", LrcLibClient.cleanTitle("Song - 2011 Remaster"))
    }

    @Test
    fun countsSyllables() {
        assertEquals(9, VocalSync.syllables("Tengo que bailar contigo hoy")) // grupos de vocales: e-o, ue, ai-a, o-i-o, oy
    }

    /**
     * Voz sintética: 6 frases separadas por silencios, dos estrofas con una pausa larga.
     * Cada línea debe caer en el inicio de su frase.
     */
    @Test
    fun assignsLinesToVocalPhrases() {
        val frames = 6_000 // 120 s
        val voiced = BooleanArray(frames)
        // (inicio, duración) en bloques de 20 ms
        val phrases = listOf(500 to 120, 650 to 200, 880 to 100, 1_000 to 160, 2_000 to 140, 2_170 to 220)
        phrases.forEach { (s, d) -> for (f in s until s + d) voiced[f] = true }
        val texts = listOf(
            "uno dos tres", // corta
            "esta es una línea bastante más larga que la anterior",
            "breve",
            "otra frase de longitud media aquí",
            "segunda estrofa empieza",
            "y termina con una línea larguísima que dura bastante",
        )
        val stanza = listOf(true, false, false, false, true, false)
        val starts = VocalSync.assign(texts, stanza, voiced, frames)
        assertNotNull(starts)
        starts!!.zip(phrases).forEachIndexed { i, (got, phrase) ->
            assertTrue("línea $i: $got vs ${phrase.first}", abs(got - phrase.first) <= 5)
        }
    }

    @Test
    fun convertsLyricsFileToWordTimedLrc() {
        val yaml = """
            version: '1.0'
            metadata:
              title: Despacito
              duration_ms: 228000
            lines:
            - text: Ay
              start_ms: 6150
              end_ms: 8350
              words:
              - text: Ay
                start_ms: 6150
            - text: Fonsi, DY
              start_ms: 8350
              end_ms: 9311
              words:
              - text: 'Fonsi, '
                start_ms: 8350
              - text: DY
                start_ms: 8585
        """.trimIndent()
        val lrc = LyricsFile.toEnhancedLrc(yaml)!!
        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.synced && parsed.hasWordTiming)
        assertEquals(2, parsed.lines.size)
        val l = parsed.lines[1]
        assertEquals("Fonsi, DY", l.text)
        assertEquals(8_350L, l.timeMs)
        assertEquals(9_310L, l.endMs) // centésimas
        assertEquals(listOf(8_350L, 8_580L), l.words!!.map { it.startMs })
        assertEquals("Fonsi, ", l.words!![0].text)
        val w = parsed.wordsAt(1)
        assertEquals(9_310L, w.last().endMs)
        // Ida y vuelta sin perder las palabras.
        assertEquals(parsed.lines, LrcParser.parse(LrcParser.toLrc(parsed.lines, null, null)).lines)
    }

    @Test
    fun estimatesWordsAndAppliesOffset() {
        val parsed = LrcParser.parse("[offset:500]\n[00:10.00]uno dos tres\n[00:14.00]siguiente")
        assertEquals(500L, parsed.offsetMs)
        assertEquals(9_500L, parsed.lines[0].timeMs)
        val words = parsed.wordsAt(0)
        assertEquals(3, words.size)
        assertEquals(9_500L, words[0].startMs)
        assertTrue(words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertTrue(words.last().endMs <= 13_500L)
        assertEquals("[00:10.00]x", LrcParser.withOffset("[offset:500]\n[00:10.00]x", 0))
    }
}
