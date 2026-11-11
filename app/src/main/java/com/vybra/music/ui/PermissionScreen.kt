package com.vybra.music.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.vybra.music.AppContainer

/** Pantalla de bienvenida que solicita el permiso de lectura de música. */
@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    var deniedOnce by remember { mutableStateOf(false) }
    val audioPermission = AppContainer.audioPermission()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[audioPermission] == true) onGranted() else deniedOnce = true
    }
    val permanentlyDenied = deniedOnce && (context as? Activity)?.let {
        !ActivityCompat.shouldShowRequestPermissionRationale(it, audioPermission)
    } == true
    val primary = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("Tu música,\nsiempre contigo", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "VYBRA reproduce la música guardada en tu teléfono. Sin cuentas, sin anuncios y sin conexión a Internet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Feature(Icons.Rounded.LibraryMusic, "Detecta automáticamente tus canciones, álbumes y artistas")
            Feature(Icons.Rounded.CloudOff, "Funciona 100 % sin conexión")
            Feature(Icons.Rounded.Lock, "Tus datos nunca salen del dispositivo")
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = {
                    if (permanentlyDenied) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        val permissions = buildList {
                            add(audioPermission)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = primary),
            ) {
                Text(if (permanentlyDenied) "Abrir ajustes de la app" else "Permitir acceso a mi música", style = MaterialTheme.typography.titleMedium)
            }
            if (deniedOnce) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (permanentlyDenied) "El permiso fue denegado. Actívalo en Ajustes > Permisos > Música y audio."
                    else "Necesitamos este permiso para encontrar tus canciones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
