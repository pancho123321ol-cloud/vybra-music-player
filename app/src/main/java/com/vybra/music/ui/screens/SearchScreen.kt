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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.db.Album
import com.vybra.music.data.db.Artist
import com.vybra.music.data.db.PlaylistInfo
import com.vybra.music.data.db.Song
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.SongListKind
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.components.AlbumCard
import com.vybra.music.ui.components.ArtistCard
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.MediaRow
import com.vybra.music.ui.components.PlaylistCover
import com.vybra.music.ui.components.SectionHeader
import com.vybra.music.ui.components.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchResults(
    val query: String = "",
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val recent = c.settings.settings.map { it.recentSearches }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private class Indexed<T>(val item: T, val key: String)

    private val songIndex = c.music.songs.filterNotNull().map { list ->
        list.map { Indexed(it, MusicRepository.normalize("${it.title} ${it.artist} ${it.album}")) }
    }.flowOn(Dispatchers.Default)
    private val albumIndex = c.music.albums.map { list ->
        list.map { Indexed(it, MusicRepository.normalize("${it.title} ${it.artist}")) }
    }.flowOn(Dispatchers.Default)
    private val artistIndex = c.music.artists.map { list ->
        list.map { Indexed(it, MusicRepository.normalize(it.name)) }
    }.flowOn(Dispatchers.Default)

    val results = combine(
        query.debounce(150),
        songIndex,
        albumIndex,
        artistIndex,
        c.playlists.playlists,
    ) { q, songs, albums, artists, playlists ->
        val norm = MusicRepository.normalize(q.trim())
        if (norm.isEmpty()) return@combine SearchResults()
        val tokens = norm.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(key: String) = tokens.all { key.contains(it) }
        SearchResults(
            query = q,
            songs = songs.filter { matches(it.key) }
                .sortedBy { if (MusicRepository.normalize(it.item.title).startsWith(norm)) 0 else 1 }
                .take(60).map { it.item },
            albums = albums.filter { matches(it.key) }.take(20).map { it.item },
            artists = artists.filter { matches(it.key) }
                .sortedBy { if (it.key.startsWith(norm)) 0 else 1 }
                .take(20).map { it.item },
            playlists = playlists.filter { matches(MusicRepository.normalize(it.playlist.name)) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun saveQuery(q: String = query.value) = viewModelScope.launch { c.settings.addRecentSearch(q) }
    fun removeRecent(q: String) = viewModelScope.launch { c.settings.removeRecentSearch(q) }
    fun clearRecent() = viewModelScope.launch { c.settings.clearRecentSearches() }
    fun play(songs: List<Song>, index: Int) {
        saveQuery()
        c.player.playSongs(songs, index, source = "Búsqueda: ${query.value.trim()}")
    }
}

@Composable
fun SearchScreen() {
    val vm = vybraViewModel { SearchViewModel(it) }
    val ui = LocalUi.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Buscar",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        )
        TextField(
            value = query,
            onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Canciones, artistas o álbumes") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Borrar") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.saveQuery(); focus.clearFocus() }),
        )
        Spacer(Modifier.height(8.dp))

        if (query.isBlank()) {
            BrowseContent(recent, onRecent = { vm.query.value = it }, onRemove = { vm.removeRecent(it) }, onClear = { vm.clearRecent() })
            return@Column
        }
        if (results.isEmpty && results.query == query) {
            EmptyState(Icons.Rounded.SearchOff, "Sin resultados", "No encontramos nada para \"$query\". Prueba con otras palabras.")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (results.artists.isNotEmpty()) {
                item { SectionHeader("Artistas") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(results.artists, key = { it.name }) { a ->
                            ArtistCard(a.name, songCountLabel(a.songCount), a.coverSongId, a.coverAlbumId, onClick = {
                                vm.saveQuery(); ui.navigate(Routes.artist(a.name))
                            }, width = 112.dp)
                        }
                    }
                }
            }
            if (results.albums.isNotEmpty()) {
                item { SectionHeader("Álbumes") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(results.albums, key = { it.id }) { a ->
                            AlbumCard(a, onClick = { vm.saveQuery(); ui.navigate(Routes.album(a.id)) }, width = 132.dp)
                        }
                    }
                }
            }
            if (results.songs.isNotEmpty()) {
                item { SectionHeader("Canciones") }
                itemsIndexed(results.songs, key = { _, s -> "s${s.id}" }) { i, song ->
                    SongRow(song, onClick = { vm.play(results.songs, i) }, onMore = { ui.openSongMenu(song) })
                }
            }
            if (results.playlists.isNotEmpty()) {
                item { SectionHeader("Playlists") }
                items(results.playlists, key = { "p${it.playlist.id}" }) { info ->
                    MediaRow(info.playlist.name, songCountLabel(info.songCount), onClick = {
                        vm.saveQuery(); ui.navigate(Routes.playlist(info.playlist.id))
                    }) {
                        PlaylistCover(info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id, Modifier.size(56.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class BrowseTile(val title: String, val color: Color, val icon: ImageVector, val action: () -> Unit)

@Composable
private fun BrowseContent(recent: List<String>, onRecent: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    val ui = LocalUi.current
    val tiles = listOf(
        BrowseTile("Canciones", Color(0xFFE13300), Icons.Rounded.MusicNote) { ui.openLibrary(0) },
        BrowseTile("Álbumes", Color(0xFF1E3264), Icons.Rounded.Album) { ui.openLibrary(1) },
        BrowseTile("Artistas", Color(0xFF8400E7), Icons.Rounded.Person) { ui.openLibrary(2) },
        BrowseTile("Favoritas", Color(0xFFDC148C), Icons.Rounded.Favorite) { ui.navigate(Routes.songList(SongListKind.FAVORITES)) },
        BrowseTile("Recién agregadas", Color(0xFF148A08), Icons.Rounded.NewReleases) { ui.navigate(Routes.songList(SongListKind.RECENTLY_ADDED)) },
        BrowseTile("Más escuchadas", Color(0xFFE8115B), Icons.AutoMirrored.Rounded.TrendingUp) { ui.navigate(Routes.songList(SongListKind.MOST_PLAYED)) },
        BrowseTile("Historial", Color(0xFF477D95), Icons.Rounded.History) { ui.navigate(Routes.HISTORY) },
        BrowseTile("Playlists", Color(0xFFBA5D07), Icons.AutoMirrored.Rounded.QueueMusic) { ui.navigateToTab(Routes.PLAYLISTS) },
    )
    LazyColumn(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Búsquedas recientes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("Borrar") }
                }
            }
            items(recent, key = { "r$it" }) { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { onRecent(q) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(q) }) {
                        Icon(Icons.Rounded.Close, "Quitar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionHeader("Explorar") }
        items(tiles.chunked(2)) { row ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { tile ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tile.color)
                            .clickable(onClick = tile.action)
                            .padding(12.dp),
                    ) {
                        Text(tile.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Icon(
                            tile.icon, null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(52.dp)
                                .offset(x = 14.dp, y = 10.dp)
                                .rotate(22f),
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
