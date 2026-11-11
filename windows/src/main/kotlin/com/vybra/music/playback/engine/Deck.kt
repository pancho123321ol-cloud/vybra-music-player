package com.vybra.music.playback.engine

import com.vybra.music.Log
import com.vybra.music.media.AudioDecoder
import com.vybra.music.playback.audio.VybraAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Reproductor de una cola de canciones con una API parecida a la de ExoPlayer.
 * Las órdenes llegan desde el hilo principal; el audio lo pide [AudioOutput] desde su
 * propio hilo con [render]. Todo el estado compartido se protege con [lock] y los
 * eventos se publican en el hilo principal.
 */
class Deck(
    val name: String,
    private val scope: CoroutineScope,
    val processor: VybraAudioProcessor,
    private val output: () -> AudioOutput?,
) {
    private val lock = Any()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "deck-$name-loader").apply { isDaemon = true } }
    private val listeners = ArrayList<PlayerListener>()

    // ---------- Estado (protegido por lock) ----------
    private val items = ArrayList<MediaItem>()
    /** Orden aleatorio: posición → índice del item. */
    private var order = IntArray(0)
    private var index = -1
    private var state = Player.STATE_IDLE
    private var pwr = false
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var params = PlaybackParameters.DEFAULT
    private var lastIsPlaying = false

    private var decoder: AudioDecoder? = null
    private var decoderIndex = -1
    private var preloaded: AudioDecoder? = null
    private var preloadedIndex = -1
    private var preloading = false
    /** Se incrementa con cada carga para descartar resultados obsoletos. */
    private var generation = 0
    /** Posición a la que hay que ir cuando el decodificador esté listo. */
    private var pendingSeekMs = 0L

    private val stretcher = TimeStretcher(SAMPLE_RATE)
    private val varispeed = VarispeedResampler()
    private var scratch = FloatArray(4096 * 2)

    @Volatile var volume: Float = 1f
    private var appliedVolume = 1f
    @Volatile var pauseAtEndOfMediaItems: Boolean = false

    fun addListener(l: PlayerListener) { listeners += l }
    fun removeListener(l: PlayerListener) { listeners -= l }

    // ---------- Consultas ----------

    val isPlaying: Boolean get() = synchronized(lock) { isPlayingLocked() }
    private fun isPlayingLocked() = pwr && state == Player.STATE_READY

    var playWhenReady: Boolean
        get() = synchronized(lock) { pwr }
        set(value) = setPlayWhenReady(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

    val playbackState: Int get() = synchronized(lock) { state }
    val mediaItemCount: Int get() = synchronized(lock) { items.size }
    val currentMediaItemIndex: Int get() = synchronized(lock) { index }
    val currentMediaItem: MediaItem? get() = synchronized(lock) { items.getOrNull(index) }
    fun getMediaItemAt(i: Int): MediaItem = synchronized(lock) { items[i] }
    fun mediaItems(): List<MediaItem> = synchronized(lock) { items.toList() }

    val nextMediaItemIndex: Int get() = synchronized(lock) { nextIndexLocked(repeat) }
    val previousMediaItemIndex: Int get() = synchronized(lock) { previousIndexLocked() }

    /** Duración de la canción actual en ms o [C.TIME_UNSET]. */
    val duration: Long
        get() = synchronized(lock) {
            val d = decoder?.takeIf { decoderIndex == index }?.durationMs ?: 0L
            when {
                d > 0 -> d
                else -> items.getOrNull(index)?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET
            }
        }

    /** Posición que se está oyendo ahora (descontando el audio ya enviado a la tarjeta). */
    val currentPosition: Long
        get() = synchronized(lock) {
            val dec = decoder
            if (dec == null || decoderIndex != index) return@synchronized pendingSeekMs
            val pending = when {
                params.pitch != 1f -> varispeed.pendingInputFrames
                params.speed != 1f -> stretcher.pendingInputFrames
                else -> 0.0
            }
            val src = dec.positionFrames - pending
            val latency = if (isPlayingLocked()) (output()?.latencyMs ?: 0L) * params.speed else 0f
            (src * 1000 / SAMPLE_RATE - latency).toLong().coerceAtLeast(0)
        }

    val bufferedPosition: Long get() = currentPosition

    var shuffleModeEnabled: Boolean
        get() = synchronized(lock) { shuffle }
        set(value) {
            synchronized(lock) {
                if (shuffle == value) return
                shuffle = value
                if (value) order = shuffledOrder(items.size, index)
                dropPreloadLocked()
            }
            emit { it.onShuffleModeEnabledChanged(value) }
        }

    var repeatMode: Int
        get() = synchronized(lock) { repeat }
        set(value) {
            synchronized(lock) {
                if (repeat == value) return
                repeat = value
                dropPreloadLocked()
            }
            emit { it.onRepeatModeChanged(value) }
        }

    var playbackParameters: PlaybackParameters
        get() = synchronized(lock) { params }
        set(value) {
            synchronized(lock) {
                val old = params
                params = value
                val oldMode = mode(old)
                val newMode = mode(value)
                if (oldMode != newMode) {
                    stretcher.reset(); varispeed.reset()
                }
                stretcher.tempo = value.speed
                varispeed.rate = value.speed.toDouble()
            }
        }

    private fun mode(p: PlaybackParameters) = when {
        p.pitch != 1f -> 2
        p.speed != 1f -> 1
        else -> 0
    }

    /** Orden de reproducción actual (respetando el modo aleatorio). */
    fun playbackOrder(): IntArray = synchronized(lock) {
        if (shuffle) order.copyOf() else IntArray(items.size) { it }
    }

    fun setShuffleOrder(newOrder: IntArray) = synchronized(lock) {
        if (newOrder.size == items.size) order = newOrder.copyOf()
        dropPreloadLocked()
    }

    // ---------- Cola ----------

    fun setMediaItems(newItems: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        val oldIndex: Int
        synchronized(lock) {
            oldIndex = index
            closeDecodersLocked()
            items.clear(); items.addAll(newItems)
            index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
            order = if (shuffle) shuffledOrder(items.size, index) else IntArray(items.size) { it }
            pendingSeekMs = startPositionMs.coerceAtLeast(0)
            if (items.isEmpty()) state = Player.STATE_IDLE
            else if (state != Player.STATE_IDLE) {
                state = Player.STATE_BUFFERING
                loadLocked(index, pendingSeekMs)
            }
        }
        emit { it.onTimelineChanged() }
        emit { it.onMediaItemTransition(currentMediaItem, Player.TRANSITION_REASON_PLAYLIST_CHANGED) }
        if (oldIndex != index) emit { it.onPositionDiscontinuity(oldIndex, currentMediaItemIndex, Player.DISCONTINUITY_REASON_SEEK) }
        stateMaybeChanged()
    }

    fun setMediaItems(newItems: List<MediaItem>) = setMediaItems(newItems, 0, 0L)

    fun addMediaItems(newItems: List<MediaItem>) = addMediaItems(mediaItemCount, newItems)

    fun addMediaItems(at: Int, newItems: List<MediaItem>) {
        if (newItems.isEmpty()) return
        synchronized(lock) {
            val pos = at.coerceIn(0, items.size)
            items.addAll(pos, newItems)
            if (index >= pos && index >= 0) index += newItems.size
            if (decoderIndex >= pos) decoderIndex += newItems.size
            // Los nuevos se insertan en posiciones aleatorias tras la canción actual.
            val shifted = order.map { if (it >= pos) it + newItems.size else it }.toMutableList()
            val curPos = shifted.indexOf(index).coerceAtLeast(-1)
            for (k in newItems.indices) {
                val v = pos + k
                if (shuffle) shifted.add(Random.nextInt(curPos + 1, shifted.size + 1), v)
                else shifted.add(v)
            }
            order = if (shuffle) shifted.toIntArray() else IntArray(items.size) { it }
            if (index < 0) index = 0
            dropPreloadLocked()
        }
        emit { it.onTimelineChanged() }
    }

    fun removeMediaItem(i: Int) = removeMediaItems(i, i + 1)

    fun removeMediaItems(from: Int, to: Int) {
        var transitioned = false
        synchronized(lock) {
            val a = from.coerceIn(0, items.size)
            val b = to.coerceIn(a, items.size)
            if (a == b) return
            val count = b - a
            val removingCurrent = index in a until b
            repeat(count) { items.removeAt(a) }
            order = order.filter { it !in a until b }.map { if (it >= b) it - count else it }.toIntArray()
            if (!shuffle) order = IntArray(items.size) { it }
            dropPreloadLocked()
            when {
                items.isEmpty() -> {
                    closeDecodersLocked()
                    index = -1
                    state = Player.STATE_ENDED
                    transitioned = true
                }
                removingCurrent -> {
                    closeDecodersLocked()
                    index = a.coerceAtMost(items.lastIndex)
                    pendingSeekMs = 0
                    if (state != Player.STATE_IDLE) {
                        state = Player.STATE_BUFFERING
                        loadLocked(index, 0)
                    }
                    transitioned = true
                }
                index >= b -> {
                    index -= count
                    if (decoderIndex >= b) decoderIndex -= count
                }
            }
        }
        emit { it.onTimelineChanged() }
        if (transitioned) emit { it.onMediaItemTransition(currentMediaItem, Player.TRANSITION_REASON_PLAYLIST_CHANGED) }
        stateMaybeChanged()
    }

    fun moveMediaItem(from: Int, to: Int) {
        synchronized(lock) {
            if (from !in items.indices || to !in items.indices || from == to) return
            val item = items.removeAt(from)
            items.add(to, item)
            fun remap(i: Int): Int = when {
                i == from -> to
                from < to && i in (from + 1)..to -> i - 1
                from > to && i in to until from -> i + 1
                else -> i
            }
            index = remap(index)
            if (decoderIndex >= 0) decoderIndex = remap(decoderIndex)
            order = if (shuffle) order.map { remap(it) }.toIntArray() else IntArray(items.size) { it }
            dropPreloadLocked()
        }
        emit { it.onTimelineChanged() }
    }

    fun clearMediaItems() = setMediaItems(emptyList())

    // ---------- Transporte ----------

    fun prepare() {
        synchronized(lock) {
            if (items.isEmpty()) return
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                if (state == Player.STATE_ENDED) pendingSeekMs = 0
                state = Player.STATE_BUFFERING
                if (decoder == null || decoderIndex != index) loadLocked(index, pendingSeekMs)
                else state = Player.STATE_READY
            }
        }
        stateMaybeChanged()
    }

    fun play() = setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    fun pause() = setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

    private fun setPlayWhenReady(value: Boolean, reason: Int) {
        synchronized(lock) {
            if (pwr == value) return
            pwr = value
        }
        emit { it.onPlayWhenReadyChanged(value, reason) }
        stateMaybeChanged()
    }

    fun stop() {
        synchronized(lock) {
            closeDecodersLocked()
            state = Player.STATE_IDLE
            pwr = false
        }
        stateMaybeChanged()
    }

    fun release() {
        stop()
        loader.shutdownNow()
    }

    fun seekTo(positionMs: Long) = seekTo(currentMediaItemIndex, positionMs)

    fun seekTo(targetIndex: Int, positionMs: Long) {
        val oldIndex: Int
        synchronized(lock) {
            if (targetIndex !in items.indices) return
            oldIndex = index
            val ms = positionMs.coerceAtLeast(0)
            if (targetIndex == index && decoder != null && decoderIndex == index) {
                decoder?.seek(ms)
                stretcher.reset(); varispeed.reset()
                processor.flush()
                if (state == Player.STATE_ENDED) state = Player.STATE_READY
            } else {
                if (targetIndex != index) closeDecodersLocked()
                index = targetIndex
                pendingSeekMs = ms
                if (state != Player.STATE_IDLE) {
                    state = Player.STATE_BUFFERING
                    loadLocked(index, ms)
                }
            }
        }
        if (oldIndex != targetIndex) emit { it.onMediaItemTransition(currentMediaItem, Player.TRANSITION_REASON_SEEK) }
        emit { it.onPositionDiscontinuity(oldIndex, targetIndex, Player.DISCONTINUITY_REASON_SEEK) }
        stateMaybeChanged()
    }

    fun seekToDefaultPosition() = seekTo(currentMediaItemIndex, 0)
    fun seekToDefaultPosition(i: Int) = seekTo(i, 0)

    fun seekToNext() {
        val next = synchronized(lock) { nextIndexLocked(if (repeat == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeat) }
        if (next != C.INDEX_UNSET) seekTo(next, 0)
    }

    fun seekToPrevious() {
        val prev = synchronized(lock) { previousIndexLocked() }
        if (currentPosition > 3_000 || prev == C.INDEX_UNSET) seekTo(0) else seekTo(prev, 0)
    }

    // ---------- Orden ----------

    private fun nextIndexLocked(repeatMode: Int): Int {
        if (index < 0 || items.isEmpty()) return C.INDEX_UNSET
        if (repeatMode == Player.REPEAT_MODE_ONE) return index
        val seq = if (shuffle) order else IntArray(items.size) { it }
        val p = seq.indexOf(index)
        return when {
            p < 0 -> C.INDEX_UNSET
            p + 1 < seq.size -> seq[p + 1]
            repeatMode == Player.REPEAT_MODE_ALL -> seq[0]
            else -> C.INDEX_UNSET
        }
    }

    private fun previousIndexLocked(): Int {
        if (index < 0 || items.isEmpty()) return C.INDEX_UNSET
        val seq = if (shuffle) order else IntArray(items.size) { it }
        val p = seq.indexOf(index)
        return when {
            p > 0 -> seq[p - 1]
            repeat == Player.REPEAT_MODE_ALL && seq.isNotEmpty() -> seq.last()
            else -> C.INDEX_UNSET
        }
    }

    private fun shuffledOrder(size: Int, first: Int): IntArray {
        val list = (0 until size).toMutableList()
        list.shuffle()
        if (first in 0 until size) { list.remove(first); list.add(0, first) }
        return list.toIntArray()
    }

    // ---------- Carga de decodificadores ----------

    private fun loadLocked(i: Int, positionMs: Long) {
        val gen = ++generation
        val item = items.getOrNull(i) ?: return
        loader.execute {
            val dec = AudioDecoder.open(item.path, SAMPLE_RATE, 2)
            if (dec != null && positionMs > 0) dec.seek(positionMs)
            var failed = false
            synchronized(lock) {
                if (gen != generation || index != i) { dec?.close(); return@execute }
                if (dec == null) {
                    failed = true
                } else {
                    decoder?.close()
                    decoder = dec
                    decoderIndex = i
                    stretcher.reset(); varispeed.reset()
                    processor.flush()
                    state = Player.STATE_READY
                }
            }
            if (failed) {
                Log.w(TAG, "No se puede reproducir ${item.path}; se salta")
                skipUnplayable(i)
            }
            stateMaybeChanged()
        }
    }

    /** Si una canción no se puede abrir, se pasa a la siguiente (o se detiene al final). */
    private fun skipUnplayable(failedIndex: Int) {
        scope.launch(Dispatchers.Main) {
            val next = synchronized(lock) {
                if (index != failedIndex) return@launch
                nextIndexLocked(if (repeat == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeat)
            }
            if (next == C.INDEX_UNSET || next == failedIndex) {
                synchronized(lock) { state = Player.STATE_ENDED }
                stateMaybeChanged()
            } else {
                seekTo(next, 0)
            }
        }
    }

    private fun preloadNextLocked() {
        if (preloading || preloaded != null) return
        val next = nextIndexLocked(repeat)
        if (next == C.INDEX_UNSET || next == index) return
        val item = items.getOrNull(next) ?: return
        preloading = true
        val gen = generation
        loader.execute {
            val dec = AudioDecoder.open(item.path, SAMPLE_RATE, 2)
            synchronized(lock) {
                preloading = false
                if (gen != generation || nextIndexLocked(repeat) != next || items.getOrNull(next) != item) { dec?.close(); return@execute }
                preloaded?.close()
                preloaded = dec
                preloadedIndex = next
            }
        }
    }

    private fun dropPreloadLocked() {
        preloaded?.close()
        preloaded = null
        preloadedIndex = -1
    }

    private fun closeDecodersLocked() {
        generation++
        decoder?.close()
        decoder = null
        decoderIndex = -1
        dropPreloadLocked()
        stretcher.reset(); varispeed.reset()
        processor.flush()
    }

    // ---------- Hilo de audio ----------

    /**
     * Suma [frames] fotogramas de este deck en [mix] (estéreo intercalado).
     * Devuelve true si aportó audio.
     */
    fun render(mix: FloatArray, frames: Int): Boolean {
        val events = ArrayList<(PlayerListener) -> Unit>()
        val produced: Int
        synchronized(lock) {
            if (!isPlayingLocked() || decoder == null) return false
            if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
            var done = 0
            var guard = 0
            while (done < frames && guard++ < 4) {
                val got = readLocked(scratch, done * 2, frames - done)
                done += got
                if (got == 0) {
                    if (!advanceLocked(events)) break
                }
            }
            produced = done
            // Pre-carga la siguiente para que el paso entre canciones no tenga silencios.
            val dec = decoder
            if (dec != null && dec.durationMs > 0 && dec.durationMs - dec.positionMs < PRELOAD_MS) preloadNextLocked()
            if (produced > 0) {
                processor.process(scratch, produced)
                val target = volume.coerceIn(0f, 1f)
                var v = appliedVolume
                for (i in 0 until produced) {
                    v += (target - v) * 0.002f
                    mix[i * 2] += scratch[i * 2] * v
                    mix[i * 2 + 1] += scratch[i * 2 + 1] * v
                }
                appliedVolume = v
            }
        }
        if (events.isNotEmpty()) {
            events.forEach { e -> emit(e) }
            stateMaybeChanged()
        }
        return produced > 0
    }

    private val sourceFn: PcmSource = { out, off, n -> decoder?.read(out, off, n) ?: 0 }

    private fun readLocked(out: FloatArray, offset: Int, frames: Int): Int = when (mode(params)) {
        2 -> varispeed.read(out, offset, frames, sourceFn)
        1 -> stretcher.read(out, offset, frames, sourceFn)
        else -> decoder?.read(out, offset, frames) ?: 0
    }

    /** Fin de la canción actual: pasa a la siguiente. Devuelve false si la cola terminó. */
    private fun advanceLocked(events: MutableList<(PlayerListener) -> Unit>): Boolean {
        stretcher.reset(); varispeed.reset()
        if (repeat == Player.REPEAT_MODE_ONE) {
            decoder?.seek(0)
            processor.flush()
            val item = items.getOrNull(index)
            events += { it.onMediaItemTransition(item, Player.TRANSITION_REASON_REPEAT) }
            return true
        }
        val old = index
        val next = nextIndexLocked(repeat)
        if (next == C.INDEX_UNSET) {
            state = Player.STATE_ENDED
            events += { it.onPlaybackStateChanged(Player.STATE_ENDED) }
            return false
        }
        val dec = if (preloadedIndex == next && preloaded != null) preloaded.also { preloaded = null; preloadedIndex = -1 }
        else AudioDecoder.open(items[next].path, SAMPLE_RATE, 2)
        decoder?.close()
        decoder = dec
        decoderIndex = next
        index = next
        processor.flush()
        val item = items.getOrNull(next)
        events += { it.onMediaItemTransition(item, Player.TRANSITION_REASON_AUTO) }
        events += { it.onPositionDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION) }
        if (dec == null) {
            // Archivo ilegible: se intentará saltar desde el hilo principal.
            state = Player.STATE_BUFFERING
            skipUnplayable(next)
            return false
        }
        if (pauseAtEndOfMediaItems) {
            pwr = false
            events += { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
            return false
        }
        return true
    }

    // ---------- Eventos ----------

    private fun stateMaybeChanged() {
        val playing: Boolean
        val changed: Boolean
        synchronized(lock) {
            playing = isPlayingLocked()
            changed = playing != lastIsPlaying
            lastIsPlaying = playing
        }
        if (changed) emit { it.onIsPlayingChanged(playing) }
        emit { it.onPlaybackStateChanged(playbackState) }
        output()?.wake()
    }

    private fun emit(event: (PlayerListener) -> Unit) {
        scope.launch(Dispatchers.Main) {
            listeners.toList().forEach { l ->
                runCatching { event(l) }.onFailure { Log.e(TAG, "Error en un listener", it) }
                l.onAnyEvent()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val PRELOAD_MS = 10_000L
        private const val TAG = "Deck"
    }
}
