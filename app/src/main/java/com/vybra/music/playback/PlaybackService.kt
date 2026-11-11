package com.vybra.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vybra.music.R
import com.vybra.music.appContainer
import com.vybra.music.artwork.ArtworkBitmapLoader
import android.media.AudioManager
import com.vybra.music.playback.audio.VybraAudioProcessor
import com.vybra.music.playback.mix.MixEngine
import com.vybra.music.playback.audio.VybraRenderersFactory
import com.vybra.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Servicio de reproducción en segundo plano. Gestiona ExoPlayer, la MediaSession
 * (notificación, pantalla de bloqueo, auriculares), el audio focus, el historial y
 * la restauración de la última sesión.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    /** Reproductor activo (el que controla la MediaSession). */
    private lateinit var player: ExoPlayer
    private lateinit var players: Array<ExoPlayer>
    private lateinit var processors: Array<VybraAudioProcessor>
    private lateinit var mixEngine: MixEngine
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { appContainer }
    private var tickerJob: Job? = null
    private var restored = false

    // Seguimiento del tiempo escuchado para historial/estadísticas.
    private var trackedMediaId: String? = null
    private var trackedEventId: Long = -1
    private var trackedListenedMs: Long = 0
    private var segmentStart: Long = -1

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Dos reproductores: el activo (el de la MediaSession) y uno en espera que solo
        // suena durante las transiciones de Playlist Mix. Comparten la sesión de audio
        // para que el ecualizador se aplique a ambos.
        val audioSessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        processors = Array(2) { VybraAudioProcessor(container.spectrum) }
        players = Array(2) { i ->
            ExoPlayer.Builder(this, VybraRenderersFactory(this, processors[i]))
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ i == 0)
                .setHandleAudioBecomingNoisy(i == 0)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSkipSilenceEnabled(false)
                .build()
                .apply { setAudioSessionId(audioSessionId) }
        }
        processors[1].targetGain = 0f
        processors[1].feedAnalyzer = false
        player = players[0]
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this)))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        container.audioEffects.attach(audioSessionId)
        container.sleepTimer.player = player
        mixEngine = MixEngine(container, scope, players, processors, audioAttributes, ::swapActivePlayer)
        container.mixEngine = mixEngine
        restoreLastSession()
    }

    /** Llamado por el motor de mezcla al terminar una transición. */
    private fun swapActivePlayer(newActive: Int) {
        val old = player
        // Cierra el tiempo escuchado de la canción saliente.
        endSegment()
        flushListened()
        old.removeListener(playerListener)
        player = players[newActive]
        player.addListener(playerListener)
        session.player = player
        container.sleepTimer.player = player
        trackedMediaId = player.currentMediaItem?.mediaId
        trackedEventId = -1
        trackedListenedMs = 0
        if (player.isPlaying) {
            beginSegment()
            startTicker()
        }
        saveQueueAndPosition(queue = false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueueAndPosition()
        // Si la música está sonando sigue en segundo plano; si no, el servicio se cierra.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        flushListened()
        saveQueueAndPositionBlocking()
        tickerJob?.cancel()
        mixEngine.release()
        container.mixEngine = null
        container.sleepTimer.player = null
        container.audioEffects.release()
        session.release()
        player.removeListener(playerListener)
        players.forEach { it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Callback de la sesión ----------

    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.resolvePlayable() }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = container.settings.savedPlayback()
            val songs = container.music.songsByIds(saved.queue)
            val items = songs.map { it.toMediaItem() }
            val savedId = saved.queue.getOrNull(saved.index)
            val index = songs.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
            MediaSession.MediaItemsWithStartPosition(items, index, saved.positionMs)
        }
    }

    // ---------- Listener del reproductor ----------

    private val playerListener = object : Player.Listener {
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) mix { onSeek() }
            }
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

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                mix { onQueueChanged() }
                if (restored) saveQueueAndPosition()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            mix { onModesChanged() }
            scope.launch { container.settings.saveModes(shuffleModeEnabled, player.repeatMode) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            mix { onModesChanged() }
            scope.launch { container.settings.saveModes(player.shuffleModeEnabled, repeatMode) }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            container.audioEffects.attach(audioSessionId)
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

    private fun beginSegment() {
        if (trackedMediaId == null) trackedMediaId = player.currentMediaItem?.mediaId
        if (segmentStart < 0) segmentStart = SystemClock.elapsedRealtime()
    }

    private fun endSegment() {
        if (segmentStart >= 0) {
            trackedListenedMs += SystemClock.elapsedRealtime() - segmentStart
            segmentStart = -1
        }
    }

    /** Registra la reproducción en el historial cuando se escucharon al menos 10 segundos. */
    private fun flushListened() {
        val songId = trackedMediaId?.toLongOrNull() ?: return
        val running = if (segmentStart >= 0) SystemClock.elapsedRealtime() - segmentStart else 0
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
                // La posición también se guarda al pausar, cambiar de canción o cerrar;
                // aquí solo como respaldo cada ~30 s para no escribir en disco constantemente.
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

    private fun queueIds(): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

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
