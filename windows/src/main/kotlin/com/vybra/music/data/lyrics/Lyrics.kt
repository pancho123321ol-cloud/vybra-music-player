package com.vybra.music.data.lyrics

import java.io.File
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.db.LyricsEntity
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Una palabra con su momento de inicio (incluye el espacio o signo que la sigue). */
data class LyricWord(val startMs: Long, val text: String)

/**
 * Una línea de letra. [words] solo existe si el archivo trae tiempos por palabra
 * (LRC mejorado `<mm:ss.xx>`); si no, se estiman con [ParsedLyrics.wordsAt].
 * [endMs] es el final indicado por la fuente (-1 si no se conoce).
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val endMs: Long = -1,
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val title: String? = null,
    val artist: String? = null,
    /** Desfase guardado en el archivo ([offset:], en ms; positivo = la letra aparece antes). */
    val offsetMs: Long = 0,
) {
    val isEmpty get() = lines.none { it.text.isNotBlank() }

    /** true si al menos una línea trae tiempos reales por palabra. */
    val hasWordTiming get() = lines.any { it.words != null }

    /** Índice de la línea que corresponde a [positionMs], o -1 antes de la primera. */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { result = mid; lo = mid + 1 } else hi = mid - 1
        }
        return result
    }

    /** Momento en que termina de cantarse la línea [i] (real o estimado). */
    fun endOf(i: Int): Long {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
        if (line.endMs > line.timeMs) return minOf(line.endMs, next)
        // Sin final conocido: ~0,3 s por sílaba (como mucho hasta la línea siguiente).
        val estimate = line.timeMs + 600 + VocalSync.syllables(line.text) * 300L
        return minOf(estimate, next).coerceAtLeast(line.timeMs + 400)
    }

    /**
     * Palabras de la línea [i] con su inicio y fin. Si la fuente no trae tiempos por palabra,
     * se reparten entre el inicio y el final de la línea según sus sílabas.
     */
    fun wordsAt(i: Int): List<TimedWord> {
        val line = lines[i]
        val end = endOf(i)
        val real = line.words
        if (real != null && real.isNotEmpty()) {
            return real.mapIndexed { k, w ->
                val wEnd = real.getOrNull(k + 1)?.startMs ?: end
                TimedWord(w.text, w.startMs, maxOf(wEnd, w.startMs + 80))
            }
        }
        val tokens = splitWords(line.text)
        if (tokens.isEmpty()) return emptyList()
        val weights = tokens.map { VocalSync.syllables(it).toDouble() + 0.3 }
        val total = weights.sum()
        val span = (end - line.timeMs).toDouble()
        var t = line.timeMs.toDouble()
        return tokens.mapIndexed { k, w ->
            val d = span * weights[k] / total
            TimedWord(w, t.toLong(), (t + d).toLong()).also { t += d }
        }
    }

    companion object {
        /** Divide en palabras conservando el espacio final de cada una. */
        fun splitWords(text: String): List<String> =
            Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
    }
}

/** Palabra lista para animar: [startMs, endMs). */
data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

/** Analizador de archivos LRC (incluye varias marcas por línea, [offset:] y LRC mejorado por palabra). */
object LrcParser {
    /** Marca de orden de bytes que algunos editores añaden al inicio de los LRC. */
    private val BOM = Char(0xFEFF)
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTag = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun parse(text: String): ParsedLyrics {
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        var offset = 0L
        var title: String? = null
        var artist: String? = null
        text.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { raw ->
            val line = raw.trim().trimStart(BOM)
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isNotEmpty() && line.startsWith("[")) {
                // Solo cuentan las marcas al inicio de la línea.
                var end = 0
                val times = ArrayList<Long>()
                for (m in stamps) {
                    if (m.range.first != end) break
                    times += toMs(m)
                    end = m.range.last + 1
                }
                val body = line.substring(end)
                val (content, words, lineEnd) = parseWords(body)
                times.forEach { timed += LyricLine(it, content, words, lineEnd) }
                return@forEach
            }
            val meta = metaTag.matchEntire(line)
            if (meta != null) {
                val value = meta.groupValues[2].trim()
                when (meta.groupValues[1].lowercase(Locale.ROOT)) {
                    "offset" -> offset = value.toLongOrNull() ?: 0L
                    "ti" -> title = value
                    "ar" -> artist = value
                }
                return@forEach
            }
            plain += line
        }
        return if (timed.isNotEmpty()) {
            fun shift(t: Long) = if (t < 0) t else (t - offset).coerceAtLeast(0)
            val lines = timed.map { l ->
                l.copy(
                    timeMs = shift(l.timeMs),
                    endMs = shift(l.endMs),
                    words = l.words?.map { it.copy(startMs = shift(it.startMs)) },
                )
            }.sortedBy { it.timeMs }
            ParsedLyrics(lines, synced = true, title = title, artist = artist, offsetMs = offset)
        } else {
            val trimmed = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            ParsedLyrics(trimmed.map { LyricLine(-1, it) }, synced = false, title = title, artist = artist, offsetMs = offset)
        }
    }

    /**
     * Lee las marcas por palabra `<mm:ss.xx>palabra`. Una marca al final sin texto indica
     * el final de la línea. Devuelve (texto limpio, palabras o null, fin o -1).
     */
    private fun parseWords(body: String): Triple<String, List<LyricWord>?, Long> {
        val tags = wordTag.findAll(body).toList()
        if (tags.isEmpty()) return Triple(body.trim(), null, -1L)
        val words = ArrayList<LyricWord>()
        var lineEnd = -1L
        val lead = body.substring(0, tags.first().range.first)
        val sb = StringBuilder(lead)
        tags.forEachIndexed { k, m ->
            val next = tags.getOrNull(k + 1)?.range?.first ?: body.length
            val piece = body.substring(m.range.last + 1, next)
            val t = toMs(m)
            if (piece.isBlank()) {
                if (k == tags.lastIndex) lineEnd = t
                sb.append(piece)
            } else {
                words += LyricWord(t, piece)
                sb.append(piece)
            }
        }
        val content = sb.toString().trim()
        if (words.isEmpty()) return Triple(content, null, lineEnd)
        // El texto previo a la primera marca se une a la primera palabra.
        if (lead.isNotBlank()) words[0] = words[0].copy(text = lead + words[0].text)
        return Triple(content, words, lineEnd)
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + ms
    }

    fun format(ms: Long): String = "[" + stamp(ms) + "]"

    private fun stamp(ms: Long): String {
        val min = ms / 60_000
        val sec = (ms % 60_000) / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.ROOT, "%02d:%02d.%02d", min, sec, cs)
    }

    /** Línea en formato LRC; con tiempos por palabra si los tiene (LRC mejorado). */
    fun formatLine(line: LyricLine): String {
        val words = line.words
        if (words.isNullOrEmpty()) return format(line.timeMs) + line.text
        return buildString {
            append(format(line.timeMs))
            words.forEach { append('<').append(stamp(it.startMs)).append('>').append(it.text) }
            if (line.endMs > 0) append('<').append(stamp(line.endMs)).append('>')
        }
    }

    fun toLrc(lines: List<LyricLine>, title: String?, artist: String?, offsetMs: Long = 0): String = buildString {
        if (!title.isNullOrBlank()) appendLine("[ti:$title]")
        if (!artist.isNullOrBlank() && !LrcLibClient.isUnknownArtist(artist)) appendLine("[ar:$artist]")
        if (offsetMs != 0L) appendLine("[offset:$offsetMs]")
        lines.forEach { appendLine(formatLine(it)) }
    }

    /** Cambia (o quita, si es 0) la etiqueta [offset:] de un LRC sin tocar el resto. */
    fun withOffset(content: String, offsetMs: Long): String {
        val rest = content.replace("\r\n", "\n").lines().filterNot {
            it.trim().trimStart(BOM).lowercase(Locale.ROOT).startsWith("[offset:")
        }.joinToString("\n")
        return if (offsetMs == 0L) rest else "[offset:$offsetMs]\n$rest"
    }
}

data class BulkImportResult(val matched: Int, val unmatched: List<String>)

class LyricsRepository(db: VybraDatabase) {
    private val dao = db.lyricsDao()

    fun observe(songId: Long): Flow<ParsedLyrics?> =
        dao.observe(songId).map { it?.let { e -> LrcParser.parse(e.content) } }

    fun observeRaw(songId: Long): Flow<LyricsEntity?> = dao.observe(songId)

    suspend fun save(songId: Long, content: String, source: String) {
        val parsed = LrcParser.parse(content)
        dao.upsert(LyricsEntity(songId, content.trim(), parsed.synced, source))
    }

    suspend fun delete(songId: Long) = dao.delete(songId)

    /** Guarda el desfase de la letra (positivo = aparece antes), conservando el resto del archivo. */
    suspend fun setOffset(songId: Long, offsetMs: Long) {
        val current = dao.observe(songId).first() ?: return
        dao.upsert(current.copy(content = LrcParser.withOffset(current.content, offsetMs).trim()))
    }

    /** Canciones ya buscadas en internet en esta sesión (para no repetir la consulta). */
    private val searchedOnline = java.util.Collections.synchronizedSet(HashSet<Long>())

    fun wasSearchedOnline(songId: Long) = songId in searchedOnline

    /**
     * Busca en internet la letra de [song] y la guarda si encuentra una coincidencia clara.
     * Lanza IOException si no hay conexión.
     */
    suspend fun fetchOnline(song: Song): OnlineLyrics? {
        searchedOnline += song.id
        val found = LrcLibClient.bestMatch(song) ?: return null
        val content = found.content ?: return null
        save(song.id, content, "lrclib")
        return found
    }

    enum class SyncSource { ONLINE, AUDIO }

    class AutoSyncResult(val lines: List<LyricLine>, val source: SyncSource, val matched: Int, val triedOnline: Boolean = false)

    /**
     * Sincroniza automáticamente la letra [text] (la del usuario, sin tiempos o con ellos):
     *  1. Si se permite internet, busca una versión sincronizada de la canción y copia sus
     *     tiempos a las líneas que coinciden por texto.
     *  2. Si no, analiza la voz de la canción para estimar cuándo empieza cada línea.
     */
    suspend fun autoSync(
        song: Song,
        text: String,
        useInternet: Boolean,
        onStage: (String) -> Unit,
    ): AutoSyncResult? {
        val parsed = LrcParser.parse(text)
        // Las líneas en blanco (separación de estrofas) se conservan como pista para el análisis.
        val raw = parsed.lines.map { it.text }
        val lines = raw.filter { it.isNotBlank() }.map { it.trim() }
        if (lines.isEmpty()) return null
        if (useInternet) {
            onStage("Buscando la versión sincronizada en internet…")
            val online = runCatching { LrcLibClient.bestMatch(song) }
                .onFailure { com.vybra.music.Log.w("LyricsSync", "Búsqueda en LRCLIB fallida", it) }
                .getOrNull()
            com.vybra.music.Log.i("LyricsSync", "LRCLIB: ${online?.let { "${it.artist} - ${it.track} (${it.durationSec}s) synced=${it.isSynced}" } ?: "sin coincidencia"}")
            val synced = (online?.wordSynced ?: online?.synced)?.let { LrcParser.parse(it) }?.takeIf { it.synced }
            if (synced != null) {
                val transfer = withContext(Dispatchers.Default) {
                    LyricsTextAligner.transfer(lines, synced.lines, song.duration)
                }
                if (transfer != null) {
                    // Si la línea es idéntica a la de referencia, se copian también sus tiempos por palabra.
                    val byText = synced.lines.groupBy { it.timeMs }
                    val result = lines.zip(transfer.times) { t, ms ->
                        val ref = byText[ms]?.firstOrNull { it.text.trim() == t }
                        if (ref != null) ref.copy(text = t) else LyricLine(ms, t)
                    }
                    return AutoSyncResult(result, SyncSource.ONLINE, transfer.matched)
                }
            }
        }
        onStage("Analizando la voz de la canción…")
        val times = withContext(Dispatchers.Default) { VocalSync.align(song.path, raw, song.duration) } ?: return null
        return AutoSyncResult(lines.zip(times) { t, ms -> LyricLine(ms, t) }, SyncSource.AUDIO, 0, triedOnline = useInternet)
    }

    /** Importa un archivo LRC/TXT y lo asocia a la canción indicada. */
    suspend fun importFor(songId: Long, uri: File): ParsedLyrics? {
        val text = readText(uri) ?: return null
        val parsed = LrcParser.parse(text)
        if (parsed.isEmpty) return null
        save(songId, text, "import")
        return parsed
    }

    /**
     * Importa varios archivos y los asocia automáticamente comparando el nombre del
     * archivo con el del audio, con "Artista - Título", o con las etiquetas [ti:]/[ar:].
     */
    suspend fun importMany(uris: List<File>, songs: List<Song>): BulkImportResult {
        val byFile = HashMap<String, Song>()
        val byArtistTitle = HashMap<String, Song>()
        val byTitle = HashMap<String, MutableList<Song>>()
        songs.forEach { s ->
            byFile[key(s.path.substringAfterLast('/').substringBeforeLast('.'))] = s
            byArtistTitle[key("${s.artist} - ${s.title}")] = s
            byArtistTitle[key("${s.title} - ${s.artist}")] = s
            byTitle.getOrPut(key(s.title)) { mutableListOf() } += s
        }
        var matched = 0
        val unmatched = ArrayList<String>()
        for (uri in uris) {
            val name = uri.name
            val text = readText(uri)
            if (text == null) { unmatched += name; continue }
            val parsed = LrcParser.parse(text)
            if (parsed.isEmpty) { unmatched += name; continue }
            val base = key(name.substringBeforeLast('.'))
            val song = byFile[base]
                ?: byArtistTitle[base]
                ?: parsed.title?.let { t ->
                    val candidates = byTitle[key(t)].orEmpty()
                    candidates.firstOrNull { parsed.artist != null && key(it.artist) == key(parsed.artist) }
                        ?: candidates.singleOrNull()
                }
                ?: byTitle[base]?.singleOrNull()
            if (song == null) { unmatched += name; continue }
            save(song.id, text, "import")
            matched++
        }
        return BulkImportResult(matched, unmatched)
    }

    private fun key(s: String) = MusicRepository.normalize(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Lee el archivo como UTF-8 y, si no es válido, como Windows-1252 (habitual en LRC antiguos). */
    private suspend fun readText(uri: File): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            uri.readBytes()
        }.getOrNull() ?: return@withContext null
        if (bytes.size > 512 * 1024) return@withContext null
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
