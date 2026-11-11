package com.vybra.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vybra.music.AppContainer
import com.vybra.music.data.MusicRepository
import com.vybra.music.data.db.PlaylistInfo
import com.vybra.music.data.db.Song
import com.vybra.music.data.prefs.LibraryStyle
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.SongListKind
import com.vybra.music.ui.common.formatLongDuration
import com.vybra.music.ui.common.vybraViewModel
import com.vybra.music.ui.common.songCountLabel
import com.vybra.music.ui.components.CollectionLayout
import com.vybra.music.ui.components.ConfirmDialog
import com.vybra.music.ui.components.DetailHeader
import com.vybra.music.ui.components.EmptyState
import com.vybra.music.ui.components.FavoritesCover
import com.vybra.music.ui.components.GradientIconCover
import com.vybra.music.ui.components.MediaCard
import com.vybra.music.ui.components.MediaRow
import com.vybra.music.ui.components.PlaylistCover
import com.vybra.music.ui.components.ScreenTitle
import com.vybra.music.ui.components.SongRow
import com.vybra.music.ui.components.TextInputDialog
import com.vybra.music.ui.components.rememberArtworkColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ===================== Lista de playlists =====================

class PlaylistsViewModel(private val c: AppContainer) : ViewModel() {
    val playlists = c.playlists.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favoritesCount = c.music.favorites.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val style = c.settings.settings.map { it.libraryStyle }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStyle.LIST)

    fun create(name: String, onCreated: (Long) -> Unit) = viewModelScope.launch { onCreated(c.playlists.create(name)) }
    fun delete(id: Long) = viewModelScope.launch { c.playlists.delete(id) }
    fun rename(id: Long, name: String) = viewModelScope.launch { c.playlists.rename(id, name) }
}

@Composable
fun PlaylistsScreen() {
    val vm = vybraViewModel { PlaylistsViewModel(it) }
    val ui = LocalUi.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favCount by vm.favoritesCount.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<PlaylistInfo?>(null) }
    var renaming by remember { mutableStateOf<PlaylistInfo?>(null) }
    var deleting by remember { mutableStateOf<PlaylistInfo?>(null) }

    if (creating) {
        TextInputDialog("Nueva playlist", "", "Crear", onDismiss = { creating = false }, onConfirm = { name ->
            creating = false
            vm.create(name) { id -> ui.navigate(Routes.playlist(id)) }
        })
    }
    renaming?.let { info ->
        TextInputDialog("Renombrar playlist", info.playlist.name, "Guardar", onDismiss = { renaming = null }, onConfirm = {
            vm.rename(info.playlist.id, it); renaming = null
        })
    }
    deleting?.let { info ->
        ConfirmDialog(
            "Eliminar playlist",
            "¿Eliminar \"${info.playlist.name}\"? Las canciones no se borrarán del dispositivo.",
            "Eliminar",
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(info.playlist.id); deleting = null; ui.message("Playlist eliminada") },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Playlists") {
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Crear playlist") }
        }
        val list = playlists ?: return@Column

        val favoritesItem: @Composable (Boolean) -> Unit = { grid ->
            if (grid) {
                MediaCard("Canciones favoritas", songCountLabel(favCount), onClick = {
                    ui.navigate(Routes.songList(SongListKind.FAVORITES))
                }, width = null) { FavoritesCover(Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 48.dp) }
            } else {
                MediaRow("Canciones favoritas", "Playlist • ${songCountLabel(favCount)}", onClick = {
                    ui.navigate(Routes.songList(SongListKind.FAVORITES))
                }) { FavoritesCover(Modifier.size(56.dp)) }
            }
        }
        val createItem: @Composable (Boolean) -> Unit = { grid ->
            if (grid) {
                MediaCard("Crear playlist", "Nueva lista", onClick = { creating = true }, width = null) {
                    GradientIconCover(
                        Icons.Rounded.Add,
                        listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh),
                        Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 48.dp,
                    )
                }
            } else {
                MediaRow("Crear playlist", "Organiza tu música a tu manera", onClick = { creating = true }) {
                    GradientIconCover(
                        Icons.Rounded.Add,
                        listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh),
                        Modifier.size(56.dp),
                    )
                }
            }
        }
        val menu: @Composable (PlaylistInfo) -> Unit = { info ->
            DropdownMenu(expanded = menuFor == info, onDismissRequest = { menuFor = null }) {
                DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                    renaming = info; menuFor = null
                })
                DropdownMenuItem(text = { Text("Eliminar") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                    deleting = info; menuFor = null
                })
            }
        }

        if (style == LibraryStyle.GRID) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
                item { favoritesItem(true) }
                items(list, key = { it.playlist.id }) { info ->
                    Box {
                        MediaCard(
                            info.playlist.name, songCountLabel(info.songCount),
                            onClick = { ui.navigate(Routes.playlist(info.playlist.id)) },
                            onLongClick = { menuFor = info },
                            width = null,
                        ) {
                            PlaylistCover(
                                info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id,
                                Modifier.fillMaxSize(), RoundedCornerShape(10.dp), 48.dp,
                            )
                        }
                        menu(info)
                    }
                }
                item { createItem(true) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item { favoritesItem(false) }
                items(list, key = { it.playlist.id }) { info ->
                    Box {
                        MediaRow(
                            info.playlist.name, "Playlist • ${songCountLabel(info.songCount)}",
                            onClick = { ui.navigate(Routes.playlist(info.playlist.id)) },
                            onLongClick = { menuFor = info },
                            trailing = {
                                IconButton(onClick = { menuFor = info }) {
                                    Icon(Icons.Rounded.MoreVert, "Opciones", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        ) {
                            PlaylistCover(info.playlist.coverPath, info.firstSongId, info.firstAlbumId, info.playlist.id, Modifier.size(56.dp))
                        }
                        Box(Modifier.align(Alignment.TopEnd)) { menu(info) }
                    }
                }
                item { createItem(false) }
                if (list.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            "Crea tu primera playlist",
                            "Agrupa tus canciones favoritas como quieras. Se guardan en tu teléfono.",
                        )
                    }
                }
            }
        }
    }
}

// ===================== Detalle de playlist =====================

class PlaylistDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val playlist = c.playlists.playlist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val songs = c.playlists.songs(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val player = c.player.state
    val mixEnabled = c.settings.mix.map { it.enabled }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Transiciones personalizadas entre canciones consecutivas (clave: origen→destino). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val transitions = c.playlists.songs(id).flatMapLatest { list ->
        if (list.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap())
        else c.database.mixTransitionDao().observeFrom(list.map { it.id }).map { all -> all.associateBy { it.fromSongId to it.toSongId } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun play(list: List<Song>, index: Int = 0, shuffle: Boolean? = null) {
        val name = playlist.value?.name ?: "Playlist"
        c.player.playSongs(list, index, shuffle, source = name)
    }

    fun rename(name: String) = viewModelScope.launch { c.playlists.rename(id, name) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.playlists.delete(id); onDone() }
    fun setCover(uri: java.io.File?, onResult: (Boolean) -> Unit) = viewModelScope.launch { onResult(c.playlists.setCover(id, uri)) }
    fun remove(songId: Long) = viewModelScope.launch { c.playlists.removeSong(id, songId) }
    fun reorder(ids: List<Long>) = viewModelScope.launch { c.playlists.reorder(id, ids) }
    fun togglePlayback() = c.player.togglePlayPause()
}

@Composable
fun PlaylistDetailScreen(id: Long) {
    val vm = vybraViewModel(key = "playlist$id") { PlaylistDetailViewModel(it, id) }
    val ui = LocalUi.current
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val mixEnabled by vm.mixEnabled.collectAsStateWithLifecycle()
    val transitions by vm.transitions.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    val pickImage = com.vybra.music.platform.rememberOpenFile("Elegir portada", com.vybra.music.platform.IMAGE_EXTENSIONS) { uri ->
        if (uri != null) vm.setCover(uri) { ok -> ui.message(if (ok) "Portada actualizada" else "No se pudo usar la imagen") }
    }

    val p = playlist
    val list = songs ?: emptyList()
    val first = list.firstOrNull()
    val tint by rememberArtworkColor(first?.id ?: -1, first?.albumId ?: -1, MaterialTheme.colorScheme.surfaceContainerHighest)
    val isPlayingHere = player.isPlaying && p != null && player.sourceName == p.name

    if (renaming && p != null) {
        TextInputDialog("Renombrar playlist", p.name, "Guardar", onDismiss = { renaming = false }, onConfirm = {
            vm.rename(it); renaming = false
        })
    }
    if (deleting && p != null) {
        ConfirmDialog(
            "Eliminar playlist",
            "¿Eliminar \"${p.name}\"? Las canciones no se borrarán del dispositivo.",
            "Eliminar",
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; vm.delete { ui.back(); ui.message("Playlist eliminada") } },
        )
    }

    // Lista local para reordenar con arrastre.
    val ordered = remember { mutableStateListOf<Song>() }
    LaunchedEffect(list) { ordered.clear(); ordered.addAll(list) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = ordered.indexOfFirst { it.id == from.key }
        val toIdx = ordered.indexOfFirst { it.id == to.key }
        if (fromIdx >= 0 && toIdx >= 0) ordered.add(toIdx, ordered.removeAt(fromIdx))
    }

    CollectionLayout(
        title = p?.name ?: "",
        tint = tint,
        onBack = ui.back,
        listState = listState,
        topActions = {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, "Opciones", tint = Color.White)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Agregar canciones") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = {
                        menu = false; ui.navigate(Routes.addSongs(id))
                    })
                    DropdownMenuItem(text = { Text("Playlist Mix") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) }, onClick = {
                        menu = false; ui.navigate(Routes.playlistMix(id))
                    })
                    DropdownMenuItem(text = { Text(if (editing) "Terminar de ordenar" else "Ordenar canciones") }, leadingIcon = { Icon(Icons.Rounded.SwapVert, null) }, onClick = {
                        menu = false; editing = !editing
                    })
                    DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                        menu = false; renaming = true
                    })
                    DropdownMenuItem(text = { Text("Cambiar portada") }, leadingIcon = { Icon(Icons.Rounded.Image, null) }, onClick = {
                        menu = false
                        pickImage.launch()
                    })
                    if (p?.coverPath != null) {
                        DropdownMenuItem(text = { Text("Quitar portada") }, leadingIcon = { Icon(Icons.Rounded.HideImage, null) }, onClick = {
                            menu = false; vm.setCover(null) {}
                        })
                    }
                    DropdownMenuItem(text = { Text("Eliminar playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                        menu = false; deleting = true
                    })
                }
            }
        },
    ) {
        if (p == null) return@CollectionLayout
        item(key = "header") {
            DetailHeader(
                tint = tint,
                title = p.name,
                subtitle = null,
                meta = "Playlist • ${songCountLabel(list.size)} • ${formatLongDuration(list.sumOf { it.duration })}",
                onPlay = { if (isPlayingHere) vm.togglePlayback() else vm.play(list) },
                onShuffle = { vm.play(list, shuffle = true) },
                isPlayingHere = isPlayingHere,
                playEnabled = list.isNotEmpty(),
                actions = {
                    IconButton(onClick = { ui.navigate(Routes.addSongs(id)) }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar canciones")
                    }
                    IconButton(onClick = { ui.navigate(Routes.playlistMix(id)) }, enabled = list.size > 1) {
                        Icon(Icons.Rounded.GraphicEq, "Playlist Mix")
                    }
                    IconButton(onClick = { editing = !editing }, enabled = list.size > 1) {
                        Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.SwapVert, if (editing) "Listo" else "Ordenar")
                    }
                },
            ) {
                Box(Modifier.fillMaxSize().clickable {
                    pickImage.launch()
                }) {
                    PlaylistCover(p.coverPath, first?.id, first?.albumId, p.id, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), 72.dp)
                }
            }
        }
        if (songs != null && list.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "Playlist vacía",
                    "Agrega canciones desde tu biblioteca para empezar.",
                    actionLabel = "Agregar canciones",
                    onAction = { ui.navigate(Routes.addSongs(id)) },
                )
            }
        }
        if (editing) {
            item(key = "editHint") {
                Text(
                    "Arrastra ≡ para reordenar. Toca × para quitar una canción.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        itemsIndexed(ordered, key = { _, s -> s.id }) { i, song ->
            ReorderableItem(reorderState, key = song.id, enabled = editing) { dragging ->
                SongRow(
                    song,
                    modifier = if (dragging) Modifier.shadow(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier,
                    onClick = { if (!editing) vm.play(ordered.toList(), i) },
                    onMore = if (editing) null else ({ ui.openSongMenu(song, playlistId = id) }),
                    trailing = if (editing) ({
                        IconButton(onClick = { vm.remove(song.id) }) {
                            Icon(Icons.Rounded.Close, "Quitar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(onDragStopped = { vm.reorder(ordered.map { it.id }) }),
                        ) {
                            Icon(Icons.Rounded.DragHandle, "Reordenar")
                        }
                    }) else null,
                )
            }
            // Transición hacia la siguiente canción (solo con Playlist Mix activado).
            val next = ordered.getOrNull(i + 1)
            if (mixEnabled && !editing && next != null) {
                TransitionChip(
                    label = transitionLabel(transitions[song.id to next.id]),
                    onClick = { ui.navigate(Routes.transitionEditor(song.id, next.id)) },
                )
            }
        }
    }
}

// ===================== Agregar canciones =====================

class AddSongsViewModel(private val c: AppContainer, private val playlistId: Long) : ViewModel() {
    val query = MutableStateFlow("")
    val existing = c.playlists.songs(playlistId).map { list -> list.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val songs = combine(c.music.songs.filterNotNull(), query) { all, q ->
        val norm = MusicRepository.normalize(q.trim())
        val sorted = c.music.sortSongs(all, com.vybra.music.data.prefs.SortOption.TITLE, true)
        if (norm.isEmpty()) sorted
        else sorted.filter { MusicRepository.normalize("${it.title} ${it.artist} ${it.album}").contains(norm) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(ids: List<Long>, onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(c.playlists.addSongs(playlistId, ids))
    }

    suspend fun playlistName(): String = c.playlists.playlist(playlistId).first()?.name ?: "la playlist"
}

@Composable
fun AddSongsScreen(playlistId: Long) {
    val vm = vybraViewModel(key = "add$playlistId") { AddSongsViewModel(it, playlistId) }
    val ui = LocalUi.current
    val query by vm.query.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<Long>() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ui.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                Text("Agregar canciones", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Buscar en tu biblioteca") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(songs ?: emptyList(), key = { it.id }) { song ->
                    val already = song.id in existing
                    val isSel = song.id in selected
                    SongRow(
                        song,
                        onClick = {
                            if (!already) { if (isSel) selected.remove(song.id) else selected.add(song.id) }
                        },
                        trailing = {
                            Icon(
                                when {
                                    already -> Icons.Rounded.CheckCircle
                                    isSel -> Icons.Rounded.CheckCircle
                                    else -> Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = when {
                                    already -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    isSel -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        },
                    )
                }
            }
        }
        if (selected.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.add(selected.toList()) { count ->
                        ui.message(if (count == 1) "1 canción agregada" else "$count canciones agregadas")
                        ui.back()
                    }
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Agregar ${selected.size}") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

/** Píldora entre dos canciones que muestra y abre su transición. */
@Composable
private fun TransitionChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 80.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
    }
}
