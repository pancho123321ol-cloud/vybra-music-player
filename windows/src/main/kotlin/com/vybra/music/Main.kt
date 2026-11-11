package com.vybra.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.vybra.music.artwork.ArtworkFetcher
import com.vybra.music.artwork.ArtworkKeyer
import com.vybra.music.platform.mainFrame
import com.vybra.music.ui.VybraRoot
import com.vybra.music.ui.common.BackDispatcher
import com.vybra.music.ui.theme.VybraTheme
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private var instanceLock: FileLock? = null

/** Evita abrir dos VYBRA a la vez (dos motores de audio sonarían a la vez). */
private fun acquireSingleInstance(): Boolean = runCatching {
    val channel = RandomAccessFile(File(AppDirs.data, ".lock"), "rw").channel
    instanceLock = channel.tryLock()
    instanceLock != null
}.getOrDefault(true)

private fun appIcon(): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("vybra.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

fun main() {
    Log.init(AppDirs.data)
    Log.i("VYBRA", "Iniciando VYBRA ${System.getProperty("jpackage.app-version") ?: "dev"} (Java ${System.getProperty("java.version")})")
    if (!acquireSingleInstance()) {
        JOptionPane.showMessageDialog(null, "VYBRA ya está abierto.", "VYBRA", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.e("VYBRA", "Error no controlado en ${t.name}", e) }

    application {
        val container = remember { AppContainer.get() }
        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 820.dp),
            position = WindowPosition.PlatformDefault,
        )
        val icon = remember { appIcon() }
        Window(
            onCloseRequest = {
                container.shutdown()
                exitApplication()
            },
            state = windowState,
            title = "VYBRA",
            icon = icon,
            onKeyEvent = { e -> handleShortcut(container, e) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 600)
                mainFrame = window
                // Botón lateral "atrás" del ratón.
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ ev ->
                    val me = ev as? MouseEvent ?: return@addAWTEventListener
                    if (me.id == MouseEvent.MOUSE_RELEASED && me.button == 4) BackDispatcher.dispatch()
                }, java.awt.AWTEvent.MOUSE_EVENT_MASK)
                container.start()
            }
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components {
                        add(ArtworkKeyer())
                        add(ArtworkFetcher.Factory())
                    }
                    .memoryCache { MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build() }
                    .crossfade(220)
                    .build()
            }
            App(container)
        }
    }
}

/** Atajos de teclado (solo cuando ningún campo de texto ha consumido la tecla). */
private fun handleShortcut(container: AppContainer, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val player = container.player
    return when {
        e.key == Key.Escape -> BackDispatcher.dispatch()
        e.key == Key.Spacebar || e.key == Key.MediaPlayPause -> { player.togglePlayPause(); true }
        e.key == Key.MediaNext || (e.isCtrlPressed && e.key == Key.DirectionRight) -> { player.next(); true }
        e.key == Key.MediaPrevious || (e.isCtrlPressed && e.key == Key.DirectionLeft) -> { player.previous(); true }
        e.isAltPressed && e.key == Key.DirectionRight -> {
            player.seekTo(player.currentPosition + 10_000); true
        }
        e.isAltPressed && e.key == Key.DirectionLeft -> {
            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); true
        }
        e.isCtrlPressed && e.key == Key.S -> { player.toggleShuffle(); true }
        e.isCtrlPressed && e.key == Key.R -> { player.cycleRepeat(); true }
        else -> false
    }
}

@Composable
private fun App(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C)))
        return
    }
    VybraTheme(themeMode = s.themeMode, accent = Color(s.accentColor), dynamicColor = s.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            VybraRoot(container)
        }
    }
}
