package com.vybra.music.playback

import com.vybra.music.AppContainer
import com.vybra.music.playback.engine.AudioOutput
import com.vybra.music.playback.engine.Deck
import com.vybra.music.playback.engine.MediaItem
import com.vybra.music.playback.engine.Player
import com.vybra.music.playback.engine.PlayerListener
import com.vybra.music.playback.audio.VybraAudioProcessor
import com.vybra.music.playback.mix.MixEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Motor de reproducción de la app de escritorio: dos decks (el activo y uno en espera
 * para las transiciones de Playlist Mix), la salida de audio, el historial y la
 * restauración de la última sesión. Vive mientras la app está abierta.
 */
class PlaybackService(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val processors: Array<VybraAudioProcessor> = Array(2) { VybraAudioProcessor(container.spectrum) }
    private var output: AudioOutput? = null
    val players: Array<Deck> = Array(2) { i -> Deck(if (i == 0) "A" else "B", scope, processors[i]) { output } }

    /** Deck activo (el que controla la interfaz). */
    var player: Deck = players[0]
        private set

    private lateinit var mixEngine: MixEngine
    private var tickerJob: Job? = null
    private var restored = false

    /** Avisa a [PlayerConnection] cuando cambia el deck activo. */
    var onActiveChanged: ((Deck) -> Unit)? = null

    // Seguimiento del tiempo escuchado para historial/estadísticas.
    private var trackedMediaId: String? = null
    private var trackedEventId: Long = -1
    private var trackedListenedMs: Long = 0
    private var segmentStart: Long = -1

    fun start() {
        processors[1].targetGain = 0f
        processors[1].feedAnalyzer = false
        output = AudioOutput({ players.toList() }, container.audioEffects.equalizer).also { it.start() }
        player.addListener(playerListener)
        container.audioEffects.attach()
        container.sleepTimer.player = player
        mixEngine = MixEngine(container, scope, players, processors, ::swapActivePlayer)
        container.mixEngine = mixEngine
        scope.launch {
            container.settings.volume.collect { output?.masterVolume = it }
        }
        restoreLastSession()
    }

    fun setVolume(v: Float) {
        output?.masterVolume = v
        scope.launch { container.settings.setVolume(v) }
    }

    /** Llamado por el motor de mezcla al terminar una transición. */
    private fun swapActivePlayer(newActive: Int) {
        val old = player
        endSegment()
        flushListened()
        old.removeListener(playerListener)
        player = players[newActive]
        player.addListener(playerListener)
        container.sleepTimer.player = player
        onActiveChanged?.invoke(player)
        trackedMediaId = player.currentMediaItem?.mediaId
        trackedEventId = -1
        trackedListenedMs = 0
        if (player.isPlaying) {
            beginSegment()
            startTicker()
        }
        saveQueueAndPosition(queue = false)
    }

    fun shutdown() {
        flushListened()
        saveQueueAndPositionBlocking()
        tickerJob?.cancel()
        if (::mixEngine.isInitialized) mixEngine.release()
        container.mixEngine = null
        container.sleepTimer.player = null
        container.audioEffects.release()
        player.removeListener(playerListener)
        players.forEach { it.release() }
        output?.release()
        scope.cancel()
    }

    // ---------- Listener del reproductor ----------

    private val playerListener = object : PlayerListener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                beginSegment()
                startTicker()
            } else {
                endSegment()
                flushListened()
                saveQueueAndPosition(queue = false)
            }
            mix { onIsPlayingChanged() }
        }

        override fun onPositionDiscontinuity(oldIndex: Int, newIndex: Int, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldIndex == newIndex) mix { onSeek() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mix { onMediaItemTransition() }
            endSegment()
            flushListened()
            trackedMediaId = mediaItem?.mediaId
            trackedEventId = -1
            trackedListenedMs = 0
            if (player.isPlaying) beginSegment()
            saveQueueAndPosition(queue = false)
        }

        override fun onTimelineChanged() {
            mix { onQueueChanged() }
            if (restored) saveQueueAndPosition()
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            mix { onModesChanged() }
            scope.launch { container.settings.saveModes(enabled, player.repeatMode) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            mix { onModesChanged() }
            scope.launch { container.settings.saveModes(player.shuffleModeEnabled, repeatMode) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            mix { onPlayWhenReadyChanged(playWhenReady) }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                container.sleepTimer.onPausedAtEndOfItem()
            }
        }
    }

    private fun mix(action: MixEngine.() -> Unit) {
        if (::mixEngine.isInitialized) mixEngine.action()
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun beginSegment() {
        if (trackedMediaId == null) trackedMediaId = player.currentMediaItem?.mediaId
        if (segmentStart < 0) segmentStart = now()
    }

    private fun endSegment() {
        if (segmentStart >= 0) {
            trackedListenedMs += now() - segmentStart
            segmentStart = -1
        }
    }

    /** Registra la reproducción en el historial cuando se escucharon al menos 10 segundos. */
    private fun flushListened() {
        val songId = trackedMediaId?.toLongOrNull() ?: return
        val running = if (segmentStart >= 0) now() - segmentStart else 0
        val listened = trackedListenedMs + running
        if (listened < MIN_LISTEN_MS) return
        val music = container.music
        if (trackedEventId < 0) {
            trackedEventId = 0 // marcado como "en creación" para evitar duplicados
            scope.launch {
                val id = music.recordPlay(songId)
                music.updateListened(id, listened)
                if (trackedMediaId == songId.toString() && trackedEventId == 0L) trackedEventId = id
            }
        } else if (trackedEventId > 0) {
            val id = trackedEventId
            scope.launch { music.updateListened(id, listened) }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            var ticks = 0
            while (isActive && player.isPlaying) {
                delay(TICK_MS)
                flushListened()
                if (++ticks % 3 == 0) saveQueueAndPosition(queue = false)
            }
        }
    }

    // ---------- Persistencia ----------

    private fun restoreLastSession() {
        scope.launch {
            try {
                val saved = container.settings.savedPlayback()
                container.player.setSourceName(saved.sourceName)
                if (player.mediaItemCount > 0 || saved.queue.isEmpty()) return@launch
                val songs = container.music.songsByIds(saved.queue)
                if (songs.isEmpty() || player.mediaItemCount > 0) return@launch
                val savedId = saved.queue.getOrNull(saved.index)
                val index = songs.indexOfFirst { it.id == savedId }
                val position = if (index >= 0) saved.positionMs else 0L
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeatMode
                player.setMediaItems(songs.map { it.toMediaItem() }, index.coerceAtLeast(0), position)
                player.prepare()
            } finally {
                restored = true
            }
        }
    }

    private fun queueIds(): List<String> = player.mediaItems().map { it.mediaId }

    private fun saveQueueAndPosition(queue: Boolean = true) {
        if (!restored) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val settings = container.settings
        if (queue) {
            val ids = queueIds()
            scope.launch { settings.saveQueue(ids, index, position) }
        } else {
            scope.launch { settings.savePosition(index, position) }
        }
    }

    private fun saveQueueAndPositionBlocking() {
        if (!restored) return
        val ids = queueIds()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        runCatching { runBlocking { container.settings.saveQueue(ids, index, position) } }
    }

    private companion object {
        const val TICK_MS = 10_000L
        const val MIN_LISTEN_MS = 10_000L
    }
}
