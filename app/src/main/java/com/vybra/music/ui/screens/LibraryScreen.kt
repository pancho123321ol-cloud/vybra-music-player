package com.vybra.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.db.Album
import com.vybra.music.data.db.Artist
import com.vybra.music.data.db.Song
import com.vybra.music.data.prefs.LibraryStyle
import com.vybra.music.data.prefs.SortOption
import com.vybra.music.data.prefs.UserSettings
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.common.albumCountLabel
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.components.AlbumCard
import com.vybra.music.ui.components.ArtistAvatar
import com.vybra.music.ui.components.ArtistCard
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.FilterChipRow
import com.vybra.music.ui.components.LoadingBox
import com.vybra.music.ui.components.MediaRow
import com.vybra.music.ui.components.ScreenTitle
import com.vybra.music.ui.components.SongCard
import com.vybra.music.ui.components.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryContent(
    val loaded: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val scan = c.scanner.state

    val content = combine(c.music.songs.filterNotNull(), c.music.albums, c.music.artists, c.settings.settings) { songs, albums, artists, s ->
        LibraryContent(
            loaded = true,
            songs = c.music.sortSongs(songs, s.sortOption, s.sortAscending),
            albums = c.music.sortAlbums(albums, s.sortOption, s.sortAscending),
            artists = c.music.sortArtists(artists, if (s.sortOption == SortOption.ARTIST || s.sortOption == SortOption.TITLE) s.sortAscending else true),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryContent())

    fun setSort(option: SortOption) = viewModelScope.launch {
        val s = settings.value
        val ascending = if (s.sortOption == option) !s.sortAscending else option != SortOption.DATE_ADDED
        c.settings.setSort(option, ascending)
    }

    fun toggleStyle() = viewModelScope.launch {
        c.settings.setLibraryStyle(if (settings.value.libraryStyle == LibraryStyle.LIST) LibraryStyle.GRID else LibraryStyle.LIST)
    }

    fun rescan() = c.scanner.scanAsync()
    fun play(songs: List<Song>, index: Int, shuffle: Boolean? = null) =
        c.player.playSongs(songs, index, shuffle, source = "Tu biblioteca")
}

private val sortLabels = mapOf(
    SortOption.TITLE to "Nombre",
    SortOption.ARTIST to "Artista",
    SortOption.ALBUM to "Álbum",
    SortOption.DATE_ADDED to "Fecha de incorporación",
)

@Composable
fun LibraryScreen() {
    val vm = vybraViewModel { LibraryViewModel(it) }
    val ui = LocalUi.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    val grid = settings.libraryStyle == LibraryStyle.GRID

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Tu biblioteca") {
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar")
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Text(
                        "Ordenar por",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SortOption.entries.forEach { option ->
                        val selected = settings.sortOption == option
                        DropdownMenuItem(
                            text = {
                                Text(
                                    sortLabels.getValue(option) + if (selected) (if (settings.sortAscending) "  ↑" else "  ↓") else "",
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leadingIcon = {
                                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = { vm.setSort(option); sortMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = { vm.toggleStyle() }) {
                Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "Vista de lista" else "Vista de cuadrícula")
            }
            IconButton(onClick = { vm.rescan() }, enabled = !scan.isScanning) {
                Icon(Icons.Rounded.Refresh, "Actualizar biblioteca")
            }
        }
        FilterChipRow(
            listOf("Canciones", "Álbumes", "Artistas"),
            selected = ui.libraryTab,
            onSelect = { ui.libraryTab = it },
        )
        if (scan.isScanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(CircleShape))
        }

        if (!content.loaded) {
            LoadingBox()
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = scan.isScanning,
            onRefresh = { vm.rescan() },
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = ui.libraryTab to grid,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "libraryTab",
            ) { (tab, isGrid) ->
                when (tab) {
                    0 -> SongsTab(content.songs, isGrid, vm)
                    1 -> AlbumsTab(content.albums, isGrid)
                    else -> ArtistsTab(content.artists, isGrid)
                }
            }
        }
    }
}

@Composable
private fun PlayAllRow(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            songCountLabel(count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(CircleShape).clickable(onClick = onShuffle).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Shuffle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Aleatorio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier.clip(CircleShape).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text("Reproducir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SongsTab(songs: List<Song>, grid: Boolean, vm: LibraryViewModel) {
    val ui = LocalUi.current
    if (songs.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "Sin canciones", "No se encontraron archivos de música en el dispositivo.", Modifier.fillMaxSize())
        return
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PlayAllRow(songs.size, { vm.play(songs, 0, false) }, { vm.play(songs, 0, true) })
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongCard(song, onClick = { vm.play(songs, i) }, onLongClick = { ui.openSongMenu(song) }, width = null)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            item { PlayAllRow(songs.size, { vm.play(songs, 0, false) }, { vm.play(songs, 0, true) }) }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(song, onClick = { vm.play(songs, i) }, onMore = { ui.openSongMenu(song) })
            }
        }
    }
}

@Composable
private fun AlbumsTab(albums: List<Album>, grid: Boolean) {
    val ui = LocalUi.current
    if (albums.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "Sin álbumes", "Aún no hay álbumes en tu biblioteca.", Modifier.fillMaxSize())
        return
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, onClick = { ui.navigate(Routes.album(album.id)) }, width = null)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Spacer(Modifier.size(4.dp)) }
            items(albums, key = { it.id }) { album ->
                MediaRow(
                    title = album.title,
                    subtitle = "${album.artist} • ${songCountLabel(album.songCount)}",
                    onClick = { ui.navigate(Routes.album(album.id)) },
                ) { Artwork(album.coverSongId, album.id, Modifier.size(56.dp), RoundedCornerShape(6.dp)) }
            }
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<Artist>, grid: Boolean) {
    val ui = LocalUi.current
    if (artists.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "Sin artistas", "Aún no hay artistas en tu biblioteca.", Modifier.fillMaxSize())
        return
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(artists, key = { it.name }) { artist ->
                ArtistCard(
                    artist.name, songCountLabel(artist.songCount), artist.coverSongId, artist.coverAlbumId,
                    onClick = { ui.navigate(Routes.artist(artist.name)) }, width = null,
                )
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Spacer(Modifier.size(4.dp)) }
            items(artists, key = { it.name }) { artist ->
                MediaRow(
                    title = artist.name,
                    subtitle = "${songCountLabel(artist.songCount)} • ${albumCountLabel(artist.albumCount)}",
                    onClick = { ui.navigate(Routes.artist(artist.name)) },
                ) { ArtistAvatar(artist.name, artist.coverSongId, artist.coverAlbumId, Modifier.size(56.dp), 24.dp) }
            }
            item { HorizontalDivider(color = androidx.compose.ui.graphics.Color.Transparent) }
        }
    }
}
