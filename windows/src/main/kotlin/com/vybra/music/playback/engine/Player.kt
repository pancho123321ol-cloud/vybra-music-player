package com.vybra.music.playback.engine

/** Constantes compatibles con las de Media3, para conservar los valores guardados. */
object C {
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
    const val INDEX_UNSET: Int = -1
}

object Player {
    const val REPEAT_MODE_OFF = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2

    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    const val TRANSITION_REASON_REPEAT = 0
    const val TRANSITION_REASON_AUTO = 1
    const val TRANSITION_REASON_SEEK = 2
    const val TRANSITION_REASON_PLAYLIST_CHANGED = 3

    const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
    const val DISCONTINUITY_REASON_SEEK = 1

    const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
    const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5
}

/** Canción en la cola del reproductor. */
data class MediaItem(
    val mediaId: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val albumId: Long,
    val durationMs: Long,
)

/** Velocidad y tono. Con pitch == speed se remuestrea (efecto vinilo); con pitch 1 se hace time-stretch. */
data class PlaybackParameters(val speed: Float, val pitch: Float = 1f) {
    companion object {
        val DEFAULT = PlaybackParameters(1f, 1f)
    }
}

/** Eventos del reproductor. Se entregan siempre en el hilo principal. */
interface PlayerListener {
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onPlaybackStateChanged(state: Int) {}
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
    /** Salto de posición: [oldIndex]/[newIndex] son los índices antes y después. */
    fun onPositionDiscontinuity(oldIndex: Int, newIndex: Int, reason: Int) {}
    fun onTimelineChanged() {}
    fun onShuffleModeEnabledChanged(enabled: Boolean) {}
    fun onRepeatModeChanged(repeatMode: Int) {}
    /** Se llama después de cualquier otro evento (para refrescar la interfaz). */
    fun onAnyEvent() {}
}
