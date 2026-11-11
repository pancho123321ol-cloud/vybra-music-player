package com.vybra.music.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Equivalente de escritorio al botón "atrás" de Android: la tecla Esc y el botón lateral
 * del ratón llaman al último [BackHandler] activo; si no hay ninguno, se vuelve a la
 * pantalla anterior.
 */
object BackDispatcher {
    class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = ArrayList<Entry>()

    /** Acción por defecto (navegación hacia atrás). */
    var fallback: () -> Boolean = { false }

    fun register(e: Entry) { entries += e }
    fun unregister(e: Entry) { entries -= e }

    /** Devuelve true si algo consumió el evento. */
    fun dispatch(): Boolean {
        val e = entries.lastOrNull { it.enabled }
        if (e != null) {
            e.onBack()
            return true
        }
        return fallback()
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { BackDispatcher.Entry(enabled) { current.value() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        BackDispatcher.register(entry)
        onDispose { BackDispatcher.unregister(entry) }
    }
}
