package com.vybra.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.ListeningStats
import com.vybra.music.data.db.Song
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.SongListKind
import com.vybra.music.ui.common.albumCountLabel
import com.vybra.music.ui.common.dayLabel
import com.vybra.music.ui.common.formatLongDuration
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.common.timeLabel
import com.vybra.music.ui.components.AlbumCard
import com.vybra.music.ui.components.ArtistAvatar
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.CollectionLayout
import com.vybra.music.ui.components.ConfirmDialog
import com.vybra.music.ui.components.DetailHeader
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.FavoritesCover
import com.vybra.music.ui.components.GradientIconCover
import com.vybra.music.ui.components.MediaRow
import com.vybra.music.ui.components.SectionHeader
import com.vybra.music.ui.components.SongRow
import com.vybra.music.ui.components.rememberArtworkColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ===================== Álbum =====================

class AlbumViewModel(private val c: AppContainer, albumId: Long) : ViewModel() {
    val album = c.music.album(albumId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val songs = c.music.albumSongs(albumId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val player = c.player.state
    val connection = c.player
}

@Composable
fun AlbumScreen(albumId: Long) {
    val vm = vybraViewModel(key = "album$albumId") { AlbumViewModel(it, albumId) }
    val ui = LocalUi.current
    val album by vm.album.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val a = album
    val list = songs ?: emptyList()
    val tint by rememberArtworkColor(a?.coverSongId ?: -1, albumId, MaterialTheme.colorScheme.surfaceContainerHighest)
    val source = "Álbum: ${a?.title ?: ""}"
    val isPlayingHere = player.isPlaying && player.sourceName == source

    CollectionLayout(title = a?.title ?: "", tint = tint, onBack = ui.back) {
        if (a == null) return@CollectionLayout
        item(key = "header") {
            DetailHeader(
                tint = tint,
                title = a.title,
                subtitle = a.artist,
                onSubtitleClick = { ui.navigate(Routes.artist(a.artist)) },
                meta = buildString {
                    append("Álbum")
                    if (a.year > 0) append(" • ${a.year}")
                    append(" • ${songCountLabel(a.songCount)} • ${formatLongDuration(a.duration)}")
                },
                onPlay = {
                    if (isPlayingHere) vm.connection.togglePlayPause() else vm.connection.playSongs(list, 0, source = source)
                },
                onShuffle = { vm.connection.playSongs(list, shuffle = true, source = source) },
                isPlayingHere = isPlayingHere,
                actions = {
                    IconButton(onClick = { ui.addToPlaylist = list }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar a playlist")
                    }
                },
            ) {
                Artwork(a.coverSongId, a.id, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 72.dp)
            }
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                onClick = { vm.connection.playSongs(list, i, source = source) },
                onMore = { ui.openSongMenu(song) },
                index = if (song.track > 0) song.track else i + 1,
                showArtwork = false,
                subtitle = song.artist,
            )
        }
    }
}

// ===================== Artista =====================

class ArtistViewModel(private val c: AppContainer, val name: String) : ViewModel() {
    val songs = c.music.artistSongs(name).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums = c.music.artistAlbums(name).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val player = c.player.state
    val connection = c.player
}

@Composable
fun ArtistScreen(name: String) {
    val vm = vybraViewModel(key = "artist$name") { ArtistViewModel(it, name) }
    val ui = LocalUi.current
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val list = songs ?: emptyList()
    val first = list.firstOrNull()
    val tint by rememberArtworkColor(first?.id ?: -1, first?.albumId ?: -1, MaterialTheme.colorScheme.surfaceContainerHighest)
    val source = "Artista: $name"
    val isPlayingHere = player.isPlaying && player.sourceName == source

    CollectionLayout(title = name, tint = tint, onBack = ui.back) {
        item(key = "header") {
            DetailHeader(
                tint = tint,
                title = name,
                subtitle = null,
                meta = "Artista • ${songCountLabel(list.size)} • ${albumCountLabel(albums.size)}",
                onPlay = {
                    if (isPlayingHere) vm.connection.togglePlayPause() else vm.connection.playSongs(list, 0, source = source)
                },
                onShuffle = { vm.connection.playSongs(list, shuffle = true, source = source) },
                isPlayingHere = isPlayingHere,
                playEnabled = list.isNotEmpty(),
                coverShape = CircleShape,
                actions = {
                    IconButton(onClick = { ui.addToPlaylist = list }, enabled = list.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar a playlist")
                    }
                },
            ) {
                ArtistAvatar(name, first?.id ?: -1, first?.albumId ?: -1, Modifier.fillMaxSize(), 80.dp)
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albumsHeader") { SectionHeader("Álbumes") }
            item(key = "albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(album, onClick = { ui.navigate(Routes.album(album.id)) })
                    }
                }
            }
        }
        item(key = "songsHeader") { SectionHeader("Canciones") }
        itemsIndexed(list, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                onClick = { vm.connection.playSongs(list, i, source = source) },
                onMore = { ui.openSongMenu(song) },
                subtitle = song.album,
            )
        }
    }
}

// ===================== Listas automáticas (favoritas, recientes...) =====================

class SongListViewModel(private val c: AppContainer, val kind: SongListKind) : ViewModel() {
    private val source: Flow<List<Song>> = when (kind) {
        SongListKind.FAVORITES -> c.music.favorites
        SongListKind.RECENTLY_ADDED -> c.music.recentlyAdded(100)
        SongListKind.MOST_PLAYED -> c.music.mostPlayed(100).map { list -> list.map { it.song } }
        SongListKind.RECENTLY_PLAYED -> c.music.recentlyPlayed(100)
    }
    val songs = source.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val plays = (if (kind == SongListKind.MOST_PLAYED) c.music.mostPlayed(100).map { list -> list.associate { it.song.id to it.plays } }
    else kotlinx.coroutines.flow.flowOf(emptyMap())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val player = c.player.state
    val connection = c.player

    fun createPlaylistFromFavorites(onDone: (Long) -> Unit) = viewModelScope.launch {
        val list = songs.value ?: return@launch
        onDone(c.playlists.create("Mis favoritas", list.map { it.id }))
    }
}

@Composable
fun SongListScreen(kind: SongListKind) {
    val vm = vybraViewModel(key = "list${kind.name}") { SongListViewModel(it, kind) }
    val ui = LocalUi.current
    val songs by vm.songs.collectAsStateWithLifecycle()
    val plays by vm.plays.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val list = songs ?: emptyList()
    val tint = when (kind) {
        SongListKind.FAVORITES -> Color(0xFF5B2EFF)
        SongListKind.RECENTLY_ADDED -> Color(0xFF0EA5E9)
        SongListKind.MOST_PLAYED -> Color(0xFFF97316)
        SongListKind.RECENTLY_PLAYED -> Color(0xFF14B8A6)
    }
    val isPlayingHere = player.isPlaying && player.sourceName == kind.title

    CollectionLayout(title = kind.title, tint = tint, onBack = ui.back) {
        item(key = "header") {
            DetailHeader(
                tint = tint,
                title = kind.title,
                subtitle = null,
                meta = "${songCountLabel(list.size)} • ${formatLongDuration(list.sumOf { it.duration })}",
                onPlay = {
                    if (isPlayingHere) vm.connection.togglePlayPause() else vm.connection.playSongs(list, 0, source = kind.title)
                },
                onShuffle = { vm.connection.playSongs(list, shuffle = true, source = kind.title) },
                isPlayingHere = isPlayingHere,
                playEnabled = list.isNotEmpty(),
                actions = {
                    if (kind == SongListKind.FAVORITES) {
                        IconButton(onClick = {
                            vm.createPlaylistFromFavorites { id ->
                                ui.message("Playlist creada con tus favoritas")
                                ui.navigate(Routes.playlist(id))
                            }
                        }, enabled = list.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Crear playlist con favoritas")
                        }
                    } else {
                        IconButton(onClick = { ui.addToPlaylist = list }, enabled = list.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar a playlist")
                        }
                    }
                },
            ) {
                when (kind) {
                    SongListKind.FAVORITES -> FavoritesCover(Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 88.dp)
                    SongListKind.RECENTLY_ADDED -> GradientIconCover(Icons.Rounded.NewReleases, listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)), Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 88.dp)
                    SongListKind.MOST_PLAYED -> GradientIconCover(Icons.AutoMirrored.Rounded.TrendingUp, listOf(Color(0xFFF97316), Color(0xFFEF4444)), Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 88.dp)
                    SongListKind.RECENTLY_PLAYED -> GradientIconCover(Icons.Rounded.History, listOf(Color(0xFF14B8A6), Color(0xFF0F766E)), Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 88.dp)
                }
            }
        }
        if (songs != null && list.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    when (kind) {
                        SongListKind.FAVORITES -> Icons.Rounded.Favorite
                        else -> Icons.Rounded.History
                    },
                    "Nada por aquí todavía",
                    when (kind) {
                        SongListKind.FAVORITES -> "Toca el corazón en cualquier canción para guardarla aquí."
                        SongListKind.RECENTLY_ADDED -> "Cuando agregues música al teléfono aparecerá aquí."
                        else -> "Escucha algo de música y aparecerá aquí."
                    },
                )
            }
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                onClick = { vm.connection.playSongs(list, i, source = kind.title) },
                onMore = { ui.openSongMenu(song) },
                index = if (kind == SongListKind.MOST_PLAYED) i + 1 else null,
                subtitle = plays[song.id]?.let { "${song.artist} • $it reproducciones" },
            )
        }
    }
}

// ===================== Historial =====================

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val history = c.music.history(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val connection = c.player
    fun clear() = viewModelScope.launch { c.music.clearHistory() }
    fun delete(eventId: Long) = viewModelScope.launch { c.music.deleteHistoryEntry(eventId) }
}

@Composable
fun HistoryScreen() {
    val vm = vybraViewModel { HistoryViewModel(it) }
    val ui = LocalUi.current
    val history by vm.history.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    if (confirm) {
        ConfirmDialog(
            "Borrar historial",
            "Se eliminará todo el historial de reproducción y las estadísticas.",
            "Borrar",
            onDismiss = { confirm = false },
            onConfirm = { vm.clear(); confirm = false; ui.message("Historial borrado") },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Historial", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirm = true }, enabled = !history.isNullOrEmpty()) {
                Icon(Icons.Rounded.DeleteSweep, "Borrar historial")
            }
        }
        val entries = history ?: return@Column
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.History, "Sin historial", "Las canciones que escuches aparecerán aquí.")
            return@Column
        }
        val songsInOrder = entries.map { it.song }
        LazyColumn(Modifier.fillMaxSize()) {
            var lastDay = ""
            entries.forEachIndexed { i, entry ->
                val day = dayLabel(entry.playedAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day$day$i") { SectionHeader(day) }
                }
                item(key = "e${entry.eventId}") {
                    SongRow(
                        entry.song,
                        onClick = { vm.connection.playSongs(songsInOrder, i, source = "Historial") },
                        onMore = { ui.openSongMenu(entry.song) },
                        subtitle = "${entry.song.artist} • ${timeLabel(entry.playedAt)}",
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ===================== Estadísticas =====================

class StatsViewModel(c: AppContainer) : ViewModel() {
    val connection = c.player
    val stats = c.music.stats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StatsScreen() {
    val vm = vybraViewModel { StatsViewModel(it) }
    val ui = LocalUi.current
    val stats by vm.stats.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
            Text("Estadísticas", style = MaterialTheme.typography.titleLarge)
        }
        val s = stats ?: return@Column
        if (s.totalPlays == 0) {
            EmptyState(Icons.Rounded.BarChart, "Aún no hay estadísticas", "Escucha música durante al menos 10 segundos por canción y aquí verás tu actividad.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { StatsCards(s) }
            if (s.topSongs.isNotEmpty()) {
                item { SectionHeader("Tus canciones más escuchadas") }
                itemsIndexed(s.topSongs, key = { _, it -> "s${it.song.id}" }) { i, sp ->
                    SongRow(
                        sp.song,
                        onClick = { vm.connection.playSongs(s.topSongs.map { it.song }, i, source = "Más escuchadas") },
                        onMore = { ui.openSongMenu(sp.song) },
                        index = i + 1,
                        subtitle = "${sp.song.artist} • ${sp.plays} reproducciones",
                    )
                }
            }
            if (s.topArtists.isNotEmpty()) {
                item { SectionHeader("Tus artistas favoritos") }
                items(s.topArtists, key = { "a${it.artist}" }) { a ->
                    MediaRow(a.artist, "${a.plays} reproducciones", onClick = { ui.navigate(Routes.artist(a.artist)) }) {
                        ArtistAvatar(a.artist, a.coverSongId, a.coverAlbumId, Modifier.size(56.dp), 24.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCards(s: ListeningStats) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Tiempo escuchado", formatLongDuration(s.totalListenedMs), Modifier.weight(1f), highlight = true)
            StatCard("Reproducciones", s.totalPlays.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Canciones distintas", s.distinctSongs.toString(), Modifier.weight(1f))
            StatCard("Últimos 7 días", "${s.playsLast7Days} • ${formatLongDuration(s.listenedLast7DaysMs)}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
