package com.vybra.music.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Running(val endsAtMillis: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/** Temporizador de apagado: pausa la música tras un tiempo o al terminar la canción actual. */
@androidx.annotation.OptIn(UnstableApi::class)
class SleepTimer(private val scope: CoroutineScope) {
    /** Reproductor del servicio, asignado por [PlaybackService] mientras está activo. */
    var player: ExoPlayer? = null
        set(value) {
            field = value
            if (value == null) cancel()
        }

    private var job: Job? = null
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun start(minutes: Int) {
        cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = SleepTimerState.Running(endsAt)
        job = scope.launch {
            val fadeMs = 8_000L
            delay((endsAt - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            // Desvanecimiento suave antes de pausar.
            val p = player
            if (p != null && p.isPlaying) {
                val steps = 16
                for (i in steps downTo 1) {
                    p.volume = i / steps.toFloat()
                    delay(fadeMs / steps)
                }
                p.pause()
                p.volume = 1f
            } else {
                delay(fadeMs)
                player?.pause()
            }
            _state.value = SleepTimerState.Off
        }
    }

    fun startEndOfTrack() {
        cancel()
        val p = player ?: return
        p.pauseAtEndOfMediaItems = true
        _state.value = SleepTimerState.EndOfTrack
    }

    /** Llamado por el servicio cuando la reproducción se pausó al final de una canción. */
    fun onPausedAtEndOfItem() {
        if (_state.value == SleepTimerState.EndOfTrack) {
            player?.pauseAtEndOfMediaItems = false
            _state.value = SleepTimerState.Off
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        player?.let {
            it.pauseAtEndOfMediaItems = false
            it.volume = 1f
        }
        _state.value = SleepTimerState.Off
    }
}
