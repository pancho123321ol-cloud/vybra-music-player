package com.vybra.music.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

@Database(
    entities = [
        Song::class, Playlist::class, PlaylistSong::class, Favorite::class, PlayEvent::class,
        LyricsEntity::class, TrackAnalysis::class, MixTransitionEntity::class, TrackWaveform::class,
    ],
    version = 4,
    exportSchema = true,
    // v2: letras, análisis de audio y transiciones del mix (solo tablas nuevas, sin pérdida de datos).
    // v3: formas de onda y transiciones manuales (volumen/EQ/efecto/compases), con valores por defecto.
    // v4: efecto creativo, bucles y sincronización por transición.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class VybraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun mixTransitionDao(): MixTransitionDao
    abstract fun waveformDao(): WaveformDao

    companion object {
        fun build(dir: File): VybraDatabase =
            // Sin migración destructiva: las playlists y favoritos del usuario deben conservarse.
            Room.databaseBuilder<VybraDatabase>(
                name = File(dir, "vybra.db").absolutePath,
                factory = { VybraDatabase_Impl() },
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
