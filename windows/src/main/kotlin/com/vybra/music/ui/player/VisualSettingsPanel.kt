package com.vybra.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.data.prefs.VisualMode
import com.vybra.music.data.prefs.VisualPalette
import com.vybra.music.data.prefs.VisualSettings
import com.vybra.music.ui.LocalUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val modeDescriptions = mapOf(
    VisualMode.AMBIENT to "Degradados animados que fluyen con la música",
    VisualMode.PULSE to "Brillos y anillos que laten con cada golpe de graves",
    VisualMode.SPECTRUM to "Espectro de frecuencias en tiempo real",
    VisualMode.ARTWORK to "Degradado estático con los colores de la carátula",
    VisualMode.MINIMAL to "Fondo oscuro y sencillo",
)

/** Controles de los fondos dinámicos; guarda los cambios en DataStore. */
@Composable
fun VisualSettingsPanel(modifier: Modifier = Modifier, onChange: (VisualSettings) -> Unit = {}) {
    val container = appContainer
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<VisualSettings?>(null) }
    LaunchedEffect(Unit) { s = container.settings.visuals.first() }
    val current = s ?: return
    fun update(v: VisualSettings, persist: Boolean = true) {
        s = v
        onChange(v)
        if (persist) scope.launch { container.settings.saveVisuals(v) }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Efectos visuales", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Desactívalos para ahorrar batería",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = current.enabled, onCheckedChange = { update(current.copy(enabled = it)) })
        }
        Label("Estilo")
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VisualMode.entries.forEach { mode ->
                Chip(mode.label, selected = current.mode == mode, enabled = current.enabled) { update(current.copy(mode = mode)) }
            }
        }
        Text(
            modeDescriptions.getValue(current.mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        val animatedMode = current.mode in setOf(VisualMode.AMBIENT, VisualMode.PULSE, VisualMode.SPECTRUM)
        Label("Intensidad")
        Slider(
            value = current.intensity,
            onValueChange = { update(current.copy(intensity = it), persist = false) },
            onValueChangeFinished = { update(current) },
            enabled = current.enabled && animatedMode,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Label("Velocidad")
        Slider(
            value = current.speed,
            onValueChange = { update(current.copy(speed = it), persist = false) },
            onValueChangeFinished = { update(current) },
            enabled = current.enabled && animatedMode,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Label("Colores")
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VisualPalette.entries.forEach { p ->
                Chip(p.label, selected = current.palette == p, enabled = current.enabled) { update(current.copy(palette = p)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.5f))
    }
}

/** Hoja rápida desde el reproductor. */
@Composable
fun VisualsSheet() {
    val ui = LocalUi.current
    if (!ui.showVisuals) return
    ModalBottomSheet(
        onDismissRequest = { ui.showVisuals = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            "Fondo del reproductor",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        VisualSettingsPanel(Modifier.navigationBarsPadding())
    }
}

/** Pantalla de ajustes con vista previa en vivo. */
@Composable
fun VisualsScreen() {
    val ui = LocalUi.current
    val container = appContainer
    val player by container.player.state.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<VisualSettings?>(null) }
    val np = player.nowPlaying

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Fondos dinámicos", style = MaterialTheme.typography.titleLarge)
        }
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PlayerBackground(np?.songId ?: -1, np?.albumId ?: -1, overrideSettings = preview)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (np == null) "Vista previa" else if (player.isPlaying) np.title else "Reproduce música para ver los efectos al ritmo",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        VisualSettingsPanel(onChange = { preview = it })
    }
}
