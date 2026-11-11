package com.vybra.music.playback.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Fábrica de renderers que inserta [VybraAudioProcessor] en la cadena de audio de ExoPlayer. */
@UnstableApi
class VybraRenderersFactory(
    context: Context,
    private val processor: VybraAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Salida PCM 16 bits: el procesador trabaja siempre sobre enteros de 16 bits.
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(processor))
        .build()
}
