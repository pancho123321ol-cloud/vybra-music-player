package com.vybra.music.data.lyrics

/**
 * Convierte el formato "lyricsfile" de LRCLIB (YAML con tiempos por línea y por palabra)
 * a LRC mejorado: `[mm:ss.xx]<mm:ss.xx>palabra <mm:ss.xx>palabra<mm:ss.xx>`.
 * Solo se lee la parte necesaria (lines → text/start_ms/end_ms/words), sin dependencias.
 */
object LyricsFile {
    private class Word(var text: String = "", var start: Long = -1)
    private class Line(var text: String = "", var start: Long = -1, var end: Long = -1, val words: MutableList<Word> = ArrayList())

    fun toEnhancedLrc(yaml: String?): String? {
        if (yaml.isNullOrBlank()) return null
        val lines = parse(yaml)
        if (lines.isEmpty() || lines.none { it.start >= 0 }) return null
        return LrcParser.toLrc(
            lines.filter { it.start >= 0 }.map { l ->
                val words = l.words.filter { it.start >= 0 && it.text.isNotEmpty() }
                LyricLine(
                    timeMs = l.start,
                    text = l.text,
                    words = words.takeIf { it.isNotEmpty() }?.map { LyricWord(it.start, it.text) },
                    endMs = l.end,
                )
            },
            title = null, artist = null,
        )
    }

    private fun parse(yaml: String): List<Line> {
        val out = ArrayList<Line>()
        var inLines = false
        var line: Line? = null
        var word: Word? = null
        var lineIndent = -1
        for (raw in yaml.replace("\r", "").lines()) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val t = raw.trim()
            if (indent == 0 && !t.startsWith("- ")) { inLines = t == "lines:"; continue }
            if (!inLines) continue
            val isItem = t.startsWith("- ")
            val body = if (isItem) t.substring(2) else t
            if (isItem && (lineIndent < 0 || indent <= lineIndent)) {
                // Nueva línea de la letra.
                lineIndent = indent
                line = Line().also { out += it }
                word = null
            } else if (isItem) {
                // Nueva palabra dentro de "words:".
                word = Word().also { line?.words?.add(it) }
            }
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val key = body.substring(0, colon).trim()
            val value = unquote(body.substring(colon + 1).trim())
            val target = if (word != null && indent > lineIndent) word else null
            when (key) {
                "text" -> if (target != null) target.text = value else line?.text = value
                "start_ms" -> value.toLongOrNull()?.let { if (target != null) target.start = it else line?.start = it }
                "end_ms" -> value.toLongOrNull()?.let { if (target == null) line?.end = it }
                "words" -> word = null
            }
        }
        return out
    }

    private fun unquote(v: String): String = when {
        v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1).replace("''", "'")
        v.length >= 2 && v.startsWith('"') && v.endsWith('"') ->
            v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        else -> v
    }
}
