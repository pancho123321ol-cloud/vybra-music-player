package com.vybra.music.ui.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vybra.music.appContainer
import com.vybra.music.playback.PlayerConnection
import com.vybra.music.playback.SleepTimerState
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.common.formatDuration
import com.vybra.music.ui.components.SeekBar
import com.vybra.music.ui.components.SheetAction
import kotlinx.coroutines.delay

/** Posición de reproducción actualizada periódicamente mientras suena la música. */
@Composable
fun rememberPlaybackPosition(
    connection: PlayerConnection,
    isPlaying: Boolean,
    mediaKey: Any?,
    intervalMs: Long = 250,
): State<Long> {
    val position = remember { mutableLongStateOf(connection.currentPosition) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Las búsquedas (también en pausa) reinician la lectura de la posición.
    val epoch = connection.state.collectAsStateWithLifecycle().value.positionEpoch
    LaunchedEffect(isPlaying, mediaKey, lifecycle, epoch) {
        position.longValue = connection.currentPosition
        // Solo se actualiza con la app visible: en segundo plano no consume batería.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            position.longValue = connection.currentPosition
            while (isPlaying) {
                delay(intervalMs)
                position.longValue = connection.currentPosition
            }
        }
    }
    return position
}

/** Barra de progreso con tiempos transcurrido y total. */
@Composable
fun PlaybackProgress(
    connection: PlayerConnection,
    isPlaying: Boolean,
    songId: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
) {
    val position by rememberPlaybackPosition(connection, isPlaying, songId)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var pendingSeek by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(songId) { pendingSeek = -1 }
    val shownPosition = when {
        dragging -> (dragValue * durationMs).toLong()
        pendingSeek >= 0 -> pendingSeek
        else -> position
    }
    LaunchedEffect(position) { if (pendingSeek >= 0 && kotlin.math.abs(position - pendingSeek) < 1500) pendingSeek = -1 }
    val fraction = if (durationMs > 0) shownPosition.toFloat() / durationMs else 0f

    Column(modifier) {
        SeekBar(
            value = fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                val target = (dragValue * durationMs).toLong()
                pendingSeek = target
                connection.seekTo(target)
                dragging = false
            },
            activeColor = activeColor,
            inactiveColor = activeColor.copy(alpha = 0.25f),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(shownPosition),
                style = MaterialTheme.typography.labelSmall,
                color = activeColor.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = activeColor.copy(alpha = 0.7f),
            )
        }
    }
}

/** Control de volumen del sistema (flujo de música), sincronizado con los botones físicos. */
@Composable
fun VolumeControl(modifier: Modifier = Modifier, color: Color = Color.White) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Bajar volumen", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        SeekBar(
            value = volume,
            onValueChange = {
                dragging = true
                volume = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt().coerceIn(0, max), 0)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            activeColor = color.copy(alpha = 0.85f),
            inactiveColor = color.copy(alpha = 0.2f),
            trackHeight = 3.dp,
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Subir volumen", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** Texto con el tiempo restante del temporizador, o null si está apagado. */
@Composable
fun sleepTimerLabel(): String? {
    val container = LocalContext.current.appContainer
    val state by container.sleepTimer.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is SleepTimerState.Running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return when (val s = state) {
        SleepTimerState.Off -> null
        SleepTimerState.EndOfTrack -> "Fin de la canción"
        is SleepTimerState.Running -> formatDuration((s.endsAtMillis - now).coerceAtLeast(0))
    }
}

@Composable
fun SleepTimerSheet() {
    val ui = LocalUi.current
    if (!ui.showSleepTimer) return
    val container = LocalContext.current.appContainer
    val label = sleepTimerLabel()
    val timer = container.sleepTimer
    fun dismiss() { ui.showSleepTimer = false }
    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Temporizador de apagado", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (label != null) "Activo: $label" else "La música se pausará automáticamente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { minutes ->
                SheetAction(Icons.Rounded.Timer, if (minutes < 60) "$minutes minutos" else "${minutes / 60} h ${if (minutes % 60 > 0) "${minutes % 60} min" else ""}".trim(), {
                    timer.start(minutes); ui.message("La música se pausará en $minutes min"); dismiss()
                })
            }
            SheetAction(Icons.Rounded.MusicOff, "Al terminar la canción actual", {
                if (timer.player == null) ui.message("No hay nada reproduciéndose")
                else { timer.startEndOfTrack(); ui.message("Se pausará al terminar la canción") }
                dismiss()
            })
            if (label != null) {
                SheetAction(Icons.Rounded.Close, "Desactivar temporizador", {
                    timer.cancel(); ui.message("Temporizador desactivado"); dismiss()
                }, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
