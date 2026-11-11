package com.vybra.music.ui.player

import com.vybra.music.ui.common.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.playback.engine.Player
import com.vybra.music.appContainer
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.Routes
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.rememberArtworkColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reproductor a pantalla completa. */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val ui = LocalUi.current
    val container = appContainer
    val connection = container.player
    val state by connection.state.collectAsStateWithLifecycle()
    val np = state.nowPlaying
    LaunchedEffect(np == null) { if (np == null) onClose() }
    if (np == null) return

    val scope = rememberCoroutineScope()
    val isFavorite by remember(np.songId) { container.music.isFavorite(np.songId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val dragOffset = remember { Animatable(0f) }
    val timerLabel = sleepTimerLabel()
    val onBg = Color.White

    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt().coerceAtLeast(0)) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragOffset.value > 260f) {
                                onClose()
                                dragOffset.snapTo(0f)
                            } else dragOffset.animateTo(0f, spring())
                        }
                    },
                    onDragCancel = { scope.launch { dragOffset.animateTo(0f) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(0f)) }
                }
            },
    ) {
        PlayerBackground(np.songId, np.albumId)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cabecera
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", tint = onBg, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "REPRODUCIENDO DESDE",
                        style = MaterialTheme.typography.labelSmall,
                        color = onBg.copy(alpha = 0.7f),
                    )
                    Text(
                        state.sourceName.ifBlank { np.album },
                        style = MaterialTheme.typography.titleSmall,
                        color = onBg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                IconButton(onClick = { ui.showVisuals = true }) {
                    Icon(Icons.Rounded.AutoAwesome, "Fondo del reproductor", tint = onBg)
                }
                IconButton(onClick = {
                    scope.launch { container.music.song(np.songId)?.let { ui.openSongMenu(it) } }
                }) {
                    Icon(Icons.Rounded.MoreVert, "Opciones", tint = onBg)
                }
            }

            MixStatusChip()

            // Carátula
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight - 16.dp)
                val scale by animateFloatAsState(
                    if (state.isPlaying) 1f else 0.9f,
                    spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "artScale",
                )
                AnimatedContent(
                    targetState = np,
                    transitionSpec = {
                        val forward = targetState.songId != initialState.songId
                        if (forward) (fadeIn(tween(260)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(180))
                        else fadeIn() togetherWith fadeOut()
                    },
                    contentKey = { it.songId },
                    label = "artwork",
                ) { item ->
                    Artwork(
                        songId = item.songId,
                        albumId = item.albumId,
                        modifier = Modifier
                            .size(side)
                            .scale(scale)
                            .shadow(32.dp, RoundedCornerShape(14.dp))
                            .pointerInput(Unit) {
                                var dragX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragX = 0f },
                                    onDragEnd = {
                                        if (dragX < -100f) connection.next()
                                        else if (dragX > 100f) connection.previous()
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragX += amount
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        iconSize = 96.dp,
                    )
                }
            }

            // Título, artista y favorito
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        np.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = onBg,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                    )
                    Text(
                        np.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = onBg.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            onClose(); ui.navigate(Routes.artist(np.artist))
                        },
                    )
                }
                IconButton(onClick = { scope.launch { container.music.toggleFavorite(np.songId) } }) {
                    val favScale by animateFloatAsState(if (isFavorite) 1.15f else 1f, spring(0.4f), label = "fav")
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isFavorite) "Quitar de favoritas" else "Añadir a favoritas",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else onBg,
                        modifier = Modifier.size(28.dp).scale(favScale),
                    )
                }
            }

            PlaybackProgress(
                connection = connection,
                isPlaying = state.isPlaying,
                songId = np.songId,
                durationMs = state.durationMs,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                activeColor = onBg,
            )

            // Controles principales
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ModeButton(
                    active = state.shuffle,
                    onClick = connection::toggleShuffle,
                ) { Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = it) }
                IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = onBg, modifier = Modifier.size(40.dp))
                }
                com.vybra.music.ui.components.PlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = connection::togglePlayPause,
                    size = 72.dp,
                    container = onBg,
                    content = Color.Black,
                )
                IconButton(onClick = connection::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Siguiente", tint = onBg, modifier = Modifier.size(40.dp))
                }
                ModeButton(
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = connection::cycleRepeat,
                ) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repetir canción"
                            Player.REPEAT_MODE_ALL -> "Repetir lista"
                            else -> "Repetir desactivado"
                        },
                        tint = it,
                    )
                }
            }

            VolumeControl(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = onBg)

            // Acciones inferiores
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable { ui.showSleepTimer = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Bedtime, "Temporizador",
                        tint = if (timerLabel != null) MaterialTheme.colorScheme.primary else onBg.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp),
                    )
                    if (timerLabel != null) {
                        Text(
                            timerLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp).widthIn(max = 120.dp),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { ui.showLyrics = true }) {
                    Icon(Icons.Rounded.Lyrics, "Letra", tint = onBg.copy(alpha = 0.8f))
                }
                IconButton(onClick = {
                    scope.launch { container.music.song(np.songId)?.let { ui.addToPlaylist = listOf(it) } }
                }) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Agregar a playlist", tint = onBg.copy(alpha = 0.8f))
                }
                IconButton(onClick = { ui.showQueue = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Cola de reproducción", tint = onBg.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
private fun ModeButton(active: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick) { icon(color) }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-2).dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Indica la transición de Playlist Mix preparada o en curso. */
@Composable
private fun MixStatusChip() {
    val container = appContainer
    val engine = container.mixEngine ?: return
    val runtime by engine.runtime.collectAsStateWithLifecycle()
    val text = when (val r = runtime) {
        is com.vybra.music.playback.mix.MixRuntime.Mixing ->
            "Mezclando con ${r.nextTitle} • ${(r.progress * 100).toInt()} %"
        is com.vybra.music.playback.mix.MixRuntime.Ready ->
            "${r.plan.style.label} hacia ${r.nextTitle} en ${com.vybra.music.ui.common.formatDuration(r.plan.exitMs)}"
        else -> null
    } ?: return
    Row(
        Modifier
            .padding(top = 4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
