# VYBRA para Windows

Esta carpeta contiene VYBRA para Windows (Compose Multiplatform para escritorio, Kotlin/JVM). Es un proyecto Gradle independiente: no necesita el Android SDK.

- **Instalar:** ejecuta `VYBRA-1.0.0.msi` (en esta carpeta). Instala para tu usuario (sin permisos de administrador), con Java incluido y un acceso directo en el menú Inicio.
- **Compilar el instalador:** desde esta carpeta, `./gradlew packageReleaseMsi` → `build/compose/binaries/main-release/msi/`.
- **Ejecutar en desarrollo:** desde esta carpeta, `./gradlew run`.

Diferencias con Android:
- La biblioteca se lee de las carpetas que elijas (por defecto, tu carpeta *Música*) en lugar de MediaStore; las etiquetas y carátulas se leen de los archivos (y de `cover.jpg`/`folder.jpg`).
- El audio se decodifica con FFmpeg (MP3, FLAC, WAV, M4A/AAC, OGG, OPUS, WMA…) y lo reproduce un motor propio con dos decks para Playlist Mix (time-stretch, filtros, bucles, eco y frenado de vinilo).
- El ecualizador de 5 bandas y el refuerzo de graves funcionan por software. El volumen de la app es independiente del de Windows.
- Los datos (base de datos, ajustes, portadas y registro) se guardan en `%APPDATA%\VYBRA`.
- Atajos: Espacio (reproducir/pausar), Ctrl+←/→ (anterior/siguiente), Alt+←/→ (±10 s), Ctrl+S (aleatorio), Ctrl+R (repetir), Esc o botón lateral del ratón (atrás).
- Por ahora no hay controles en la pantalla de bloqueo ni en las teclas multimedia globales de Windows.
- El código se copió desde `../app/` (la versión Android) y se adaptó, así que los cambios en una versión hay que trasladarlos a la otra.
