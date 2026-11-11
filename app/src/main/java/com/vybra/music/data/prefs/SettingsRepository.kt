package com.vybra.music.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vybra_settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class LibraryStyle { LIST, GRID }
enum class SortOption { TITLE, ARTIST, ALBUM, DATE_ADDED }

data class UserSettings(
    val userName: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentColor: Int = DEFAULT_ACCENT,
    val dynamicColor: Boolean = false,
    val libraryStyle: LibraryStyle = LibraryStyle.LIST,
    val sortOption: SortOption = SortOption.TITLE,
    val sortAscending: Boolean = true,
    val excludedFolders: Set<String> = emptySet(),
    val minDurationSec: Int = 0,
    /** Oculta los audios recibidos y notas de voz de WhatsApp (activado por defecto). */
    val hideMessagingAudio: Boolean = true,
    /** Busca letras en LRCLIB cuando una canción no tiene (y para sincronizar automáticamente). */
    val onlineLyrics: Boolean = true,
    val recentSearches: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_ACCENT: Int = 0xFFFF4D6D.toInt()
    }
}

enum class VisualMode(val label: String) {
    AMBIENT("Ambient"), PULSE("Pulse"), SPECTRUM("Spectrum"), ARTWORK("Artwork"), MINIMAL("Minimal")
}

enum class VisualPalette(val label: String) {
    ARTWORK("Carátula"), ACCENT("Acento"), NEON("Neón"), MONO("Monocromo")
}

/** Ajustes de los fondos dinámicos del reproductor. */
data class VisualSettings(
    val enabled: Boolean = true,
    val mode: VisualMode = VisualMode.AMBIENT,
    /** 0..1 */
    val intensity: Float = 0.7f,
    /** 0..1 */
    val speed: Float = 0.5f,
    val palette: VisualPalette = VisualPalette.ARTWORK,
)

enum class MixStyle(val label: String, val description: String) {
    SMOOTH("Smooth", "Fundido de potencia constante, largo y suave"),
    BEAT_MATCH("Beat Match", "Iguala el BPM y alinea los pulsos de ambas canciones"),
    FADE("Fade", "Fundido lineal simple, sin cambios de tempo"),
    DJ_MIX("DJ Mix", "Beat match + intercambio de graves, como un DJ"),
    RISE("Rise", "La entrante sube con un filtro paso alto que se abre"),
    BLEND("Blend", "Mezcla larga a tempo con intercambio de graves"),
    ECHO_OUT("Echo Out", "La saliente se despide con un eco al ritmo mientras entra la nueva"),
    VINYL("Vinilo", "La saliente frena como un disco de vinilo y entra la nueva de golpe"),
}

/** Ajustes globales de Playlist Mix. */
data class MixSettings(
    val enabled: Boolean = false,
    val style: MixStyle = MixStyle.SMOOTH,
    val durationMs: Long = 8_000,
    /** Ajusta el tempo de la canción entrante (time-stretch) cuando el BPM es compatible. */
    val beatSync: Boolean = true,
    /** Iguala el volumen percibido entre canciones durante la transición. */
    val autoGain: Boolean = true,
)

data class SavedPlayback(
    val queue: List<Long>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val sourceName: String,
)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: Int = -1,
    val bandLevels: List<Int> = emptyList(),
    val bassStrength: Int = 0,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val userName = stringPreferencesKey("user_name")
        val themeMode = stringPreferencesKey("theme_mode")
        val accent = intPreferencesKey("accent_color")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val libraryStyle = stringPreferencesKey("library_style")
        val sortOption = stringPreferencesKey("sort_option")
        val sortAscending = booleanPreferencesKey("sort_ascending")
        val excluded = stringSetPreferencesKey("excluded_folders")
        val minDuration = intPreferencesKey("min_duration_sec")
        val hideMessaging = booleanPreferencesKey("hide_messaging_audio")
        val onlineLyrics = booleanPreferencesKey("online_lyrics")
        val recentSearches = stringPreferencesKey("recent_searches")

        val queue = stringPreferencesKey("pb_queue")
        val index = intPreferencesKey("pb_index")
        val position = longPreferencesKey("pb_position")
        val shuffle = booleanPreferencesKey("pb_shuffle")
        val repeat = intPreferencesKey("pb_repeat")
        val source = stringPreferencesKey("pb_source")

        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bass = intPreferencesKey("eq_bass")

        val lastScan = longPreferencesKey("last_scan")

        val visEnabled = booleanPreferencesKey("vis_enabled")
        val visMode = stringPreferencesKey("vis_mode")
        val visIntensity = floatPreferencesKey("vis_intensity")
        val visSpeed = floatPreferencesKey("vis_speed")
        val visPalette = stringPreferencesKey("vis_palette")

        val mixEnabled = booleanPreferencesKey("mix_enabled")
        val mixStyle = stringPreferencesKey("mix_style")
        val mixDuration = longPreferencesKey("mix_duration")
        val mixBeatSync = booleanPreferencesKey("mix_beat_sync")
        val mixAutoGain = booleanPreferencesKey("mix_auto_gain")
    }

    // ---------- Playlist Mix ----------

    val mix: Flow<MixSettings> = store.data.map { p ->
        MixSettings(
            enabled = p[Keys.mixEnabled] ?: false,
            style = p[Keys.mixStyle]?.let { runCatching { MixStyle.valueOf(it) }.getOrNull() } ?: MixStyle.SMOOTH,
            durationMs = p[Keys.mixDuration] ?: 8_000L,
            beatSync = p[Keys.mixBeatSync] ?: true,
            autoGain = p[Keys.mixAutoGain] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun saveMix(m: MixSettings) = store.edit {
        it[Keys.mixEnabled] = m.enabled
        it[Keys.mixStyle] = m.style.name
        it[Keys.mixDuration] = m.durationMs
        it[Keys.mixBeatSync] = m.beatSync
        it[Keys.mixAutoGain] = m.autoGain
    }

    // ---------- Fondos dinámicos ----------

    val visuals: Flow<VisualSettings> = store.data.map { p ->
        VisualSettings(
            enabled = p[Keys.visEnabled] ?: true,
            mode = p[Keys.visMode]?.let { runCatching { VisualMode.valueOf(it) }.getOrNull() } ?: VisualMode.AMBIENT,
            intensity = p[Keys.visIntensity] ?: 0.7f,
            speed = p[Keys.visSpeed] ?: 0.5f,
            palette = p[Keys.visPalette]?.let { runCatching { VisualPalette.valueOf(it) }.getOrNull() } ?: VisualPalette.ARTWORK,
        )
    }.distinctUntilChanged()

    suspend fun saveVisuals(v: VisualSettings) = store.edit {
        it[Keys.visEnabled] = v.enabled
        it[Keys.visMode] = v.mode.name
        it[Keys.visIntensity] = v.intensity
        it[Keys.visSpeed] = v.speed
        it[Keys.visPalette] = v.palette.name
    }

    private val store get() = context.dataStore

    val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            userName = p[Keys.userName] ?: "",
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            accentColor = p[Keys.accent] ?: UserSettings.DEFAULT_ACCENT,
            dynamicColor = p[Keys.dynamicColor] ?: false,
            libraryStyle = p[Keys.libraryStyle]?.let { runCatching { LibraryStyle.valueOf(it) }.getOrNull() } ?: LibraryStyle.LIST,
            sortOption = p[Keys.sortOption]?.let { runCatching { SortOption.valueOf(it) }.getOrNull() } ?: SortOption.TITLE,
            sortAscending = p[Keys.sortAscending] ?: true,
            excludedFolders = p[Keys.excluded] ?: emptySet(),
            minDurationSec = p[Keys.minDuration] ?: 0,
            hideMessagingAudio = p[Keys.hideMessaging] ?: true,
            onlineLyrics = p[Keys.onlineLyrics] ?: true,
            recentSearches = p[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }.distinctUntilChanged()

    suspend fun current(): UserSettings = settings.first()

    suspend fun setUserName(name: String) = store.edit { it[Keys.userName] = name.trim() }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.themeMode] = mode.name }
    suspend fun setAccent(color: Int) = store.edit { it[Keys.accent] = color }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.dynamicColor] = enabled }
    suspend fun setLibraryStyle(style: LibraryStyle) = store.edit { it[Keys.libraryStyle] = style.name }
    suspend fun setSort(option: SortOption, ascending: Boolean) = store.edit {
        it[Keys.sortOption] = option.name
        it[Keys.sortAscending] = ascending
    }
    suspend fun setMinDuration(seconds: Int) = store.edit { it[Keys.minDuration] = seconds }
    suspend fun setHideMessagingAudio(hide: Boolean) = store.edit { it[Keys.hideMessaging] = hide }
    suspend fun setOnlineLyrics(enabled: Boolean) = store.edit { it[Keys.onlineLyrics] = enabled }

    suspend fun setFolderExcluded(folder: String, excluded: Boolean) = store.edit {
        val current = it[Keys.excluded] ?: emptySet()
        it[Keys.excluded] = if (excluded) current + folder else current - folder
    }

    suspend fun addRecentSearch(query: String) = store.edit {
        val q = query.trim()
        if (q.isEmpty()) return@edit
        val current = it[Keys.recentSearches]?.split('\n')?.filter { s -> s.isNotBlank() } ?: emptyList()
        it[Keys.recentSearches] = (listOf(q) + current.filterNot { s -> s.equals(q, ignoreCase = true) })
            .take(10).joinToString("\n")
    }

    suspend fun removeRecentSearch(query: String) = store.edit {
        val current = it[Keys.recentSearches]?.split('\n')?.filter { s -> s.isNotBlank() } ?: emptyList()
        it[Keys.recentSearches] = current.filterNot { s -> s == query }.joinToString("\n")
    }

    suspend fun clearRecentSearches() = store.edit { it.remove(Keys.recentSearches) }

    // ---------- Estado de reproducción ----------

    suspend fun savedPlayback(): SavedPlayback {
        val p = store.data.first()
        return SavedPlayback(
            queue = p[Keys.queue]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            index = p[Keys.index] ?: 0,
            positionMs = p[Keys.position] ?: 0L,
            shuffle = p[Keys.shuffle] ?: false,
            repeatMode = p[Keys.repeat] ?: 0,
            sourceName = p[Keys.source] ?: "",
        )
    }

    suspend fun saveQueue(ids: List<String>, index: Int, positionMs: Long) = store.edit {
        it[Keys.queue] = ids.joinToString(",")
        it[Keys.index] = index
        it[Keys.position] = positionMs
    }

    suspend fun savePosition(index: Int, positionMs: Long) = store.edit {
        it[Keys.index] = index
        it[Keys.position] = positionMs
    }

    suspend fun saveModes(shuffle: Boolean, repeatMode: Int) = store.edit {
        it[Keys.shuffle] = shuffle
        it[Keys.repeat] = repeatMode
    }

    suspend fun saveSource(name: String) = store.edit { it[Keys.source] = name }

    // ---------- Ecualizador ----------

    val equalizer: Flow<EqualizerSettings> = store.data.map { p ->
        EqualizerSettings(
            enabled = p[Keys.eqEnabled] ?: false,
            preset = p[Keys.eqPreset] ?: -1,
            bandLevels = p[Keys.eqBands]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
            bassStrength = p[Keys.bass] ?: 0,
        )
    }.distinctUntilChanged()

    suspend fun saveEqualizer(settings: EqualizerSettings) = store.edit {
        it[Keys.eqEnabled] = settings.enabled
        it[Keys.eqPreset] = settings.preset
        it[Keys.eqBands] = settings.bandLevels.joinToString(",")
        it[Keys.bass] = settings.bassStrength
    }

    // ---------- Biblioteca ----------

    val lastScan: Flow<Long> = store.data.map { it[Keys.lastScan] ?: 0L }.distinctUntilChanged()
    suspend fun setLastScan(time: Long) = store.edit { it[Keys.lastScan] = time }
}
