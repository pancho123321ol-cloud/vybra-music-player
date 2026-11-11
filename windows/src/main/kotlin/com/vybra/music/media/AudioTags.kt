package com.vybra.music.media

import com.vybra.music.data.db.Song
import com.vybra.music.data.scanner.MediaScanner
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Lectura de etiquetas (título, artista, álbum…) y carátulas incrustadas. */
object AudioTags {
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private val MIME = mapOf(
        "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav", "m4a" to "audio/mp4",
        "mp4" to "audio/mp4", "m4b" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/opus", "wma" to "audio/x-ms-wma", "aiff" to "audio/aiff", "aif" to "audio/aiff",
        "alac" to "audio/mp4", "ape" to "audio/ape", "wv" to "audio/wavpack", "mka" to "audio/x-matroska",
    )

    private class Fields(
        val title: String?, val artist: String?, val album: String?, val albumArtist: String?,
        val track: Int, val year: Int, val durationMs: Long,
    )

    fun read(file: File, id: Long, path: String, modifiedSec: Long): Song? {
        val fields = readJaudiotagger(file) ?: readFfmpeg(path) ?: return null
        val folder = path.substringBeforeLast('/', "")
        val title = fields.title.clean() ?: file.nameWithoutExtension
        val artist = fields.artist.clean() ?: MediaScanner.UNKNOWN_ARTIST
        val albumArtist = fields.albumArtist.clean()
        val album = fields.album.clean() ?: folder.substringAfterLast('/').ifBlank { MediaScanner.UNKNOWN_ALBUM }
        // Igual que MediaStore: un álbum se identifica por su nombre y su artista de álbum
        // (o la carpeta si no tiene artista de álbum, para no mezclar recopilatorios).
        val albumKey = "${album.lowercase()}|${(albumArtist ?: folder).lowercase()}"
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = MediaScanner.stableHash("artist|" + artist.lowercase()),
            album = album,
            albumId = MediaScanner.stableHash("album|$albumKey"),
            albumArtist = albumArtist,
            duration = fields.durationMs,
            track = fields.track % 1000,
            year = fields.year,
            path = path,
            folder = folder,
            dateAdded = modifiedSec,
            size = file.length(),
            mimeType = MIME[file.extension.lowercase()],
        )
    }

    private fun readJaudiotagger(file: File): Fields? = runCatching {
        val af = AudioFileIO.read(file)
        val tag: Tag? = af.tag
        fun get(key: FieldKey) = runCatching { tag?.getFirst(key) }.getOrNull()
        val header = af.audioHeader
        val duration = runCatching { (header.preciseTrackLength * 1000).toLong() }.getOrNull()
            ?: (header.trackLength * 1000L)
        if (duration <= 0) return@runCatching null
        Fields(
            title = get(FieldKey.TITLE), artist = get(FieldKey.ARTIST), album = get(FieldKey.ALBUM),
            albumArtist = get(FieldKey.ALBUM_ARTIST),
            track = leadingInt(get(FieldKey.TRACK)), year = year(get(FieldKey.YEAR)),
            durationMs = duration,
        )
    }.getOrNull()

    private fun readFfmpeg(path: String): Fields? {
        val p = AudioDecoder.probe(path) ?: return null
        val t = p.tags
        return Fields(
            title = t["title"], artist = t["artist"], album = t["album"],
            albumArtist = t["album_artist"] ?: t["albumartist"],
            track = leadingInt(t["track"]), year = year(t["date"] ?: t["year"]),
            durationMs = p.durationMs,
        )
    }

    /** Imagen incrustada en el archivo (la portada frontal si hay varias). */
    fun embeddedArtwork(file: File): ByteArray? = runCatching {
        val tag = AudioFileIO.read(file).tag ?: return null
        val art = tag.firstArtwork ?: return null
        art.binaryData?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Letra incrustada en las etiquetas (USLT/LYRICS), si existe. */
    fun embeddedLyrics(file: File): String? = runCatching {
        AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun String?.clean(): String? =
        this?.trim()?.trim('\u0000')?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) }

    private fun leadingInt(s: String?): Int = s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    private fun year(s: String?): Int = s?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() } ?: 0
}
