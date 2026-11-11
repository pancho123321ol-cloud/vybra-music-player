package com.vybra.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.PlatformContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.vybra.music.artwork.ArtworkLoader
import com.vybra.music.artwork.ArtworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

private val PlaceholderGradients = listOf(
    listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
    listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
    listOf(Color(0xFF42275A), Color(0xFF734B6D)),
    listOf(Color(0xFF134E5E), Color(0xFF71B280)),
    listOf(Color(0xFF232526), Color(0xFF414345)),
    listOf(Color(0xFF4B134F), Color(0xFFC94B4B)),
    listOf(Color(0xFF1F1C2C), Color(0xFF928DAB)),
    listOf(Color(0xFF16222A), Color(0xFF3A6073)),
)

fun placeholderBrush(seed: Long): Brush =
    Brush.linearGradient(PlaceholderGradients[(seed % PlaceholderGradients.size).toInt().absoluteValue])

/** Carátula de una canción o álbum con un fondo degradado mientras carga o si no existe. */
@Composable
fun Artwork(
    songId: Long,
    albumId: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    iconSize: Dp = 22.dp,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(placeholderBrush(if (albumId > 0) albumId else songId)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholderIcon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(iconSize),
        )
        if (songId > 0) {
            AsyncImage(
                model = ArtworkRequest(songId, albumId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun PlaylistCover(
    coverPath: String?,
    firstSongId: Long?,
    firstAlbumId: Long?,
    seed: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    iconSize: Dp = 26.dp,
) {
    when {
        coverPath != null -> Box(modifier.clip(shape).background(placeholderBrush(seed))) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        firstSongId != null -> Artwork(firstSongId, firstAlbumId ?: -1, modifier, shape, iconSize)
        else -> Box(
            modifier.clip(shape).background(placeholderBrush(seed)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.QueueMusic, null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun FavoritesCover(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), iconSize: Dp = 26.dp) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF5B2EFF), Color(0xFFB8A6FF), Color(0xFFE4F1EE)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun GradientIconCover(
    icon: ImageVector,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    iconSize: Dp = 26.dp,
) {
    Box(modifier.clip(shape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ArtistAvatar(name: String, songId: Long, albumId: Long, modifier: Modifier = Modifier, iconSize: Dp = 36.dp) {
    Artwork(
        songId = songId,
        albumId = albumId,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        iconSize = iconSize,
        placeholderIcon = Icons.Rounded.Person,
    )
}


/** Colores extraídos de una carátula, del más representativo al menos. */
private val paletteCache = object {
    private val map = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 200
    }
    @Synchronized fun get(key: String): IntArray? = map[key]
    @Synchronized fun put(key: String, value: IntArray) { map[key] = value }
}

private suspend fun loadPalette(context: PlatformContext, songId: Long, albumId: Long): IntArray? {
    val key = ArtworkLoader.key(ArtworkRequest(songId, albumId))
    paletteCache.get(key)?.let { return it }
    if (songId <= 0) return null
    val request = ImageRequest.Builder(context)
        .data(ArtworkRequest(songId, albumId))
        .size(128)
        .memoryCacheKey("palette:$key")
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    val colors = withContext(Dispatchers.Default) {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = bitmap.getColor(x, y)
        val p = ColorPalette.from(pixels, 16)
        listOfNotNull(
            p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch,
            p.dominantSwatch,
            p.darkVibrantSwatch,
            p.mutedSwatch,
            p.lightVibrantSwatch,
            p.darkMutedSwatch,
        ).map { it.rgb }.distinct().toIntArray()
    }
    if (colors.isEmpty()) return null
    paletteCache.put(key, colors)
    return colors
}

/** Color predominante de la carátula, usado para fondos dinámicos. */
@Composable
fun rememberArtworkColor(songId: Long, albumId: Long, fallback: Color): State<Color> {
    val context = LocalPlatformContext.current
    val key = ArtworkLoader.key(ArtworkRequest(songId, albumId))
    var color by remember { mutableStateOf(paletteCache.get(key)?.firstOrNull()?.let { Color(it) } ?: fallback) }
    LaunchedEffect(key, fallback) {
        color = loadPalette(context, songId, albumId)?.firstOrNull()?.let { Color(it) } ?: fallback
    }
    return animateColorAsState(color, tween(600), label = "artColor")
}

/**
 * Paleta de [count] colores de la carátula (se repiten si hay menos), con transición
 * animada al cambiar de canción.
 */
@Composable
fun rememberArtworkPalette(songId: Long, albumId: Long, fallback: List<Color>, count: Int = 4): List<Color> {
    val context = LocalPlatformContext.current
    val key = ArtworkLoader.key(ArtworkRequest(songId, albumId))
    fun expand(src: List<Color>) = List(count) { src[it % src.size] }
    var colors by remember { mutableStateOf(expand(paletteCache.get(key)?.map { Color(it) } ?: fallback)) }
    LaunchedEffect(key, fallback) {
        colors = expand(loadPalette(context, songId, albumId)?.map { Color(it) } ?: fallback)
    }
    return colors.mapIndexed { i, c -> animateColorAsState(c, tween(900), label = "pal$i").value }
}
