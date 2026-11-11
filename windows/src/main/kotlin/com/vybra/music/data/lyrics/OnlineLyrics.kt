package com.vybra.music.data.lyrics

import com.vybra.music.data.MusicRepository
import com.vybra.music.data.db.Song
import com.vybra.music.data.scanner.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Una letra encontrada en internet. */
data class OnlineLyrics(
    val id: Long,
    val track: String,
    val artist: String,
    val album: String?,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    /** LRC mejorado con tiempos por palabra (de lyricsfile), si LRCLIB los tiene. */
    val wordSynced: String? = null,
) {
    val isSynced get() = !synced.isNullOrBlank() || !wordSynced.isNullOrBlank()
    val hasWords get() = !wordSynced.isNullOrBlank()
    val content: String? get() = wordSynced?.takeIf { it.isNotBlank() }
        ?: synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
}

/**
 * Cliente de LRCLIB (lrclib.net): base de datos abierta y gratuita de letras,
 * muchas de ellas sincronizadas. No requiere cuenta ni clave.
 * Solo se envían el título, el artista, el álbum y la duración de la canción.
 */
object LrcLibClient {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "VYBRA/1.0.0 (Android; reproductor de música local)"

    /** Búsqueda libre (lo que escribe el usuario). */
    suspend fun search(query: String): List<OnlineLyrics> =
        if (query.isBlank()) emptyList() else parseList(request("/search?q=${enc(query)}"))

    /**
     * Busca la mejor letra para [song]: primero por coincidencia exacta y luego por
     * búsqueda, comparando título, artista y duración. Devuelve null si nada coincide
     * lo suficiente para asociarlo sin preguntar.
     */
    suspend fun bestMatch(song: Song): OnlineLyrics? {
        val (title, artist) = queryFor(song)
        if (title.isBlank()) return null
        val seconds = song.duration / 1000.0
        if (artist.isNotBlank() && song.album.isNotBlank() && seconds > 0) {
            val exact = runCatching {
                request(
                    "/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                        "&album_name=${enc(song.album)}&duration=${seconds.toInt()}",
                )
            }.getOrNull()
            if (exact != null) {
                val item = parse(JSONObject(exact))
                if (item.content != null && score(item, title, artist, seconds) > 0) return item
            }
        }
        val candidates = LinkedHashMap<Long, OnlineLyrics>()
        if (artist.isNotBlank()) {
            parseList(request("/search?track_name=${enc(title)}&artist_name=${enc(artist)}")).forEach { candidates[it.id] = it }
            if (candidates.isEmpty()) parseList(request("/search?q=${enc("$artist $title")}")).forEach { candidates[it.id] = it }
        } else {
            parseList(request("/search?track_name=${enc(title)}")).forEach { candidates[it.id] = it }
        }
        return candidates.values
            .filter { it.content != null }
            .map { it to score(it, title, artist, seconds) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Texto de búsqueda a partir del título y el artista mostrados (sin "Artista desconocido"). */
    fun queryText(title: String, artist: String): String =
        listOf(title, artist.takeUnless { isUnknownArtist(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")

    fun isUnknownArtist(a: String) = a.isBlank() || a.equals("<unknown>", true) || a == MediaScanner.UNKNOWN_ARTIST

    /** Texto inicial del buscador para una canción. */
    fun defaultQuery(song: Song): String {
        val (title, artist) = queryFor(song)
        return listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Título y artista limpios; si el artista es desconocido, prueba con "Artista - Título" del archivo. */
    private fun queryFor(song: Song): Pair<String, String> {
        var title = song.title
        var artist = song.artist.takeUnless {
            it.isBlank() || it.equals("<unknown>", true) || it == MediaScanner.UNKNOWN_ARTIST
        } ?: ""
        if (artist.isEmpty()) {
            val name = song.path.substringAfterLast('/').substringBeforeLast('.')
            val parts = (if (title.contains(" - ")) title else name).split(" - ", limit = 2)
            if (parts.size == 2) { artist = parts[0]; title = parts[1] }
        }
        return cleanTitle(title) to primaryArtist(artist)
    }

    private val noise = Regex(
        """\s*[(\[]\s*(official|oficial|video|audio|lyric|letra|visualizer|remaster|hd|hq|4k|explicit|clean|prod\.?)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val feat = Regex("""\s*[(\[]?\s*(feat\.?|ft\.?|featuring)\s+[^)\]]*[)\]]?\s*$""", RegexOption.IGNORE_CASE)

    fun cleanTitle(t: String): String =
        feat.replace(noise.replace(t, ""), "")
            .replace(Regex("""\s+-\s+(\d{4}\s+)?remaster(ed)?.*$""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .trim()

    private fun primaryArtist(a: String): String =
        a.split(Regex("""\s*(,|&|;|/|\bfeat\.?|\bft\.?|\bx\b|\by\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: a.trim()

    /** Puntuación de coincidencia; 0 o menos significa que no es la misma canción. */
    private fun score(item: OnlineLyrics, title: String, artist: String, seconds: Double): Double {
        val diff = if (seconds > 0 && item.durationSec > 0) abs(item.durationSec - seconds) else 0.0
        if (diff > 8) return 0.0
        val t = similarity(cleanTitle(item.track), title)
        if (t < 0.5) return 0.0
        val a = if (artist.isBlank()) 0.5 else similarity(item.artist, artist).coerceAtLeast(
            if (key(item.artist).contains(key(artist)) || key(item.track).contains(key(artist))) 0.8 else 0.0,
        )
        if (artist.isNotBlank() && a < 0.34) return 0.0
        // La duración pesa mucho: otra versión (intro más larga, radio edit) desfasa toda la letra.
        val durationScore = if (diff <= 1.5) 4.0 else (1 - diff / 8) * 2
        return t * 2 + a * 1.5 + durationScore + (if (item.isSynced) 1.0 else 0.0) + (if (item.hasWords) 0.7 else 0.0) -
            (if (item.instrumental) 3 else 0)
    }

    private fun key(s: String) = MusicRepository.normalize(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Similitud por palabras (Dice), tolerante a acentos y signos. */
    fun similarity(a: String, b: String): Double {
        val x = key(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val y = key(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Devuelve el cuerpo de la respuesta, o null si no existe (404). Lanza IOException sin conexión. */
    private suspend fun request(path: String): String? = withContext(Dispatchers.IO) {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("LRCLIB respondió $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseList(body: String?): List<OnlineLyrics> {
        if (body.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parse) }
    }

    private fun parse(o: JSONObject) = OnlineLyrics(
        id = o.optLong("id"),
        track = o.optString("trackName"),
        artist = o.optString("artistName"),
        album = o.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
        durationSec = o.optDouble("duration", 0.0),
        synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() },
        plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() },
        instrumental = o.optBoolean("instrumental"),
        wordSynced = if (o.optBoolean("hasWordSync")) LyricsFile.toEnhancedLrc(o.optString("lyricsfile")) else null,
    )
}
