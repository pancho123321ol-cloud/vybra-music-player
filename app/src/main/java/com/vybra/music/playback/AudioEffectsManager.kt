package com.vybra.music.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import com.vybra.music.data.prefs.EqualizerSettings
import com.vybra.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EqBand(val index: Int, val centerHz: Int, val level: Int)

data class EqualizerState(
    val attached: Boolean = false,
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val presets: List<String> = emptyList(),
    val currentPreset: Int = -1,
    val bassSupported: Boolean = false,
    val bassStrength: Int = 0,
)

/** Ecualizador y refuerzo de graves del sistema, asociados a la sesión de audio del reproductor. */
class AudioEffectsManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var sessionId: Int = 0

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId && equalizer != null) return
        release()
        sessionId = audioSessionId
        if (audioSessionId == 0) return
        val eq = runCatching { Equalizer(0, audioSessionId) }
            .onFailure { Log.w(TAG, "Ecualizador no disponible", it) }
            .getOrNull()
        val bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        equalizer = eq
        bassBoost = bass

        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )

        if (eq == null) {
            _state.value = EqualizerState(attached = true, supported = false)
            return
        }
        scope.launch {
            val saved = settings.equalizer.first()
            applySaved(saved)
        }
    }

    private fun applySaved(saved: EqualizerSettings) {
        val eq = equalizer ?: return
        runCatching {
            if (saved.preset in 0 until eq.numberOfPresets) {
                eq.usePreset(saved.preset.toShort())
            } else if (saved.bandLevels.size == eq.numberOfBands.toInt()) {
                saved.bandLevels.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level.toShort()) }
            }
            eq.enabled = saved.enabled
            bassBoost?.let { b ->
                if (b.strengthSupported) b.setStrength(saved.bassStrength.toShort())
                b.enabled = saved.enabled && saved.bassStrength > 0
            }
        }.onFailure { Log.w(TAG, "No se pudo aplicar el ecualizador", it) }
        publish(currentPreset = if (saved.preset in 0 until eq.numberOfPresets) saved.preset else -1)
    }

    private fun publish(currentPreset: Int = _state.value.currentPreset) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val bands = (0 until eq.numberOfBands).map { i ->
                EqBand(i, eq.getCenterFreq(i.toShort()) / 1000, eq.getBandLevel(i.toShort()).toInt())
            }
            val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            _state.value = EqualizerState(
                attached = true,
                supported = true,
                enabled = eq.enabled,
                bands = bands,
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = presets,
                currentPreset = currentPreset,
                bassSupported = bassBoost?.strengthSupported == true,
                bassStrength = runCatching { bassBoost?.roundedStrength?.toInt() ?: 0 }.getOrDefault(0),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        runCatching {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled && _state.value.bassStrength > 0
        }
        publish()
        persist()
    }

    fun setBandLevel(band: Int, level: Int) {
        runCatching { equalizer?.setBandLevel(band.toShort(), level.toShort()) }
        publish(currentPreset = -1)
        persist()
    }

    fun usePreset(index: Int) {
        runCatching { equalizer?.usePreset(index.toShort()) }
        publish(currentPreset = index)
        persist()
    }

    fun setBassStrength(strength: Int) {
        runCatching {
            bassBoost?.setStrength(strength.toShort())
            bassBoost?.enabled = _state.value.enabled && strength > 0
        }
        publish()
        persist()
    }

    fun reset() {
        val eq = equalizer ?: return
        runCatching { (0 until eq.numberOfBands).forEach { eq.setBandLevel(it.toShort(), 0) } }
        runCatching { bassBoost?.setStrength(0) }
        publish(currentPreset = -1)
        persist()
    }

    private fun persist() {
        val s = _state.value
        scope.launch {
            settings.saveEqualizer(
                EqualizerSettings(
                    enabled = s.enabled,
                    preset = s.currentPreset,
                    bandLevels = s.bands.map { it.level },
                    bassStrength = s.bassStrength,
                ),
            )
        }
    }

    fun release() {
        if (sessionId != 0) {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                },
            )
        }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        sessionId = 0
        _state.value = _state.value.copy(attached = false)
    }

    private companion object {
        const val TAG = "AudioEffects"
    }
}
