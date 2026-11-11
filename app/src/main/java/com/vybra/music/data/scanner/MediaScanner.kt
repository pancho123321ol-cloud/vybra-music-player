package com.vybra.music.data.scanner

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import com.vybra.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanState(
    val isScanning: Boolean = false,
    val lastAdded: Int = 0,
    val lastRemoved: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

data class FolderInfo(val path: String, val songCount: Int)

/**
 * Lee la música del dispositivo desde MediaStore y la sincroniza con Room.
 * Soporta todos los formatos que Android indexa (MP3, FLAC, WAV, M4A, AAC, OGG, OPUS, etc.).
 */
class MediaScanner(
    private val context: Context,
    private val db: VybraDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var observer: ContentObserver? = null
    private var pendingRescan: Job? = null

    fun scanAsync() {
        scope.launch { scan() }
    }

    suspend fun scan(): ScanState = mutex.withLock {
        _state.update { it.copy(isScanning = true, error = null) }
        val result = try {
            withContext(Dispatchers.IO) { performScan() }
        } catch (e: Exception) {
            Log.e(TAG, "Error al escanear", e)
            _state.value.copy(error = e.message ?: "Error desconocido")
        }
        _state.value = result.copy(isScanning = false)
        settings.setLastScan(System.currentTimeMillis())
        _state.value
    }

    private suspend fun performScan(): ScanState {
        val prefs = settings.current()
        val excluded = prefs.excludedFolders
        val minDurationMs = prefs.minDurationSec * 1000L
        val songs = queryMediaStore().filter { song ->
            song.duration >= minDurationMs &&
                !(prefs.hideMessagingAudio && isMessagingAudioFolder(song.folder)) &&
                excluded.none { ex -> song.folder == ex || song.folder.startsWith("$ex/") }
        }
        val dao = db.songDao()
        var removed = 0
        var added = 0
        db.withTransaction {
            val existing = dao.allIds().toHashSet()
            val newIds = songs.mapTo(HashSet()) { it.id }
            val toDelete = existing.filterNot { it in newIds }
            toDelete.chunked(500).forEach { dao.deleteByIds(it) }
            songs.chunked(500).forEach { dao.upsertAll(it) }
            removed = toDelete.size
            added = newIds.count { it !in existing }
        }
        return ScanState(lastAdded = added, lastRemoved = removed, total = songs.size)
    }

    private fun queryMediaStore(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = ArrayList<Song>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else -1

            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: ""
                val displayName = c.getString(nameCol) ?: path.substringAfterLast('/')
                val rawTitle = c.getString(titleCol)
                val title = if (rawTitle.isNullOrBlank()) displayName.substringBeforeLast('.') else rawTitle
                val folder = path.substringBeforeLast('/', "")
                result += Song(
                    id = c.getLong(idCol),
                    title = title,
                    artist = cleanTag(c.getString(artistCol)) ?: UNKNOWN_ARTIST,
                    artistId = c.getLong(artistIdCol),
                    album = cleanTag(c.getString(albumCol)) ?: (folder.substringAfterLast('/').ifBlank { UNKNOWN_ALBUM }),
                    albumId = c.getLong(albumIdCol),
                    albumArtist = if (albumArtistCol >= 0) cleanTag(c.getString(albumArtistCol)) else null,
                    duration = c.getLong(durationCol),
                    track = c.getInt(trackCol) % 1000,
                    year = c.getInt(yearCol),
                    path = path,
                    folder = folder,
                    dateAdded = c.getLong(dateCol),
                    size = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        return result
    }

    /** Lista todas las carpetas que contienen música, incluidas las excluidas. */
    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        queryMediaStore().groupingBy { it.folder }.eachCount()
            .map { (path, count) -> FolderInfo(path, count) }
            .sortedBy { it.path.lowercase() }
    }

    /** Observa cambios en MediaStore para actualizar la biblioteca automáticamente. */
    fun startObserving() {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pendingRescan?.cancel()
                pendingRescan = scope.launch {
                    delay(3_000)
                    scan()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs,
        )
        observer = obs
    }

    private fun cleanTag(value: String?): String? =
        value?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) }

    companion object {
        private const val TAG = "MediaScanner"
        const val UNKNOWN_ARTIST = "Artista desconocido"
        const val UNKNOWN_ALBUM = "Álbum desconocido"

        /**
         * Carpetas de audios recibidos y notas de voz de WhatsApp (incluida WhatsApp Business).
         * "WhatsApp Documents" no se incluye porque suele contener música enviada como archivo.
         */
        private val MESSAGING_AUDIO_FOLDERS = setOf(
            "whatsapp audio",
            "whatsapp voice notes",
            "whatsapp business audio",
            "whatsapp business voice notes",
        )

        fun isMessagingAudioFolder(folder: String): Boolean =
            folder.split('/').any { it.lowercase() in MESSAGING_AUDIO_FOLDERS }
    }
}
