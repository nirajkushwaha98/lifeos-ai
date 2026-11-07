package com.lifeos.ai.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object LifeColors {
    val Background = Color(0xFF080B16)
    val BackgroundSecondary = Color(0xFF101729)
    val Card = Color(0xFF151F35)
    val Blue = Color(0xFF3B82F6)
    val Purple = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9CA3AF)
    val Success = Color(0xFF22C55E)
    val Error = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
    val Pink = Color(0xFFEC4899)

    val LightBackground = Color(0xFFF5F7FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightCard = Color(0xFFEEF2FA)
    val LightText = Color(0xFF0F172A)
    val LightTextSecondary = Color(0xFF5B6475)

    /** Stable palette used for chart slices and category accents. */
    val ChartPalette = listOf(
        Blue, Purple, Cyan, Color(0xFFF59E0B), Success,
        Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFF94A3B8), Color(0xFFF97316)
    )
}

/** Extra tokens that Material3's ColorScheme doesn't model (glass surfaces, brand gradients). */
@Immutable
data class LifeExtendedColors(
    val isDark: Boolean,
    val glass: Color,
    val glassBorder: Color,
    val cardBackground: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val brandGradient: Brush,
    val accentGradient: Brush,
    val backgroundGradient: Brush,
)

private val DarkExtended = LifeExtendedColors(
    isDark = true,
    glass = Color(0xFF151F35).copy(alpha = 0.72f),
    glassBorder = Color.White.copy(alpha = 0.08f),
    cardBackground = LifeColors.Card,
    textSecondary = LifeColors.TextSecondary,
    success = LifeColors.Success,
    warning = LifeColors.Warning,
    brandGradient = Brush.linearGradient(listOf(LifeColors.Blue, LifeColors.Purple)),
    accentGradient = Brush.linearGradient(listOf(LifeColors.Cyan, LifeColors.Blue)),
    backgroundGradient = Brush.verticalGradient(listOf(LifeColors.Background, LifeColors.BackgroundSecondary)),
)

private val LightExtended = LifeExtendedColors(
    isDark = false,
    glass = Color.White.copy(alpha = 0.85f),
    glassBorder = Color(0xFF3B82F6).copy(alpha = 0.12f),
    cardBackground = LifeColors.LightSurface,
    textSecondary = LifeColors.LightTextSecondary,
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    brandGradient = Brush.linearGradient(listOf(LifeColors.Blue, LifeColors.Purple)),
    accentGradient = Brush.linearGradient(listOf(Color(0xFF0EA5E9), LifeColors.Blue)),
    backgroundGradient = Brush.verticalGradient(listOf(LifeColors.LightBackground, Color(0xFFE9EEF8))),
)

val LocalLifeColors = staticCompositionLocalOf { DarkExtended }

private val DarkScheme = darkColorScheme(
    primary = LifeColors.Blue,
    onPrimary = Color.White,
    secondary = LifeColors.Purple,
    onSecondary = Color.White,
    tertiary = LifeColors.Cyan,
    onTertiary = Color(0xFF00222A),
    background = LifeColors.Background,
    onBackground = LifeColors.TextPrimary,
    surface = LifeColors.BackgroundSecondary,
    onSurface = LifeColors.TextPrimary,
    surfaceVariant = LifeColors.Card,
    onSurfaceVariant = LifeColors.TextSecondary,
    surfaceContainer = LifeColors.Card,
    surfaceContainerHigh = Color(0xFF1B2742),
    surfaceContainerHighest = Color(0xFF22304F),
    surfaceContainerLow = LifeColors.BackgroundSecondary,
    outline = Color(0xFF2A3654),
    outlineVariant = Color(0xFF1F2A44),
    error = LifeColors.Error,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = LifeColors.Blue,
    onPrimary = Color.White,
    secondary = LifeColors.Purple,
    onSecondary = Color.White,
    tertiary = Color(0xFF0891B2),
    background = LifeColors.LightBackground,
    onBackground = LifeColors.LightText,
    surface = LifeColors.LightSurface,
    onSurface = LifeColors.LightText,
    surfaceVariant = LifeColors.LightCard,
    onSurfaceVariant = LifeColors.LightTextSecondary,
    surfaceContainer = LifeColors.LightCard,
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
)

private val AppTypography = Typography().let { base ->
    val family = FontFamily.SansSerif
    Typography(
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family, fontSize = 11.sp),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun LifeOSTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalLifeColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
