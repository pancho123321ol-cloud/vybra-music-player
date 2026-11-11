package com.vybra.music.playback

import com.vybra.music.data.prefs.EqualizerSettings
import com.vybra.music.data.prefs.SettingsRepository
import com.vybra.music.playback.engine.SoftwareEqualizer
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

/** Ecualizador y refuerzo de graves por software, aplicados a toda la salida de audio. */
class AudioEffectsManager(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val equalizer = SoftwareEqualizer()

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    /** Aplica los ajustes guardados (se llama al arrancar el motor de audio). */
    fun attach() {
        scope.launch {
            val saved = settings.equalizer.first()
            applySaved(saved)
        }
    }

    private fun applySaved(saved: EqualizerSettings) {
        val presets = SoftwareEqualizer.PRESETS
        if (saved.preset in presets.indices) {
            presets[saved.preset].second.forEachIndexed { i, v -> equalizer.setLevel(i, v) }
        } else if (saved.bandLevels.size == equalizer.centers.size) {
            saved.bandLevels.forEachIndexed { i, level -> equalizer.setLevel(i, level) }
        }
        equalizer.setBassStrength(saved.bassStrength)
        equalizer.enabled = saved.enabled
        publish(currentPreset = if (saved.preset in presets.indices) saved.preset else -1)
    }

    private fun publish(currentPreset: Int = _state.value.currentPreset) {
        _state.value = EqualizerState(
            attached = true,
            supported = true,
            enabled = equalizer.enabled,
            bands = equalizer.centers.mapIndexed { i, hz -> EqBand(i, hz, equalizer.level(i)) },
            minLevel = equalizer.minLevel,
            maxLevel = equalizer.maxLevel,
            presets = SoftwareEqualizer.PRESETS.map { it.first },
            currentPreset = currentPreset,
            bassSupported = true,
            bassStrength = equalizer.bassStrength(),
        )
    }

    fun setEnabled(enabled: Boolean) {
        equalizer.enabled = enabled
        publish()
        persist()
    }

    fun setBandLevel(band: Int, level: Int) {
        equalizer.setLevel(band, level)
        publish(currentPreset = -1)
        persist()
    }

    fun usePreset(index: Int) {
        SoftwareEqualizer.PRESETS.getOrNull(index)?.second?.forEachIndexed { i, v -> equalizer.setLevel(i, v) }
        publish(currentPreset = index)
        persist()
    }

    fun setBassStrength(strength: Int) {
        equalizer.setBassStrength(strength)
        publish()
        persist()
    }

    fun reset() {
        equalizer.centers.indices.forEach { equalizer.setLevel(it, 0) }
        equalizer.setBassStrength(0)
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
        _state.value = _state.value.copy(attached = false)
    }
}
