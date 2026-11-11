package com.vybra.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vybra.music.data.lyrics.LrcLibClient
import com.vybra.music.data.lyrics.OnlineLyrics
import com.vybra.music.ui.common.formatDuration
import java.io.IOException
import kotlin.math.abs

private sealed interface SearchState {
    data object Loading : SearchState
    data object Offline : SearchState
    data class Done(val results: List<OnlineLyrics>) : SearchState
}

/** Hoja para buscar letras en LRCLIB y elegir una. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineLyricsSheet(
    initialQuery: String,
    durationMs: Long,
    onDismiss: () -> Unit,
    onPick: (OnlineLyrics) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var submitted by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Loading) }
    LaunchedEffect(submitted) {
        state = SearchState.Loading
        state = try {
            val seconds = durationMs / 1000.0
            // Primero las que tienen la misma duración y están sincronizadas.
            SearchState.Done(
                LrcLibClient.search(query).sortedWith(
                    compareBy<OnlineLyrics>(
                        { if (seconds > 0 && abs(it.durationSec - seconds) <= 3) 0 else 1 },
                        { if (it.isSynced) 0 else 1 },
                        { if (it.content == null) 1 else 0 },
                    ),
                ),
            )
        } catch (_: IOException) {
            SearchState.Offline
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Buscar letra en internet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Resultados de LRCLIB, una base de datos abierta de letras.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Título y artista") },
                trailingIcon = { IconButton(onClick = { submitted++ }) { Icon(Icons.Rounded.Search, "Buscar") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted++ }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp)) {
                when (val s = state) {
                    SearchState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    SearchState.Offline -> Message("Sin conexión a internet. Revisa tu conexión e inténtalo de nuevo.")
                    is SearchState.Done -> {
                        val usable = s.results.filter { it.content != null || it.instrumental }
                        if (usable.isEmpty()) Message("No se encontraron letras. Prueba con otras palabras.")
                        else LazyColumn {
                            items(usable, key = { it.id }) { item ->
                                ResultRow(item, durationMs) { if (item.content != null) onPick(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun ResultRow(item: OnlineLyrics, durationMs: Long, onClick: () -> Unit) {
    val seconds = durationMs / 1000.0
    val sameLength = seconds > 0 && abs(item.durationSec - seconds) <= 3
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.content != null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.track, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.artist, item.album).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    item.instrumental && item.content == null -> Badge("Instrumental", MaterialTheme.colorScheme.surfaceVariant)
                    item.isSynced -> Badge("Sincronizada", MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    else -> Badge("Solo texto", MaterialTheme.colorScheme.surfaceVariant)
                }
                if (item.durationSec > 0) {
                    Badge(
                        formatDuration((item.durationSec * 1000).toLong()) + if (sameLength) " ✓" else "",
                        if (sameLength) Color(0x3346C46E) else MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

