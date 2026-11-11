package com.vybra.music.data.analysis

import android.content.Context
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import com.vybra.music.data.db.TrackAnalysis
import com.vybra.music.data.db.TrackWaveform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AnalysisProgress(val done: Int, val total: Int, val running: Boolean)

/** Análisis de BPM/tonalidad bajo demanda, guardado en Room para no repetirlo. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisRepository(
    private val context: Context,
    db: VybraDatabase,
    private val scope: CoroutineScope,
) {
    private val dao = db.analysisDao()
    private val waveformDao = db.waveformDao()

    /** Forma de onda de la canción (la analiza si todavía no existe). */
    suspend fun waveform(song: Song): TrackWaveform? {
        // Solo vale la forma de onda de un análisis actual (formato de 3 bandas).
        if (cached(song.id) != null) waveformDao.get(song.id)?.let { return it }
        ensure(song)
        return waveformDao.get(song.id)
    }
    // Como máximo dos decodificaciones simultáneas para limitar CPU y batería.
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)
    private val lock = Mutex()
    private val inFlight = HashMap<Long, Deferred<TrackAnalysis?>>()
    private var batchJob: Job? = null

    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress: StateFlow<AnalysisProgress?> = _progress.asStateFlow()

    fun observe(ids: List<Long>): Flow<List<TrackAnalysis>> = dao.observe(ids)
    fun observeAll(): Flow<List<TrackAnalysis>> = dao.observeAll()

    suspend fun cached(songId: Long): TrackAnalysis? =
        dao.get(songId)?.takeIf { it.version >= TrackAnalyzer.VERSION }

    /** Devuelve el análisis guardado o lo calcula ahora. */
    suspend fun ensure(song: Song): TrackAnalysis? {
        cached(song.id)?.let { return it }
        val job = lock.withLock {
            inFlight.getOrPut(song.id) {
                scope.async(dispatcher) {
                    TrackAnalyzer.analyze(context, song.id, song.uri, song.duration)?.let { r ->
                        waveformDao.upsert(TrackWaveform(song.id, r.waveform, TrackAnalyzer.WAVEFORM_HOP_MS))
                        dao.upsert(r.analysis)
                        r.analysis
                    }
                }
            }
        }
        return try { job.await() } finally { lock.withLock { inFlight.remove(song.id) } }
    }

    /** Analiza en segundo plano las canciones que aún no tienen análisis. */
    fun analyzeAll(songs: List<Song>) {
        if (batchJob?.isActive == true) return
        batchJob = scope.launch {
            val done = withContext(Dispatchers.IO) { dao.analyzedIds(TrackAnalyzer.VERSION).toHashSet() }
            val pending = songs.filterNot { it.id in done }
            var count = 0
            _progress.value = AnalysisProgress(0, pending.size, true)
            val jobs = pending.map { song ->
                async(dispatcher) {
                    if (isActive) runCatching { ensure(song) }
                    synchronized(this@AnalysisRepository) { count++ }
                    _progress.value = AnalysisProgress(count, pending.size, true)
                }
            }
            jobs.forEach { it.await() }
            _progress.value = AnalysisProgress(count, pending.size, false)
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _progress.value = _progress.value?.copy(running = false)
    }
}
