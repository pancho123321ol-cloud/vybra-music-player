package com.vybra.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vybra.music.data.lyrics.ParsedLyrics
import com.vybra.music.data.lyrics.TimedWord
import com.vybra.music.playback.PlayerConnection
import kotlin.math.abs
import kotlin.math.sin

/**
 * Posición de reproducción que avanza en cada fotograma (no a saltos), corrigiéndose
 * suavemente con la posición real del reproductor cada 250 ms. Solo corre con la pantalla
 * visible y la música sonando; quien la lea en la fase de dibujo solo redibuja, no recompone.
 */
@Composable
fun rememberSmoothPosition(connection: PlayerConnection, isPlaying: Boolean, mediaKey: Any?): State<Long> {
    val position = remember { mutableLongStateOf(connection.currentPosition) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val epoch = connection.state.collectAsStateWithLifecycle().value.positionEpoch
    LaunchedEffect(isPlaying, mediaKey, lifecycle, epoch) {
        position.longValue = connection.currentPosition
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            position.longValue = connection.currentPosition
            if (!isPlaying) return@repeatOnLifecycle
            var estimate = connection.currentPosition.toDouble()
            var last = withFrameNanos { it }
            var lastSync = last
            while (true) {
                val now = withFrameNanos { it }
                estimate += (now - last) / 1e6
                last = now
                if (now - lastSync > 250_000_000L) {
                    val actual = connection.currentPosition.toDouble()
                    val err = actual - estimate
                    // Errores grandes (búsqueda, cambio de velocidad): salto. Pequeños: se corrigen poco a poco.
                    estimate = if (abs(err) > 150) actual else estimate + err * 0.3
                    lastSync = now
                }
                position.longValue = estimate.toLong()
            }
        }
    }
    return position
}

enum class KaraokeLineState { Past, Current, Upcoming }

private val LyricStyleBase = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)

/** Opacidad de las palabras aún no cantadas. */
private const val DIM = 0.38f

/** Opacidad de lo que falta por cantar en la línea actual (algo más visible que el resto). */
private const val DIM_CURRENT = 0.55f

/**
 * Línea de letra con animación palabra por palabra: cada palabra se ilumina de izquierda
 * a derecha mientras se canta (con borde suave), se eleva un poco y, si es una nota larga,
 * crece y brilla levemente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KaraokeLine(
    lyrics: ParsedLyrics,
    index: Int,
    state: KaraokeLineState,
    /** Tiempo actual de la letra (ms), leído solo al dibujar. */
    clock: () -> Long,
    onClick: () -> Unit,
) {
    val words = remember(lyrics, index) { lyrics.wordsAt(index) }
    val line = lyrics.lines[index]
    val current = state == KaraokeLineState.Current
    val alpha by animateFloatAsState(if (state == KaraokeLineState.Past) 0.42f else 1f, tween(450), label = "lineAlpha")
    val scale by animateFloatAsState(if (current) 1f else 0.94f, spring(dampingRatio = 0.75f, stiffness = 180f), label = "lineScale")
    val blur by animateDpAsState(
        if (current) 0.dp else 1.4.dp,
        tween(450), label = "lineBlur",
    )
    val style = LyricStyleBase.copy(fontFamily = MaterialTheme.typography.headlineMedium.fontFamily)
    val density = LocalDensity.current
    val liftPx = with(density) { 3.dp.toPx() }
    val spacePx = with(density) { (style.fontSize.toPx() * 0.27f).toDp() }

    Box(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                // Las líneas ya cantadas quedan "elevadas" como sus palabras, con una sola capa.
                if (state == KaraokeLineState.Past) translationY = -liftPx
            }
            .blur(blur),
    ) {
        if (words.isEmpty()) {
            Text(line.text.ifBlank { "♪" }, style = style, color = Color.White.copy(alpha = if (current) 1f else DIM))
            return@Box
        }
        FlowRow(Modifier.fillMaxWidth()) {
            words.forEach { w ->
                val text = w.text.trimEnd()
                val space = w.text.length != text.length
                val wordMod = Modifier.padding(end = if (space) spacePx else 0.dp)
                when (state) {
                    KaraokeLineState.Current -> KaraokeWord(text, w, style, clock, liftPx, wordMod)
                    // Fuera de la línea actual no se lee el reloj: no se redibujan en cada fotograma.
                    KaraokeLineState.Past -> Text(text, style = style, color = Color.White, modifier = wordMod)
                    KaraokeLineState.Upcoming -> Text(text, style = style, color = Color.White.copy(alpha = DIM), modifier = wordMod)
                }
            }
        }
    }
}

@Composable
private fun KaraokeWord(text: String, w: TimedWord, style: TextStyle, clock: () -> Long, liftPx: Float, modifier: Modifier) {
    val duration = (w.endMs - w.startMs).coerceAtLeast(1)
    val longNote = duration >= 900
    fun progress(): Float = ((clock() - w.startMs).toFloat() / duration).coerceIn(0f, 1f)
    Text(
        text,
        style = style,
        color = Color.White,
        modifier = modifier
            .graphicsLayer {
                val p = progress()
                // Sube al empezar a cantarse y se queda arriba.
                val rise = FastOutSlowInEasing.transform((p * 2.5f).coerceAtMost(1f))
                translationY = -liftPx * rise
                if (longNote) {
                    // Notas largas: crece un poco mientras se sostiene y vuelve al terminar.
                    val swell = sin(p * Math.PI).toFloat() * 0.06f
                    scaleX = 1f + swell
                    scaleY = 1f + swell
                    transformOrigin = TransformOrigin(0.5f, 0.6f)
                }
                // Solo la palabra que se está cantando necesita capa propia (para el barrido);
                // las demás se dibujan con una opacidad simple, mucho más barato.
                when {
                    p <= 0f -> { alpha = DIM_CURRENT; compositingStrategy = CompositingStrategy.Auto }
                    p >= 1f -> { alpha = 1f; compositingStrategy = CompositingStrategy.Auto }
                    else -> { alpha = 1f; compositingStrategy = CompositingStrategy.Offscreen }
                }
            }
            .drawWithContent {
                drawContent()
                val p = progress()
                if (p <= 0f || p >= 1f) return@drawWithContent
                // Barrido con borde suave: lo cantado opaco, lo pendiente atenuado.
                val edge = (size.width * 0.35f).coerceAtLeast(18f)
                val x = p * (size.width + edge) - edge
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.White, Color.White.copy(alpha = DIM_CURRENT)),
                        startX = x, endX = x + edge,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** Puntos que "respiran" durante una introducción o un tramo instrumental, y se llenan hasta la próxima línea. */
@Composable
fun InterludeDots(fromMs: Long, toMs: Long, clock: () -> Long) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse.animateTo(1f, tween(1100))
            pulse.animateTo(0f, tween(1100))
        }
    }
    Row(Modifier.height(56.dp).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        val span = (toMs - fromMs).coerceAtLeast(1)
                        val p = ((clock() - fromMs).toFloat() / span).coerceIn(0f, 1f)
                        // Cada punto se enciende al pasar su tercio del tramo.
                        val fill = ((p * 3f) - i).coerceIn(0f, 1f)
                        alpha = 0.3f + 0.7f * fill
                        val s = 0.85f + 0.15f * pulse.value
                        scaleX = s; scaleY = s
                    }
                    .background(Color.White, CircleShape),
            )
        }
    }
}
