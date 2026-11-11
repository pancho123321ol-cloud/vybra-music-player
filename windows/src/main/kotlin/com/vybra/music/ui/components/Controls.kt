package com.vybra.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vybra.music.ui.theme.contentColor

/** Barra de progreso interactiva (tocar o arrastrar para buscar). */
@Composable
fun SeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.onSurface,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
    thumbColor: Color = activeColor,
    trackHeight: Dp = 4.dp,
) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val thumbRadius by animateDpAsState(if (dragging) 8.dp else 6.dp, label = "thumb")
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / width).coerceIn(0f, 1f))
                    onFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onChange((offset.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { dragging = false; onFinished() },
                    onDragCancel = { dragging = false; onFinished() },
                ) { change, _ ->
                    change.consume()
                    onChange((change.position.x / width).coerceIn(0f, 1f))
                }
            }
            .drawBehind {
                val y = size.height / 2
                val stroke = trackHeight.toPx()
                val x = size.width * value.coerceIn(0f, 1f)
                drawLine(inactiveColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(activeColor, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
                drawCircle(thumbColor, thumbRadius.toPx(), Offset(x, y))
            },
    )
}

/** Deslizador vertical usado por las bandas del ecualizador. */
@Composable
fun VerticalSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
) {
    var height by remember { mutableIntStateOf(1) }
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val color = if (enabled) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Box(
        modifier
            .width(36.dp)
            .fillMaxHeight()
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onChange(1f - (offset.y / height).coerceIn(0f, 1f))
                    onFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = { onFinished() },
                    onDragCancel = { onFinished() },
                ) { change, _ ->
                    change.consume()
                    onChange(1f - (change.position.y / height).coerceIn(0f, 1f))
                }
            }
            .drawBehind {
                val x = size.width / 2
                val stroke = 6.dp.toPx()
                val pad = 10.dp.toPx()
                val usable = size.height - pad * 2
                val center = pad + usable / 2
                val y = pad + usable * (1f - value.coerceIn(0f, 1f))
                drawLine(inactiveColor, Offset(x, pad), Offset(x, size.height - pad), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, center), Offset(x, y), stroke, StrokeCap.Round)
                drawCircle(color, 10.dp.toPx(), Offset(x, y))
                drawCircle(Color.White, 4.dp.toPx(), Offset(x, y))
            },
    )
}

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = container.contentColor(),
) {
    Box(
        modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "playPause",
        ) { playing ->
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pausar" else "Reproducir",
                tint = content,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** Estructura de pantallas de colección (álbum, artista, playlist) con barra superior que aparece al desplazar. */
@Composable
fun CollectionLayout(
    title: String,
    tint: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    topActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
        val solid by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 520 }
        }
        val barColor by animateColorAsState(
            if (solid) lerp(tint, background, 0.55f) else Color.Transparent,
            label = "bar",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(barColor)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = if (solid) 0f else 0.25f)),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", tint = Color.White)
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                androidx.compose.animation.AnimatedVisibility(solid, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            topActions()
        }
    }
}

/** Cabecera grande con portada, título y botones de reproducción. */
@Composable
fun DetailHeader(
    tint: Color,
    title: String,
    subtitle: String?,
    meta: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    isPlayingHere: Boolean,
    modifier: Modifier = Modifier,
    coverShape: Shape = RoundedCornerShape(12.dp),
    onSubtitleClick: (() -> Unit)? = null,
    playEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    cover: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(lerp(tint, background, 0.25f), lerp(tint, background, 0.8f), background)))
            .statusBarsPadding()
            .padding(top = 56.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(232.dp)
                .shadow(28.dp, coverShape),
        ) { cover() }
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .then(if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            actions()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShuffle, enabled = playEnabled) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = MaterialTheme.colorScheme.onSurface)
            }
            AnimatedVisibility(playEnabled) {
                PlayPauseButton(isPlaying = isPlayingHere, onClick = onPlay)
            }
        }
    }
}

@Composable
fun FilterChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val bg by animateColorAsState(
                if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "chip",
            )
            val fg = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.CircularProgressIndicator(strokeWidth = 3.dp)
    }
}
