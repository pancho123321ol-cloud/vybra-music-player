package com.vybra.music.ui

import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.vybra.music.data.db.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"

    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{name}"
    const val PLAYLIST = "playlist/{id}"
    const val ADD_SONGS = "playlist/{id}/add"
    const val SONG_LIST = "songs/{kind}"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val EQUALIZER = "equalizer"
    const val FOLDERS = "folders"
    const val VISUALS = "visuals"
    const val MIX_SETTINGS = "mix"
    const val PLAYLIST_MIX = "playlist/{id}/mix"
    fun playlistMix(id: Long) = "playlist/$id/mix"
    const val TRANSITION_EDITOR = "mixedit/{from}/{to}"
    fun transitionEditor(from: Long, to: Long) = "mixedit/$from/$to"

    val tabs = listOf(HOME, SEARCH, LIBRARY, PLAYLISTS, SETTINGS)

    fun album(id: Long) = "album/$id"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun playlist(id: Long) = "playlist/$id"
    fun addSongs(id: Long) = "playlist/$id/add"
    fun songList(kind: SongListKind) = "songs/${kind.name}"
}

enum class SongListKind(val title: String) {
    FAVORITES("Canciones favoritas"),
    RECENTLY_ADDED("Agregadas recientemente"),
    MOST_PLAYED("Más escuchadas"),
    RECENTLY_PLAYED("Escuchadas recientemente"),
}

enum class LyricsEditorMode {
    /** Escribir o pegar la letra. */
    EDIT,
    /** Marcar cada línea a mano mientras suena. */
    MANUAL,
    /** Sincronizar automáticamente y luego revisar. */
    AUTO,
    /** Revisar y ajustar los tiempos de una letra ya sincronizada. */
    REVIEW,
}

data class LyricsEditorRequest(
    val songId: Long,
    val title: String,
    val artist: String,
    /** Modo en que se abre el editor. */
    val mode: LyricsEditorMode = LyricsEditorMode.EDIT,
)

data class SongMenuRequest(val song: Song, val playlistId: Long? = null)

/** Estado global de la interfaz: hojas inferiores, reproductor completo, mensajes y navegación. */
class UiController(private val scope: CoroutineScope) {
    val snackbar = SnackbarHostState()

    var songMenu by mutableStateOf<SongMenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var songDetails by mutableStateOf<Song?>(null)
    var showNowPlaying by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showSleepTimer by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showVisuals by mutableStateOf(false)
    var lyricsEditor by mutableStateOf<LyricsEditorRequest?>(null)
    var libraryTab by mutableIntStateOf(0)

    var navigate: (String) -> Unit = {}
    var navigateToTab: (String) -> Unit = {}
    var back: () -> Unit = {}

    fun openSongMenu(song: Song, playlistId: Long? = null) {
        songMenu = SongMenuRequest(song, playlistId)
    }

    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
        }
    }

    fun openLibrary(tab: Int) {
        libraryTab = tab
        navigateToTab(Routes.LIBRARY)
    }
}

val LocalUi = staticCompositionLocalOf<UiController> { error("UiController no disponible") }
