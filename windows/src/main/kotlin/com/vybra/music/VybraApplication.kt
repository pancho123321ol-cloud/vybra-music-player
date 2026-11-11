package com.vybra.music

import com.vybra.music.artwork.ArtworkLoader
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.PlaylistRepository
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.lyrics.LyricsRepository
import com.vybra.music.data.prefs.SettingsRepository
import com.vybra.music.data.scanner.MediaScanner
import com.vybra.music.playback.AudioEffectsManager
import com.vybra.music.playback.PlaybackService
import com.vybra.music.playback.PlayerConnection
import com.vybra.music.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Carpetas de datos de la app (%APPDATA%\VYBRA). */
object AppDirs {
    val data: File by lazy {
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".vybra")
        File(base, "VYBRA").apply { mkdirs() }
    }
    val cache: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.let(::File) ?: data
        File(base, "VYBRA/cache").apply { mkdirs() }
    }
}

/** Contenedor de dependencias manual (inyección simple, sin frameworks). */
class AppContainer {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database: VybraDatabase = VybraDatabase.build(AppDirs.data)
    val settings = SettingsRepository(AppDirs.data)
    val music = MusicRepository(database, appScope)
    val playlists = PlaylistRepository(database)
    val scanner = MediaScanner(database, settings, appScope)
    val player = PlayerConnection(appScope, settings)
    val audioEffects = AudioEffectsManager(settings, appScope)
    val sleepTimer = SleepTimer(appScope)
    val lyrics = LyricsRepository(database)
    val spectrum = com.vybra.music.playback.audio.SpectrumAnalyzer()
    val analysis = com.vybra.music.data.analysis.AnalysisRepository(database, appScope)
    val playback = PlaybackService(this)

    /** Motor de Playlist Mix del servicio de reproducción (null si no está activo). */
    @Volatile var mixEngine: com.vybra.music.playback.mix.MixEngine? = null

    /** Par (saliente, entrante) cuya transición se está previsualizando aunque el mix esté apagado. */
    @Volatile var mixPreviewPair: Pair<Long, Long>? = null

    /** Configuración sin guardar del editor que se usa al previsualizar esa transición. */
    @Volatile var mixPreviewDraft: com.vybra.music.data.db.MixTransitionEntity? = null

    private var libraryStarted = false

    init {
        val songDao = database.songDao()
        ArtworkLoader.pathOf = { id -> songDao.byId(id)?.path }
    }

    /** Arranca el motor de audio, el escaneo y la vigilancia de las carpetas de música. */
    fun start() {
        if (libraryStarted) return
        libraryStarted = true
        playback.start()
        player.attach(playback)
        scanner.scanAsync()
        scanner.startObserving()
    }

    fun shutdown() {
        playback.shutdown()
    }

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(): AppContainer = instance ?: synchronized(this) { instance ?: AppContainer().also { instance = it } }
    }
}

val appContainer: AppContainer get() = AppContainer.get()
