package com.vybra.music.playback

import com.vybra.music.data.db.Song
import com.vybra.music.data.prefs.SettingsRepository
import com.vybra.music.playback.engine.C
import com.vybra.music.playback.engine.Deck
import com.vybra.music.playback.engine.MediaItem
import com.vybra.music.playback.engine.Player
import com.vybra.music.playback.engine.PlayerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QueueEntry(
    /** Índice del item dentro de la lista del reproductor. */
    val index: Int,
    val nowPlaying: NowPlaying,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val sourceName: String = "",
    /** Se incrementa con cada salto de posición (búsqueda, cambio de canción). */
    val positionEpoch: Int = 0,
) {
    val hasMedia: Boolean get() = nowPlaying != null
}

/**
 * Conexión de la interfaz con [PlaybackService]: expone el estado del deck activo y
 * envía las órdenes de reproducción.
 */
class PlayerConnection(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private var service: PlaybackService? = null
    private var controller: Deck? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun attach(s: PlaybackService) {
        service = s
        s.onActiveChanged = { bind(it) }
        bind(s.player)
    }

    private fun bind(deck: Deck) {
        controller?.removeListener(listener)
        controller = deck
        deck.addListener(listener)
        refresh()
    }

    private val listener = object : PlayerListener {
        private var queueDirty = true
        override fun onTimelineChanged() { queueDirty = true }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { queueDirty = true }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { queueDirty = true }
        override fun onPositionDiscontinuity(oldIndex: Int, newIndex: Int, reason: Int) {
            _state.value = _state.value.copy(positionEpoch = _state.value.positionEpoch + 1)
        }
        override fun onAnyEvent() {
            refresh(rebuildQueue = queueDirty)
            queueDirty = false
        }
    }

    private fun withController(action: (Deck) -> Unit) {
        controller?.let(action)
    }

    private fun refresh(rebuildQueue: Boolean = true) {
        val c = controller ?: return
        val queue = if (rebuildQueue || _state.value.queue.isEmpty()) buildQueue(c) else _state.value.queue
        _state.value = _state.value.copy(
            connected = true,
            nowPlaying = c.currentMediaItem?.toNowPlaying(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: c.currentMediaItem?.durationMs ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
    }

    /** Construye la cola en el orden real de reproducción (respetando el modo aleatorio). */
    private fun buildQueue(c: Deck): List<QueueEntry> {
        val items = c.mediaItems()
        return c.playbackOrder().toList().mapNotNull { i -> items.getOrNull(i)?.toNowPlaying()?.let { QueueEntry(i, it) } }
    }

    val currentPosition: Long get() = controller?.currentPosition ?: 0L
    val bufferedPosition: Long get() = controller?.bufferedPosition ?: 0L

    fun setSourceName(name: String) {
        _state.value = _state.value.copy(sourceName = name)
    }

    fun setVolume(v: Float) { service?.setVolume(v) }

    // ---------- Acciones ----------

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null, source: String = "") {
        if (songs.isEmpty()) return
        setSourceName(source)
        scope.launch { settings.saveSource(source) }
        val items = songs.map { it.toMediaItem() }
        withController { c ->
            val start = if (shuffle == true) songs.indices.random() else startIndex.coerceIn(0, items.lastIndex)
            if (shuffle != null && c.shuffleModeEnabled != shuffle) {
                c.setMediaItems(items, start, 0L)
                c.shuffleModeEnabled = shuffle
            } else {
                c.setMediaItems(items, start, 0L)
            }
            c.prepare()
            c.play()
        }
    }

    /** Reproduce [songs] empezando en [index] y en la posición [positionMs]. */
    fun playFrom(songs: List<Song>, index: Int, positionMs: Long, source: String) {
        if (songs.isEmpty()) return
        setSourceName(source)
        scope.launch { settings.saveSource(source) }
        val items = songs.map { it.toMediaItem() }
        withController { c ->
            c.setMediaItems(items, index.coerceIn(0, items.lastIndex), positionMs.coerceAtLeast(0))
            c.prepare()
            c.play()
        }
    }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(songs.map { it.toMediaItem() })
                c.prepare()
                c.play()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
            }
        }
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        withController { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(songs.map { it.toMediaItem() })
            if (wasEmpty) {
                c.prepare()
                c.play()
            }
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) { c.seekToDefaultPosition(); c.prepare() }
            c.play()
        }
    }

    fun play() = withController { it.play() }
    fun pause() = withController { it.pause() }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun skipToIndex(index: Int) = withController {
        it.seekToDefaultPosition(index)
        it.prepare()
        it.play()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun removeFromQueue(index: Int) = withController {
        if (index in 0 until it.mediaItemCount) it.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) = withController {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to)
    }

    /** Elimina de la cola todo excepto la canción actual. */
    fun clearUpcoming() = withController { c ->
        val current = c.currentMediaItemIndex
        if (current < 0) return@withController
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    fun currentQueueItems(): List<MediaItem> = controller?.mediaItems() ?: emptyList()
}
