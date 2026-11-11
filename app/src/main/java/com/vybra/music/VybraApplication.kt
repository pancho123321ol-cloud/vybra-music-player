package com.vybra.music

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.vybra.music.artwork.ArtworkFetcher
import com.vybra.music.artwork.ArtworkKeyer
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.PlaylistRepository
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.lyrics.LyricsRepository
import com.vybra.music.data.prefs.SettingsRepository
import com.vybra.music.data.scanner.MediaScanner
import com.vybra.music.playback.AudioEffectsManager
import com.vybra.music.playback.PlayerConnection
import com.vybra.music.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VybraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ArtworkKeyer())
                add(ArtworkFetcher.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .crossfade(220)
            .build()
}

/** Contenedor de dependencias manual (inyección simple, sin frameworks). */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database: VybraDatabase = VybraDatabase.build(app)
    val settings = SettingsRepository(app)
    val music = MusicRepository(database, appScope)
    val playlists = PlaylistRepository(app, database)
    val scanner = MediaScanner(app, database, settings, appScope)
    val player = PlayerConnection(app, appScope, settings)
    val audioEffects = AudioEffectsManager(app, settings, appScope)
    val sleepTimer = SleepTimer(appScope)
    val lyrics = LyricsRepository(app, database)
    val spectrum = com.vybra.music.playback.audio.SpectrumAnalyzer()
    val analysis = com.vybra.music.data.analysis.AnalysisRepository(app, database, appScope)

    /** Motor de Playlist Mix del servicio de reproducción (null si el servicio no está activo). */
    @Volatile var mixEngine: com.vybra.music.playback.mix.MixEngine? = null

    /** Par (saliente, entrante) cuya transición se está previsualizando aunque el mix esté apagado. */
    @Volatile var mixPreviewPair: Pair<Long, Long>? = null

    /** Configuración sin guardar del editor que se usa al previsualizar esa transición. */
    @Volatile var mixPreviewDraft: com.vybra.music.data.db.MixTransitionEntity? = null

    private var libraryStarted = false

    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(app, audioPermission()) ==
        PackageManager.PERMISSION_GRANTED

    /** Inicia el escaneo y la observación de la biblioteca una vez concedido el permiso. */
    fun onPermissionGranted() {
        if (libraryStarted) return
        libraryStarted = true
        scanner.scanAsync()
        scanner.startObserving()
    }

    companion object {
        fun audioPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as VybraApplication).container
