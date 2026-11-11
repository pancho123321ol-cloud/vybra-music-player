package com.vybra.music.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.ui.theme.VybraTheme
import com.vybra.music.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        container.player.connect()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C)))
                return@setContent
            }
            val dark = isDarkTheme(s.themeMode)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            VybraTheme(themeMode = s.themeMode, accent = Color(s.accentColor), dynamicColor = s.dynamicColor) {
                var granted by remember { mutableStateOf(container.hasAudioPermission()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    granted = container.hasAudioPermission()
                    container.player.connect()
                }
                LaunchedEffect(granted) { if (granted) container.onPermissionGranted() }
                // Surface establece el color de contenido (texto/iconos) según el tema.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (granted) VybraRoot(container) else PermissionScreen(onGranted = { granted = true })
                }
            }
        }
    }
}
