package com.vybra.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vybra.music.R
import com.vybra.music.data.prefs.ThemeMode

/** Colores de acento disponibles para personalizar la app. */
val AccentOptions: List<Pair<String, Int>> = listOf(
    "Frambuesa" to 0xFFFF4D6D.toInt(),
    "Violeta" to 0xFF8B5CF6.toInt(),
    "Océano" to 0xFF3B82F6.toInt(),
    "Cian" to 0xFF06B6D4.toInt(),
    "Menta" to 0xFF10B981.toInt(),
    "Lima" to 0xFF84CC16.toInt(),
    "Ámbar" to 0xFFF59E0B.toInt(),
    "Atardecer" to 0xFFF97316.toInt(),
    "Rosa" to 0xFFEC4899.toInt(),
    "Rubí" to 0xFFEF4444.toInt(),
)

@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val JakartaSans = FontFamily(
    jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800),
)

private val BaseTypography = Typography()

private fun TextStyle.jakarta(weight: FontWeight, letterSpacing: Float? = null) = copy(
    fontFamily = JakartaSans,
    fontWeight = weight,
    letterSpacing = letterSpacing?.sp ?: this.letterSpacing,
)

val VybraTypography = Typography(
    displayLarge = BaseTypography.displayLarge.jakarta(FontWeight.ExtraBold, -1f),
    displayMedium = BaseTypography.displayMedium.jakarta(FontWeight.ExtraBold, -0.8f),
    displaySmall = BaseTypography.displaySmall.jakarta(FontWeight.Bold, -0.6f),
    headlineLarge = BaseTypography.headlineLarge.jakarta(FontWeight.ExtraBold, -0.6f),
    headlineMedium = BaseTypography.headlineMedium.jakarta(FontWeight.ExtraBold, -0.5f),
    headlineSmall = BaseTypography.headlineSmall.jakarta(FontWeight.Bold, -0.3f),
    titleLarge = BaseTypography.titleLarge.jakarta(FontWeight.Bold, -0.2f),
    titleMedium = BaseTypography.titleMedium.jakarta(FontWeight.SemiBold, 0f),
    titleSmall = BaseTypography.titleSmall.jakarta(FontWeight.SemiBold, 0f),
    bodyLarge = BaseTypography.bodyLarge.jakarta(FontWeight.Normal),
    bodyMedium = BaseTypography.bodyMedium.jakarta(FontWeight.Normal),
    bodySmall = BaseTypography.bodySmall.jakarta(FontWeight.Medium),
    labelLarge = BaseTypography.labelLarge.jakarta(FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.jakarta(FontWeight.SemiBold),
    labelSmall = BaseTypography.labelSmall.jakarta(FontWeight.SemiBold, 0.6f),
)

val VybraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

fun Color.contentColor(): Color = if (luminance() > 0.45f) Color(0xFF0A0A0C) else Color.White

private fun darkScheme(accent: Color): ColorScheme {
    val bg = Color(0xFF0A0A0C)
    return darkColorScheme(
        primary = accent,
        onPrimary = accent.contentColor(),
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF16161A)),
        onPrimaryContainer = Color.White,
        secondary = accent,
        onSecondary = accent.contentColor(),
        secondaryContainer = Color(0xFF26262C),
        onSecondaryContainer = Color(0xFFF4F4F6),
        tertiary = accent,
        background = bg,
        onBackground = Color(0xFFF4F4F6),
        surface = bg,
        onSurface = Color(0xFFF4F4F6),
        surfaceVariant = Color(0xFF1E1E23),
        onSurfaceVariant = Color(0xFFA1A1AB),
        surfaceTint = Color.Transparent,
        surfaceBright = Color(0xFF2E2E35),
        surfaceDim = Color(0xFF050506),
        surfaceContainerLowest = Color(0xFF050506),
        surfaceContainerLow = Color(0xFF111114),
        surfaceContainer = Color(0xFF16161A),
        surfaceContainerHigh = Color(0xFF1F1F24),
        surfaceContainerHighest = Color(0xFF2A2A30),
        outline = Color(0xFF3C3C44),
        outlineVariant = Color(0xFF26262C),
        inverseSurface = Color(0xFFF4F4F6),
        inverseOnSurface = Color(0xFF16161A),
        scrim = Color.Black,
    )
}

private fun lightScheme(accent: Color): ColorScheme {
    val bg = Color(0xFFF7F7F9)
    return lightColorScheme(
        primary = accent,
        onPrimary = accent.contentColor(),
        primaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color.White),
        onPrimaryContainer = Color(0xFF16161A),
        secondary = accent,
        onSecondary = accent.contentColor(),
        secondaryContainer = Color(0xFFE6E6EB),
        onSecondaryContainer = Color(0xFF16161A),
        tertiary = accent,
        background = bg,
        onBackground = Color(0xFF111114),
        surface = bg,
        onSurface = Color(0xFF111114),
        surfaceVariant = Color(0xFFE9E9EE),
        onSurfaceVariant = Color(0xFF5F5F6B),
        surfaceTint = Color.Transparent,
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFE2E2E7),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF1F1F4),
        surfaceContainer = Color(0xFFECECF0),
        surfaceContainerHigh = Color(0xFFE6E6EB),
        surfaceContainerHighest = Color(0xFFDFDFE5),
        outline = Color(0xFFC4C4CC),
        outlineVariant = Color(0xFFDCDCE2),
    )
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun VybraTheme(
    themeMode: ThemeMode,
    accent: Color,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val context = LocalContext.current
    val animatedAccent by animateColorAsState(accent, tween(400), label = "accent")
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val base = if (dark) darkScheme(dyn.primary) else lightScheme(dyn.primary)
        base.copy(primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer)
    } else {
        if (dark) darkScheme(animatedAccent) else lightScheme(animatedAccent)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = VybraTypography,
        shapes = VybraShapes,
        content = content,
    )
}
