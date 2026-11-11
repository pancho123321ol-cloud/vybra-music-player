# VYBRA — Reproductor de música local

VYBRA es un reproductor de música para **Android** y **Windows** que reproduce la música que ya tienes guardada, sin cuentas, sin anuncios y sin necesidad de internet. Tiene un diseño oscuro y moderno, letras sincronizadas estilo karaoke y **Playlist Mix**, un modo que enlaza las canciones como un DJ.

## Descargas

En la sección [**Releases**](../../releases/latest) están los instaladores:

| Plataforma | Archivo | Requisitos |
|---|---|---|
| Android | `VYBRA-1.0.0.apk` | Android 8.0 o superior |
| Windows | `VYBRA-1.0.0.msi` | Windows 10/11 de 64 bits (Java incluido) |

## Funciones

**Biblioteca**
- Canciones, álbumes, artistas y carpetas, con carátulas, ordenación y vista en lista o cuadrícula.
- Búsqueda instantánea con historial de búsquedas.
- Se actualiza sola cuando añades o borras música; puedes excluir carpetas (por ejemplo, los audios de WhatsApp).
- Formatos: MP3, FLAC, WAV, M4A/AAC, OGG, OPUS y más.

**Reproducción**
- Cola editable (arrastrar para reordenar), aleatorio, repetir y temporizador de apagado con fundido.
- Ecualizador de 5 bandas con preajustes y refuerzo de graves.
- Recuerda la cola y la posición al cerrar la app.
- Fondos animados que reaccionan a la música (Ambient, Pulse, Spectrum, Artwork y Minimal).

**Letras**
- Letras sincronizadas línea a línea y palabra a palabra (karaoke).
- Búsqueda automática en LRCLIB, importación de archivos LRC/TXT y un editor para sincronizarlas a mano.
- Sincronización automática: copia los tiempos de una versión en línea o analiza la voz de la canción.

**Playlist Mix**
- Analiza el BPM, la tonalidad y la energía de cada canción.
- Transiciones estilo DJ entre canciones: crossfade, beat match, intercambio de graves, filtros, eco, bucles y frenado de vinilo.
- Editor de transiciones con forma de onda para ajustar cada cambio entre dos canciones.

**Personalización y estadísticas**
- Tema oscuro, claro o del sistema, con varios colores de acento (o el color de Windows / Material You).
- Playlists con portada propia, favoritos, historial y estadísticas de escucha.

## Tecnología

- **Android** (`app/`): Kotlin, Jetpack Compose (Material 3), Media3/ExoPlayer, Room, DataStore, arquitectura MVVM.
- **Windows** (`windows/`): Kotlin, Compose Multiplatform para escritorio, FFmpeg para decodificar el audio y un motor de reproducción propio. Más detalles en [windows/README.md](windows/README.md).

# Versión Android

## Instalar en el teléfono

1. Activa **Opciones de desarrollador → Depuración USB** y conecta el teléfono.
2. Desde esta carpeta:
   ```bash
   ./gradlew installRelease
   ```
   o copia `app/build/outputs/apk/release/app-release.apk` al teléfono y ábrelo (permite "instalar apps desconocidas").
   El APK de release está firmado con la clave de depuración para poder instalarlo directamente; para publicarlo en una tienda habría que configurar una clave propia.

Requisitos: Android 8.0 (API 26) o superior. Compilar requiere JDK 17+ y el Android SDK 36.

## Estructura

```
app/src/main/java/com/vybra/music/
├── VybraApplication.kt      # Application + AppContainer (inyección manual) + Coil
├── artwork/                 # Carátulas: extracción embebida/MediaStore, fetcher Coil y BitmapLoader Media3
├── data/
│   ├── db/                  # Room: canciones, playlists, favoritos, historial
│   ├── prefs/               # DataStore: ajustes, estado de reproducción, ecualizador
│   ├── scanner/             # Escaneo de MediaStore, carpetas excluidas, observador de cambios
│   ├── MusicRepository.kt   # Biblioteca, favoritos, historial, estadísticas, ordenación
│   └── PlaylistRepository.kt
├── playback/
│   ├── PlaybackService.kt   # MediaSessionService + ExoPlayer (segundo plano, notificación, audio focus)
│   ├── PlayerConnection.kt  # MediaController que usa la interfaz
│   ├── AudioEffectsManager.kt # Ecualizador y refuerzo de graves
│   └── SleepTimer.kt
└── ui/
    ├── VybraRoot.kt         # Navegación, barra inferior, mini reproductor, hojas
    ├── player/              # Reproductor completo, mini reproductor, cola, temporizador
    ├── screens/             # Inicio, Buscar, Biblioteca, Playlists, Ajustes y detalles (+ ViewModels)
    ├── components/          # Componentes reutilizables
    └── theme/               # Colores, tipografía (Plus Jakarta Sans), tema dinámico
```

## Notas

- La biblioteca se actualiza sola cuando cambian los archivos (observador de MediaStore) y también con "deslizar para actualizar" en Biblioteca.
- El ecualizador depende de que el fabricante exponga los efectos de audio; si no, se ofrece abrir el ecualizador del sistema.
- Las playlists, favoritos e historial se guardan en Room (`vybra.db`) y se incluyen en la copia de seguridad de Android.
- Si cambias el esquema de Room en el futuro, añade una migración: no hay migración destructiva para no perder playlists.

La versión para Windows está en la carpeta [`windows/`](windows/README.md).
