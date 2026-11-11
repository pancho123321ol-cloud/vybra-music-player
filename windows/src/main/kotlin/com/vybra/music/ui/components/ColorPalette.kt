package com.vybra.music.ui.components

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Extracción de colores de una imagen, parecida a androidx.palette: agrupa los píxeles
 * por color y elige los tonos vivos, oscuros y apagados más representativos.
 */
internal class ColorPalette private constructor(swatches: List<Swatch>) {
    class Swatch(val rgb: Int, val population: Int) {
        val hsl: FloatArray = toHsl(rgb)
    }

    val dominantSwatch: Swatch? = swatches.maxByOrNull { it.population }
    private val maxPop = dominantSwatch?.population?.toFloat() ?: 1f
    private val used = HashSet<Swatch>()

    val vibrantSwatch = pick(swatches, 0.35f, 1f, 0.3f, 0.7f, 0.5f)
    val lightVibrantSwatch = pick(swatches, 0.35f, 1f, 0.55f, 1f, 0.74f)
    val darkVibrantSwatch = pick(swatches, 0.35f, 1f, 0f, 0.45f, 0.26f)
    val mutedSwatch = pick(swatches, 0f, 0.4f, 0.3f, 0.7f, 0.5f)
    val darkMutedSwatch = pick(swatches, 0f, 0.4f, 0f, 0.45f, 0.26f)

    private fun pick(list: List<Swatch>, minS: Float, maxS: Float, minL: Float, maxL: Float, targetL: Float): Swatch? {
        val best = list.filter { s ->
            s !in used && s.hsl[1] in minS..maxS && s.hsl[2] in minL..maxL
        }.maxByOrNull { s ->
            val satScore = 1f - abs(s.hsl[1] - (if (minS > 0.3f) 1f else 0.3f))
            val lumScore = 1f - abs(s.hsl[2] - targetL)
            satScore * 3f + lumScore * 6.5f + (s.population / maxPop) * 0.5f
        }
        if (best != null) used += best
        return best
    }

    companion object {
        /** [pixels]: colores ARGB de la imagen (puede ser una versión reducida). */
        fun from(pixels: IntArray, maxColors: Int = 16): ColorPalette {
            val counts = HashMap<Int, Int>()
            for (p in pixels) {
                if ((p ushr 24) < 128) continue
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val hsl = toHsl(p)
                // Se ignoran el blanco y el negro casi puros, como hace Palette.
                if (hsl[2] < 0.05f || hsl[2] > 0.95f) continue
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                counts[key] = (counts[key] ?: 0) + 1
            }
            if (counts.isEmpty()) return ColorPalette(emptyList())
            // Fusión simple: se agrupan cubos vecinos en una cuadrícula más gruesa hasta tener pocos colores.
            var shift = 0
            var groups: Map<Int, List<Pair<Int, Int>>>
            do {
                val s = shift
                groups = counts.entries.groupBy { (k, _) ->
                    val r = (k shr 10) and 31; val g = (k shr 5) and 31; val b = k and 31
                    ((r shr s) shl 10) or ((g shr s) shl 5) or (b shr s)
                }.mapValues { e -> e.value.map { it.key to it.value } }
                shift++
            } while (groups.size > maxColors * 4 && shift < 4)
            val swatches = groups.values.map { members ->
                var pop = 0; var rs = 0L; var gs = 0L; var bs = 0L
                for ((k, c) in members) {
                    pop += c
                    rs += (((k shr 10) and 31) * 8 + 4).toLong() * c
                    gs += (((k shr 5) and 31) * 8 + 4).toLong() * c
                    bs += ((k and 31) * 8 + 4).toLong() * c
                }
                val rgb = (0xFF shl 24) or ((rs / pop).toInt() shl 16) or ((gs / pop).toInt() shl 8) or (bs / pop).toInt()
                Swatch(rgb, pop)
            }.sortedByDescending { it.population }.take(maxColors)
            return ColorPalette(swatches)
        }

        fun toHsl(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) / 2f
            if (mx == mn) return floatArrayOf(0f, 0f, l)
            val d = mx - mn
            val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6 else 0))
                g -> ((b - r) / d + 2)
                else -> ((r - g) / d + 4)
            } * 60f
            return floatArrayOf(h, s, l)
        }
    }
}
