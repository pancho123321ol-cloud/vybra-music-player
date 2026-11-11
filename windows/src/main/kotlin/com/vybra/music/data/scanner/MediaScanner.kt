package com.vybra.music.data.scanner

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.vybra.music.Log
import com.vybra.music.data.db.Song
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.prefs.SettingsRepository
import com.vybra.music.media.AudioTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

data class ScanState(
    val isScanning: Boolean = false,
    val lastAdded: Int = 0,
    val lastRemoved: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

data class FolderInfo(val path: String, val songCount: Int)

/**
 * Recorre las carpetas de música elegidas por el usuario, lee las etiquetas de cada
 * archivo y sincroniza la biblioteca en Room. Solo vuelve a leer las etiquetas de los
 * archivos nuevos o modificados.
 */
class MediaScanner(
    private val db: VybraDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var watchJob: Job? = null
    private var pendingRescan: Job? = null
    @Volatile private var lastFiles: List<Song> = emptyList()

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

    /** Carpetas raíz que se escanean. */
    suspend fun roots(): List<File> {
        val chosen = settings.current().musicFolders
        return if (chosen.isEmpty()) listOf(defaultMusicFolder()) else chosen.map { File(it) }
    }

    private suspend fun performScan(): ScanState {
        val prefs = settings.current()
        val excluded = prefs.excludedFolders
        val minDurationMs = prefs.minDurationSec * 1000L
        val dao = db.songDao()
        val inDb = dao.all().associateBy { it.id }
        // Las canciones excluidas no están en la base de datos: se reutiliza también lo leído en el último escaneo.
        val known = lastFiles.associateBy { it.id } + inDb
        val found = ArrayList<Song>()
        for (root in roots()) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { dir -> !dir.isHidden || dir == root }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { file ->
                    val path = normalize(file)
                    val id = idFor(path)
                    val modified = file.lastModified() / 1000
                    val old = known[id]
                    val song = if (old != null && old.size == file.length() && old.dateAdded == modified) old
                    else runCatching { AudioTags.read(file, id, path, modified) }
                        .onFailure { Log.w(TAG, "No se pudo leer $path: ${it.message}") }
                        .getOrNull()
                    if (song != null) found += song
                }
        }
        lastFiles = found
        val songs = found.filter { song ->
            song.duration >= minDurationMs &&
                !(prefs.hideMessagingAudio && isMessagingAudioFolder(song.folder)) &&
                excluded.none { ex -> song.folder == ex || song.folder.startsWith("$ex/") }
        }
        var removed = 0
        var added = 0
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val existing = inDb.keys
                val newIds = songs.mapTo(HashSet()) { it.id }
                val toDelete = existing.filterNot { it in newIds }
                toDelete.chunked(500).forEach { dao.deleteByIds(it) }
                songs.chunked(500).forEach { dao.upsertAll(it) }
                removed = toDelete.size
                added = newIds.count { it !in existing }
            }
        }
        return ScanState(lastAdded = added, lastRemoved = removed, total = songs.size)
    }

    /** Lista todas las carpetas que contienen música, incluidas las excluidas. */
    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        if (lastFiles.isEmpty()) performScan()
        lastFiles.groupingBy { it.folder }.eachCount()
            .map { (path, count) -> FolderInfo(path, count) }
            .sortedBy { it.path.lowercase() }
    }

    /** Vigila las carpetas de música para actualizar la biblioteca automáticamente. */
    fun startObserving() {
        if (watchJob != null) return
        watchJob = scope.launch(Dispatchers.IO) {
            // Se reinicia la vigilancia cuando el usuario cambia las carpetas.
            settings.settings.map { it.musicFolders }.distinctUntilChanged().collect {
                watchFolders()
            }
        }
        scope.launch {
            settings.settings.map { it.musicFolders }.distinctUntilChanged().drop(1).collect { scan() }
        }
    }

    private var watcher: WatchService? = null

    private suspend fun watchFolders() {
        runCatching { watcher?.close() }
        val ws = FileSystems.getDefault().newWatchService()
        watcher = ws
        val keys = HashMap<WatchKey, Path>()
        fun register(dir: Path) {
            runCatching {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isDirectory(it) }.forEach { d ->
                        runCatching {
                            keys[d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)] = d
                        }
                    }
                }
            }
        }
        roots().filter { it.isDirectory }.forEach { register(it.toPath()) }
        try {
            while (scope.isActive) {
                val key = ws.poll(2, TimeUnit.SECONDS) ?: continue
                val dir = keys[key]
                for (event in key.pollEvents()) {
                    val name = event.context() as? Path ?: continue
                    val child = dir?.resolve(name)
                    if (child != null && event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) register(child)
                }
                key.reset()
                pendingRescan?.cancel()
                pendingRescan = scope.launch {
                    delay(3_000)
                    scan()
                }
            }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
        }
    }

    companion object {
        private const val TAG = "MediaScanner"
        const val UNKNOWN_ARTIST = "Artista desconocido"
        const val UNKNOWN_ALBUM = "Álbum desconocido"

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "mpeg", "mp2", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "wma", "aiff", "aif", "alac", "ape", "wv", "mka", "mp4", "m4b",
        )

        fun defaultMusicFolder(): File = File(System.getProperty("user.home"), "Music")

        /** Ruta con barras normales: todo el código de carpetas usa '/' como separador. */
        fun normalize(file: File): String = file.absolutePath.replace('\\', '/')

        /** Id estable (positivo) a partir de la ruta, sin distinguir mayúsculas como Windows. */
        fun idFor(path: String): Long = stableHash(path.lowercase())

        fun stableHash(text: String): Long {
            // FNV-1a de 64 bits, recortado a positivo.
            var h = -0x340d631b7bdddcdbL
            for (ch in text) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return (h and Long.MAX_VALUE).coerceAtLeast(1)
        }

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
