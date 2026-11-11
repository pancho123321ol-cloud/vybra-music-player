package com.vybra.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Letra asociada a una canción (formato LRC sincronizado o texto plano). */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val content: String,
    val synced: Boolean,
    /** "import", "manual" o "sync" (sincronizada en la app). */
    val source: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Resultado del análisis de audio (BPM, tonalidad, puntos de entrada/salida). */
@Entity(tableName = "track_analysis")
data class TrackAnalysis(
    @PrimaryKey val songId: Long,
    val bpm: Float,
    /** Posición del primer pulso detectado (fase del beat grid). */
    val beatOffsetMs: Long,
    /** 0 = C … 11 = B; -1 si no se pudo determinar. */
    val keyIndex: Int,
    val minor: Boolean,
    /** Confianza 0..1 de la estimación de tonalidad. */
    val keyConfidence: Float,
    /** Volumen medio relativo 0..1. */
    val energy: Float,
    /** Inicio del sonido (tras el silencio inicial): punto de entrada natural. */
    val introEndMs: Long,
    /** Fin del sonido útil (después solo queda fade o silencio): donde debe acabar la transición. */
    val outroStartMs: Long,
    val durationMs: Long,
    val analyzedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
)

/** Configuración personalizada de la transición entre dos canciones concretas. */
@Entity(tableName = "mix_transitions", primaryKeys = ["fromSongId", "toSongId"])
data class MixTransitionEntity(
    val fromSongId: Long,
    val toSongId: Long,
    val style: String,
    val durationMs: Long,
    /** Posición en la canción saliente donde empieza la transición; -1 = automático. */
    val exitMs: Long,
    /** Posición de entrada en la canción entrante; -1 = automático. */
    val entryMs: Long,
    /** Curva de volumen ("AUTO" = la del estilo). */
    @ColumnInfo(defaultValue = "AUTO") val volume: String = "AUTO",
    /** Tratamiento de EQ ("AUTO" = el del estilo). */
    @ColumnInfo(defaultValue = "AUTO") val eq: String = "AUTO",
    /** Efecto de filtro ("AUTO" = el del estilo). */
    @ColumnInfo(defaultValue = "AUTO") val effect: String = "AUTO",
    /** Duración en compases (0 = usar [durationMs]). */
    @ColumnInfo(defaultValue = "0") val bars: Int = 0,
    /** Efecto creativo: eco, frenado de vinilo… ("AUTO" = el del estilo). */
    @ColumnInfo(defaultValue = "AUTO") val fx: String = "AUTO",
    /** Bucle de la saliente en beats (-1 = el del estilo, 0 = sin bucle). */
    @ColumnInfo(defaultValue = "-1") val loopBeats: Int = -1,
    /** Sincronización de ritmos (-1 = según ajustes, 0 = no, 1 = sí). */
    @ColumnInfo(defaultValue = "-1") val beatSync: Int = -1,
)

/** Forma de onda resumida de una canción para el editor de transiciones. */
@Entity(tableName = "track_waveform")
data class TrackWaveform(
    @PrimaryKey val songId: Long,
    /** Un valor 0..255 por bloque de [TrackAnalyzerHopMs] ms. */
    val data: ByteArray,
    val hopMs: Double,
) {
    override fun equals(other: Any?) = other is TrackWaveform && other.songId == songId && other.data.contentEquals(data)
    override fun hashCode() = songId.hashCode()
}

@Dao
interface WaveformDao {
    @Query("SELECT * FROM track_waveform WHERE songId = :songId")
    suspend fun get(songId: Long): TrackWaveform?

    @Upsert
    suspend fun upsert(waveform: TrackWaveform)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    fun observe(songId: Long): Flow<LyricsEntity?>

    @Query("SELECT songId FROM lyrics")
    suspend fun songIdsWithLyrics(): List<Long>

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun delete(songId: Long)
}

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM track_analysis WHERE songId = :songId")
    suspend fun get(songId: Long): TrackAnalysis?

    @Query("SELECT * FROM track_analysis WHERE songId IN (:ids)")
    fun observe(ids: List<Long>): Flow<List<TrackAnalysis>>

    @Query("SELECT * FROM track_analysis")
    fun observeAll(): Flow<List<TrackAnalysis>>

    @Query("SELECT songId FROM track_analysis WHERE version >= :version")
    suspend fun analyzedIds(version: Int): List<Long>

    @Upsert
    suspend fun upsert(analysis: TrackAnalysis)
}

@Dao
interface MixTransitionDao {
    @Query("SELECT * FROM mix_transitions WHERE fromSongId = :from AND toSongId = :to")
    suspend fun get(from: Long, to: Long): MixTransitionEntity?

    @Query("SELECT * FROM mix_transitions WHERE fromSongId IN (:ids)")
    fun observeFrom(ids: List<Long>): Flow<List<MixTransitionEntity>>

    @Upsert
    suspend fun upsert(transition: MixTransitionEntity)

    @Query("DELETE FROM mix_transitions WHERE fromSongId = :from AND toSongId = :to")
    suspend fun delete(from: Long, to: Long)
}
