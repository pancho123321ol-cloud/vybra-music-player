package com.vybra.music.ui.screens

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.BuildConfig
import com.vybra.music.data.prefs.LibraryStyle
import com.vybra.music.data.prefs.ThemeMode
import com.vybra.music.data.prefs.UserSettings
import com.vybra.music.data.scanner.FolderInfo
import com.vybra.music.data.scanner.MediaScanner
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.components.ConfirmDialog
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.LoadingBox
import com.vybra.music.ui.components.ScreenTitle
import com.vybra.music.ui.components.TextInputDialog
import com.vybra.music.ui.components.VerticalSeekBar
import com.vybra.music.ui.player.sleepTimerLabel
import com.vybra.music.ui.theme.AccentOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ===================== Ajustes =====================

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val songCount = c.music.songCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lastScan = c.settings.lastScan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val scan = c.scanner.state

    fun setName(name: String) = viewModelScope.launch { c.settings.setUserName(name) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settings.setThemeMode(mode) }
    fun setAccent(color: Int) = viewModelScope.launch {
        c.settings.setAccent(color)
        c.settings.setDynamicColor(false)
    }
    fun setDynamic(enabled: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(enabled) }
    fun setOnlineLyrics(enabled: Boolean) = viewModelScope.launch { c.settings.setOnlineLyrics(enabled) }
    fun setStyle(style: LibraryStyle) = viewModelScope.launch { c.settings.setLibraryStyle(style) }
    fun setMinDuration(sec: Int) = viewModelScope.launch {
        c.settings.setMinDuration(sec)
        c.scanner.scan()
    }
    fun rescan() = c.scanner.scanAsync()

    /** Importa varios LRC y los asocia automáticamente a las canciones de la biblioteca. */
    fun importLyrics(uris: List<android.net.Uri>, onDone: (com.vybra.music.data.lyrics.BulkImportResult) -> Unit) =
        viewModelScope.launch {
            val songs = c.music.songs.filterNotNull().first()
            onDone(c.lyrics.importMany(uris, songs))
        }

    fun clearHistory() = viewModelScope.launch { c.music.clearHistory() }
}

@Composable
fun SettingsScreen() {
    val vm = vybraViewModel { SettingsViewModel(it) }
    val ui = LocalUi.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val songCount by vm.songCount.collectAsStateWithLifecycle()
    val lastScan by vm.lastScan.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val timer = sleepTimerLabel()
    var editingName by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val s = settings ?: UserSettings()
    val lyricsImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importLyrics(uris) { r ->
            ui.message(
                when {
                    r.matched == 0 -> "Ningún archivo coincidió con tus canciones"
                    r.unmatched.isEmpty() -> "${r.matched} letras asociadas"
                    else -> "${r.matched} letras asociadas • ${r.unmatched.size} sin coincidencia"
                },
            )
        }
    }

    if (editingName) {
        TextInputDialog("¿Cómo te llamas?", s.userName, "Guardar", placeholder = "Tu nombre",
            onDismiss = { editingName = false }, onConfirm = { vm.setName(it); editingName = false })
    }
    if (confirmClear) {
        ConfirmDialog(
            "Borrar historial", "Se eliminará el historial de reproducción y las estadísticas.", "Borrar",
            onDismiss = { confirmClear = false },
            onConfirm = { vm.clearHistory(); confirmClear = false; ui.message("Historial borrado") },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        ScreenTitle("Ajustes")

        SettingsGroup("Perfil") {
            SettingsItem(Icons.Rounded.Person, "Tu nombre", s.userName.ifBlank { "Toca para personalizar el saludo" }) { editingName = true }
        }

        SettingsGroup("Apariencia") {
            SettingsLabel(Icons.Rounded.DarkMode, "Tema")
            SegmentedRow(
                listOf("Oscuro" to ThemeMode.DARK, "Claro" to ThemeMode.LIGHT, "Sistema" to ThemeMode.SYSTEM),
                selected = s.themeMode,
                onSelect = vm::setTheme,
            )
            SettingsLabel(Icons.Rounded.ColorLens, "Color de acento")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentOptions.forEach { (name, color) ->
                    val selected = !s.dynamicColor && s.accentColor == color
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { vm.setAccent(color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, name, tint = Color.White)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsSwitch(Icons.Rounded.Palette, "Colores del sistema", "Usa el color de tu fondo de pantalla (Material You)", s.dynamicColor, vm::setDynamic)
            }
            SettingsItem(
                Icons.Rounded.AutoAwesome, "Fondos dinámicos",
                "Ambient, Pulse, Spectrum, Artwork o Minimal • intensidad, velocidad y colores",
            ) { ui.navigate(Routes.VISUALS) }
            SettingsLabel(Icons.Rounded.GridView, "Estilo de la biblioteca")
            SegmentedRow(
                listOf("Lista" to LibraryStyle.LIST, "Cuadrícula" to LibraryStyle.GRID),
                selected = s.libraryStyle,
                onSelect = vm::setStyle,
            )
        }

        SettingsGroup("Biblioteca") {
            SettingsItem(
                Icons.Rounded.Refresh,
                if (scan.isScanning) "Escaneando…" else "Escanear música",
                buildString {
                    append(songCountLabel(songCount))
                    if (lastScan > 0) append(" • Último escaneo: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastScan))}")
                },
                enabled = !scan.isScanning,
            ) { vm.rescan() }
            if (scan.isScanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CircleShape))
            SettingsItem(Icons.Rounded.FolderOff, "Carpetas", "Elige qué carpetas se incluyen en la biblioteca") { ui.navigate(Routes.FOLDERS) }
            SettingsItem(
                Icons.Rounded.Lyrics, "Importar letras (LRC)",
                "Selecciona varios archivos .lrc; se asocian por nombre de archivo o artista y título",
            ) { lyricsImporter.launch(arrayOf("*/*")) }
            SettingsSwitch(
                Icons.Rounded.TravelExplore, "Buscar letras en internet",
                "Busca en LRCLIB las letras que faltan y usa sus tiempos al sincronizar. Solo envía título, artista y duración.",
                s.onlineLyrics, vm::setOnlineLyrics,
            )
            SettingsLabel(Icons.Rounded.Timer, "Ignorar audios más cortos de")
            SegmentedRow(
                listOf("No" to 0, "15 s" to 15, "30 s" to 30, "60 s" to 60),
                selected = s.minDurationSec,
                onSelect = vm::setMinDuration,
            )
        }

        SettingsGroup("Reproducción") {
            SettingsItem(Icons.Rounded.Equalizer, "Ecualizador", "Ajusta el sonido y refuerzo de graves") { ui.navigate(Routes.EQUALIZER) }
            SettingsItem(
                Icons.Rounded.GraphicEq, "Playlist Mix",
                "Transiciones con BPM y tonalidad: Smooth, Beat Match, Fade y DJ Mix",
            ) { ui.navigate(Routes.MIX_SETTINGS) }
            SettingsItem(Icons.Rounded.Bedtime, "Temporizador de apagado", timer?.let { "Activo: $it" } ?: "Desactivado") { ui.showSleepTimer = true }
        }

        SettingsGroup("Actividad") {
            SettingsItem(Icons.Rounded.History, "Historial de reproducción", "Lo que has escuchado") { ui.navigate(Routes.HISTORY) }
            SettingsItem(Icons.Rounded.BarChart, "Estadísticas", "Tu tiempo de escucha y lo más escuchado") { ui.navigate(Routes.STATS) }
            SettingsItem(Icons.Rounded.DeleteSweep, "Borrar historial", "Elimina el historial y las estadísticas") { confirmClear = true }
        }

        SettingsGroup("Acerca de") {
            SettingsItem(Icons.Rounded.Info, "VYBRA ${BuildConfig.VERSION_NAME}", "Reproductor de música local • Funciona sin conexión", showChevron = false) {}
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showChevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsLabel(icon: ImageVector, title: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun <T> SegmentedRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
    ) {
        options.forEach { (label, value) ->
            val isSel = value == selected
            val bg by animateColorAsState(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ===================== Ecualizador =====================

class EqualizerViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.audioEffects.state
    val effects = c.audioEffects
}

@Composable
fun EqualizerScreen() {
    val vm = vybraViewModel { EqualizerViewModel(it) }
    val ui = LocalUi.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val systemEqIntent = remember {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    val hasSystemEq = remember { systemEqIntent.resolveActivity(context.packageManager) != null }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Ecualizador", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.supported && state.attached) {
                Switch(checked = state.enabled, onCheckedChange = vm.effects::setEnabled, modifier = Modifier.padding(end = 12.dp))
            }
        }

        when {
            !state.attached -> EmptyState(
                Icons.Rounded.Tune, "Ecualizador no disponible todavía",
                "Reproduce una canción para activar el ecualizador en la sesión de audio.",
            )
            !state.supported -> EmptyState(
                Icons.Rounded.Tune, "Tu dispositivo no admite el ecualizador",
                "Este teléfono no expone efectos de audio a las aplicaciones.",
                actionLabel = if (hasSystemEq) "Abrir ecualizador del sistema" else null,
                onAction = if (hasSystemEq) ({ context.startActivity(systemEqIntent) }) else null,
            )
            else -> {
                if (state.presets.isNotEmpty()) {
                    Text("Preajustes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    FlowRow(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.presets.forEachIndexed { i, name ->
                            val sel = state.currentPreset == i
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(enabled = state.enabled) { vm.effects.usePreset(i) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.enabled) 1f else 0.5f),
                                )
                            }
                        }
                    }
                }

                // Bandas
                val local = remember { mutableStateMapOf<Int, Float>() }
                val range = (state.maxLevel - state.minLevel).coerceAtLeast(1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    state.bands.forEach { band ->
                        val value = local[band.index] ?: ((band.level - state.minLevel).toFloat() / range)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${"%+.0f".format((value * range + state.minLevel) / 100f)} dB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            VerticalSeekBar(
                                value = value,
                                onValueChange = { local[band.index] = it },
                                onValueChangeFinished = {
                                    local.remove(band.index)?.let { v ->
                                        vm.effects.setBandLevel(band.index, (v * range + state.minLevel).toInt())
                                    }
                                },
                                modifier = Modifier.height(220.dp).padding(vertical = 8.dp),
                                enabled = state.enabled,
                            )
                            Text(
                                if (band.centerHz >= 1000) "${band.centerHz / 1000}k" else "${band.centerHz}",
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                if (state.bassSupported) {
                    var bass by remember(state.bassStrength) { mutableFloatStateOf(state.bassStrength / 1000f) }
                    Text("Refuerzo de graves", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    Slider(
                        value = bass,
                        onValueChange = { bass = it },
                        onValueChangeFinished = { vm.effects.setBassStrength((bass * 1000).toInt()) },
                        enabled = state.enabled,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm.effects::reset, enabled = state.enabled, shape = CircleShape) { Text("Restablecer") }
                    if (hasSystemEq) {
                        OutlinedButton(onClick = { context.startActivity(systemEqIntent) }, shape = CircleShape) { Text("Ecualizador del sistema") }
                    }
                }
            }
        }
    }
}

// ===================== Carpetas =====================

class FoldersViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val folders = kotlinx.coroutines.flow.MutableStateFlow<List<FolderInfo>?>(null)
    private var rescanJob: Job? = null

    init {
        viewModelScope.launch { folders.value = c.scanner.listFolders() }
    }

    fun toggle(path: String, excluded: Boolean) = viewModelScope.launch {
        c.settings.setFolderExcluded(path, excluded)
        scheduleRescan()
    }

    fun setHideMessaging(hide: Boolean) = viewModelScope.launch {
        c.settings.setHideMessagingAudio(hide)
        scheduleRescan()
    }

    private fun scheduleRescan() {
        rescanJob?.cancel()
        rescanJob = viewModelScope.launch {
            delay(800)
            c.scanner.scan()
        }
    }
}

@Composable
fun FoldersScreen() {
    val vm = vybraViewModel { FoldersViewModel(it) }
    val ui = LocalUi.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val excluded = settings?.excludedFolders ?: emptySet()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Carpetas", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Desactiva las carpetas que no quieras incluir en tu biblioteca (notas de voz, tonos, podcasts…).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val hideMessaging = settings?.hideMessagingAudio ?: true
        SettingsSwitch(
            Icons.Rounded.FolderOff,
            "Ocultar audios de WhatsApp",
            "Excluye notas de voz y audios recibidos. Los archivos de \"WhatsApp Documents\" se mantienen.",
            hideMessaging,
            vm::setHideMessaging,
        )
        val list = folders
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState(Icons.Rounded.Folder, "Sin carpetas", "No se encontraron carpetas con música.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.path }) { folder ->
                    val hiddenByRule = hideMessaging && MediaScanner.isMessagingAudioFolder(folder.path)
                    val isExcluded = hiddenByRule || excluded.any { folder.path == it || folder.path.startsWith("$it/") }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !hiddenByRule) { vm.toggle(folder.path, !isExcluded) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isExcluded) Icons.Rounded.FolderOff else Icons.Rounded.Folder, null,
                            tint = if (isExcluded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.path.substringAfterLast('/').ifEmpty { "/" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (hiddenByRule) "Oculta por \"Ocultar audios de WhatsApp\" • ${songCountLabel(folder.songCount)}"
                                else "${songCountLabel(folder.songCount)} • ${folder.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(checked = !isExcluded, enabled = !hiddenByRule, onCheckedChange = { vm.toggle(folder.path, !it) })
                    }
                }
            }
        }
    }
}
