package com.vybra.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.db.Album
import com.vybra.music.data.db.Artist
import com.vybra.music.data.db.PlaylistInfo
import com.vybra.music.data.db.Song
import com.vybra.music.data.scanner.ScanState
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.SongListKind
import com.vybra.music.ui.common.greeting
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.components.AlbumCard
import com.vybra.music.ui.components.ArtistCard
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.FavoritesCover
import com.vybra.music.ui.components.GradientIconCover
import com.vybra.music.ui.components.MediaCard
import com.vybra.music.ui.components.PlaylistCover
import com.vybra.music.ui.components.QuickTile
import com.vybra.music.ui.components.SectionHeader
import com.vybra.music.ui.components.SongCard
import com.vybra.music.ui.theme.contentColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val loaded: Boolean = false,
    val userName: String = "",
    val totalSongs: Int = 0,
    val recentlyPlayed: List<Song> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val recentlyAdded: List<Song> = emptyList(),
    val mostPlayed: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val libraryPart = combine(
        c.music.songCount,
        c.music.recentlyAdded(15),
        c.music.albums.map { list -> list.sortedByDescending { it.dateAdded }.take(15) },
        c.music.artists.map { list -> list.sortedByDescending { it.songCount }.take(15) },
    ) { count, added, albums, artists -> listOf(count, added, albums, artists) }

    private val personalPart = combine(
        c.music.recentlyPlayed(15),
        c.music.favorites.map { it.take(15) },
        c.music.mostPlayed(15).map { list -> list.map { it.song } },
        c.playlists.playlists,
        c.settings.settings.map { it.userName },
    ) { recent, fav, most, playlists, name -> listOf(recent, fav, most, playlists, name) }

    @Suppress("UNCHECKED_CAST")
    val state = combine(libraryPart, personalPart) { lib, per ->
        HomeState(
            loaded = true,
            totalSongs = lib[0] as Int,
            recentlyAdded = lib[1] as List<Song>,
            albums = lib[2] as List<Album>,
            artists = lib[3] as List<Artist>,
            recentlyPlayed = per[0] as List<Song>,
            favorites = per[1] as List<Song>,
            mostPlayed = per[2] as List<Song>,
            playlists = per[3] as List<PlaylistInfo>,
            userName = per[4] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    val scan = c.scanner.state
    val allSongs = c.music.songs

    fun play(songs: List<Song>, index: Int, source: String) = c.player.playSongs(songs, index, source = source)

    fun shuffleAll() {
        val all = c.music.songs.value ?: return
        c.player.playSongs(all, shuffle = true, source = "Toda tu música")
    }

    fun rescan() = c.scanner.scanAsync()
}

@Composable
fun HomeScreen() {
    val vm = vybraViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val ui = LocalUi.current
    // Mantiene cargada la lista completa para el modo aleatorio.
    val allSongs by vm.allSongs.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(greeting(state.userName), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { ui.navigate(Routes.HISTORY) }) { Icon(Icons.Rounded.History, "Historial") }
                    IconButton(onClick = { ui.navigate(Routes.STATS) }) { Icon(Icons.Rounded.BarChart, "Estadísticas") }
                }
            }
        }

        if (state.loaded && state.totalSongs == 0) {
            item { NoMusicState(scan) { vm.rescan() } }
            return@LazyColumn
        }

        item { QuickAccessGrid(state) }

        item {
            ShuffleBanner(state.totalSongs, enabled = allSongs != null) { vm.shuffleAll() }
        }

        if (state.recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader("Escuchado recientemente", action = "Ver todo") {
                    ui.navigate(Routes.songList(SongListKind.RECENTLY_PLAYED))
                }
            }
            item { SongRowCarousel(state.recentlyPlayed) { i -> vm.play(state.recentlyPlayed, i, "Escuchado recientemente") } }
        }

        if (state.favorites.isNotEmpty()) {
            item {
                SectionHeader("Tus favoritas", action = "Ver todo") {
                    ui.navigate(Routes.songList(SongListKind.FAVORITES))
                }
            }
            item { SongRowCarousel(state.favorites) { i -> vm.play(state.favorites, i, "Canciones favoritas") } }
        }

        if (state.recentlyAdded.isNotEmpty()) {
            item {
                SectionHeader("Agregado recientemente", action = "Ver todo") {
                    ui.navigate(Routes.songList(SongListKind.RECENTLY_ADDED))
                }
            }
            item { SongRowCarousel(state.recentlyAdded) { i -> vm.play(state.recentlyAdded, i, "Agregado recientemente") } }
        }

        if (state.albums.isNotEmpty()) {
            item { SectionHeader("Álbumes", action = "Ver todo") { ui.openLibrary(1) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.albums, key = { it.id }) { album ->
                        AlbumCard(album, onClick = { ui.navigate(Routes.album(album.id)) })
                    }
                }
            }
        }

        if (state.artists.isNotEmpty()) {
            item { SectionHeader("Artistas", action = "Ver todo") { ui.openLibrary(2) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.artists, key = { it.name }) { artist ->
                        ArtistCard(
                            artist.name, songCountLabel(artist.songCount), artist.coverSongId, artist.coverAlbumId,
                            onClick = { ui.navigate(Routes.artist(artist.name)) },
                        )
                    }
                }
            }
        }

        item { SectionHeader("Tus playlists", action = "Ver todo") { ui.navigateToTab(Routes.PLAYLISTS) } }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    MediaCard("Canciones favoritas", "Playlist automática", onClick = {
                        ui.navigate(Routes.songList(SongListKind.FAVORITES))
                    }) { FavoritesCover(Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 44.dp) }
                }
                items(state.playlists, key = { it.playlist.id }) { info ->
                    MediaCard(info.playlist.name, songCountLabel(info.songCount), onClick = {
                        ui.navigate(Routes.playlist(info.playlist.id))
                    }) {
                        PlaylistCover(
                            info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id,
                            Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 44.dp,
                        )
                    }
                }
                item {
                    MediaCard("Nueva playlist", "Crea tu propia lista", onClick = { ui.navigateToTab(Routes.PLAYLISTS) }) {
                        GradientIconCover(
                            Icons.Rounded.Add,
                            listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh),
                            Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 44.dp,
                        )
                    }
                }
            }
        }

        if (state.mostPlayed.isNotEmpty()) {
            item {
                SectionHeader("Lo que más escuchas", action = "Ver todo") {
                    ui.navigate(Routes.songList(SongListKind.MOST_PLAYED))
                }
            }
            item { SongRowCarousel(state.mostPlayed) { i -> vm.play(state.mostPlayed, i, "Más escuchadas") } }
        }
    }
}

@Composable
private fun SongRowCarousel(songs: List<Song>, onPlay: (Int) -> Unit) {
    val ui = LocalUi.current
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongCard(song, onClick = { onPlay(i) }, onLongClick = { ui.openSongMenu(song) })
        }
    }
}

@Composable
private fun QuickAccessGrid(state: HomeState) {
    val ui = LocalUi.current
    val tiles = buildList<Pair<String, Pair<() -> Unit, @Composable () -> Unit>>> {
        add("Canciones favoritas" to ({ ui.navigate(Routes.songList(SongListKind.FAVORITES)) } to { FavoritesCover(Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 22.dp) }))
        state.playlists.take(3).forEach { info ->
            add(info.playlist.name to ({ ui.navigate(Routes.playlist(info.playlist.id)) } to {
                PlaylistCover(info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 22.dp)
            }))
        }
        add("Agregadas recientemente" to ({ ui.navigate(Routes.songList(SongListKind.RECENTLY_ADDED)) } to {
            GradientIconCover(Icons.Rounded.NewReleases, listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 22.dp)
        }))
        add("Más escuchadas" to ({ ui.navigate(Routes.songList(SongListKind.MOST_PLAYED)) } to {
            GradientIconCover(Icons.AutoMirrored.Rounded.TrendingUp, listOf(Color(0xFFF97316), Color(0xFFEF4444)), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 22.dp)
        }))
        if (size < 6) add("Tu biblioteca" to ({ ui.openLibrary(0) } to {
            GradientIconCover(Icons.Rounded.LibraryMusic, listOf(Color(0xFF10B981), Color(0xFF0D9488)), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 22.dp)
        }))
    }.take(6)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (title, actions) ->
                    QuickTile(title, onClick = actions.first, modifier = Modifier.weight(1f)) { actions.second() }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShuffleBanner(total: Int, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(primary, primary.copy(alpha = 0.55f))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mezcla aleatoria", style = MaterialTheme.typography.titleLarge, color = primary.contentColor())
            Text(
                "Toda tu música • ${songCountLabel(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = primary.contentColor().copy(alpha = 0.8f),
            )
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(primary.contentColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Shuffle, "Reproducir aleatorio", tint = primary)
        }
    }
}

@Composable
private fun NoMusicState(scan: ScanState, onRescan: () -> Unit) {
    if (scan.isScanning) {
        Column(Modifier.fillMaxWidth().padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Buscando tu música…", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        EmptyState(
            icon = Icons.Rounded.LibraryMusic,
            title = "No encontramos música",
            message = "Copia archivos de audio (MP3, FLAC, M4A, OGG, WAV…) a tu teléfono y vuelve a escanear. " +
                "Revisa también las carpetas excluidas en Ajustes.",
            actionLabel = "Escanear de nuevo",
            onAction = onRescan,
        )
    }
}
