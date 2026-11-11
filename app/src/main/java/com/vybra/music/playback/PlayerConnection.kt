package com.vybra.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vybra.music.data.db.Song
import com.vybra.music.data.prefs.SettingsRepository
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
 * Conexión de la interfaz con [PlaybackService] mediante un MediaController.
 * La reproducción vive en el servicio, por lo que no se detiene al cambiar de pantalla
 * ni al cerrar la interfaz.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) action(c) else {
            pending += action
            controller = null
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
            )
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                _state.value = _state.value.copy(positionEpoch = _state.value.positionEpoch + 1)
            }
            refresh(rebuildQueue = queueChanged)
        }
    }

    private fun refresh(rebuildQueue: Boolean = true) {
        val c = controller ?: return
        // Reconstruir la cola solo cuando cambia evita trabajo O(n) en cada evento.
        val queue = if (rebuildQueue || _state.value.queue.isEmpty()) buildQueue(c) else _state.value.queue
        _state.value = _state.value.copy(
            connected = true,
            nowPlaying = c.currentMediaItem?.toNowPlaying(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
    }

    /** Construye la cola en el orden real de reproducción (respetando el modo aleatorio). */
    private fun buildQueue(c: MediaController): List<QueueEntry> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val result = ArrayList<QueueEntry>(c.mediaItemCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        var guard = 0
        while (index != C.INDEX_UNSET && guard < c.mediaItemCount) {
            c.getMediaItemAt(index).toNowPlaying()?.let { result += QueueEntry(index, it) }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            guard++
        }
        return result
    }

    val currentPosition: Long get() = controller?.currentPosition ?: 0L
    val bufferedPosition: Long get() = controller?.bufferedPosition ?: 0L

    fun setSourceName(name: String) {
        _state.value = _state.value.copy(sourceName = name)
    }

    // ---------- Acciones ----------

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null, source: String = "") {
        if (songs.isEmpty()) return
        setSourceName(source)
        scope.launch { settings.saveSource(source) }
        val items = songs.map { it.toMediaItem() }
        withController { c ->
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            val start = if (shuffle == true) songs.indices.random() else startIndex.coerceIn(0, items.lastIndex)
            c.setMediaItems(items, start, 0L)
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
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
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

    fun currentQueueItems(): List<MediaItem> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
    }
}
