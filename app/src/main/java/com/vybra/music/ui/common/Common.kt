package com.vybra.music.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vybra.music.AppContainer
import com.vybra.music.appContainer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Crea un ViewModel con acceso al contenedor de dependencias de la app. */
@Composable
inline fun <reified VM : ViewModel> vybraViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Id de la canción en reproducción y si está sonando, disponibles para cualquier fila. */
val LocalNowPlayingId = compositionLocalOf { -1L }
val LocalIsPlaying = compositionLocalOf { false }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun formatLongDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

fun songCountLabel(count: Int): String = if (count == 1) "1 canción" else "$count canciones"
fun albumCountLabel(count: Int): String = if (count == 1) "1 álbum" else "$count álbumes"

fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(Locale.getDefault(), "%d KB", bytes / 1024)
}

fun greeting(name: String): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val base = when (hour) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
    return if (name.isBlank()) base else "$base, ${name.trim()}"
}

fun dayLabel(timeMillis: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = timeMillis
    val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when (day) {
        today -> "Hoy"
        yesterday -> "Ayer"
        else -> SimpleDateFormat("EEEE d 'de' MMMM", Locale.getDefault()).format(Date(timeMillis))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

fun timeLabel(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

fun mimeLabel(mime: String?, path: String): String {
    val ext = path.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return ext.ifEmpty { mime?.substringAfter('/')?.uppercase(Locale.ROOT) ?: "?" }
}
