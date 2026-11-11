package com.vybra.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vybra.music.appContainer
import com.vybra.music.playback.PlayerUiState
import com.vybra.music.ui.LocalUi
import com.vybra.music.ui.components.Artwork
import com.vybra.music.ui.components.rememberArtworkColor
import kotlinx.coroutines.launch

/** Reproductor miniatura permanente sobre la barra de navegación. */
@Composable
fun MiniPlayer(state: PlayerUiState, modifier: Modifier = Modifier) {
    val np = state.nowPlaying ?: return
    val ui = LocalUi.current
    val container = appContainer
    val connection = container.player
    val scope = rememberCoroutineScope()
    val isFavorite by remember(np.songId) { container.music.isFavorite(np.songId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val artColor by rememberArtworkColor(np.songId, np.albumId, surface)
    val background = lerp(artColor, surface, 0.62f)
    val position by rememberPlaybackPosition(connection, state.isPlaying, np.songId)
    val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val accent = MaterialTheme.colorScheme.onSurface

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable { ui.showNowPlaying = true }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -40f) ui.showNowPlaying = true },
                ) { _, dragAmount -> total += dragAmount }
            }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -120f) connection.next()
                        else if (total > 120f) connection.previous()
                    },
                ) { _, dragAmount -> total += dragAmount }
            }
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                val stroke = 2.dp.toPx()
                val inset = 8.dp.toPx()
                val w = size.width - inset * 2
                drawLine(accent.copy(alpha = 0.2f), Offset(inset, y), Offset(inset + w, y), stroke)
                drawLine(accent, Offset(inset, y), Offset(inset + w * progress, y), stroke)
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(np.songId, np.albumId, Modifier.size(42.dp), RoundedCornerShape(6.dp), 18.dp)
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                targetState = np,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "miniTitle",
            ) { item ->
                Column {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        item.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { scope.launch { container.music.toggleFavorite(np.songId) } }) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorita",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = connection::togglePlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = connection::next) {
                Icon(Icons.Rounded.SkipNext, "Siguiente")
            }
        }
    }
}
