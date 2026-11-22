package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Semantic status & media accent colors that adapt automatically to Dark and Light themes
 * while maintaining WCAG-AA+ contrast for text and badges.
 */
@Immutable
data class AppSemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val transcode: Color,
    val transcodeContainer: Color,
    val scrimDark: Color,
    val onScrimDark: Color
)

private val DarkSemanticColors = AppSemanticColors(
    success = DirectCastEmerald,
    successContainer = DirectCastEmeraldBg,
    warning = RemuxAmber,
    warningContainer = RemuxAmberBg,
    transcode = TranscodeViolet,
    transcodeContainer = TranscodeVioletBg,
    scrimDark = ObsidianCanvas,
    onScrimDark = Color.White
)

private val LightSemanticColors = AppSemanticColors(
    success = LightDirectCastEmerald,
    successContainer = LightDirectCastEmeraldBg,
    warning = LightRemuxAmber,
    warningContainer = LightRemuxAmberBg,
    transcode = LightTranscodeViolet,
    transcodeContainer = LightTranscodeVioletBg,
    scrimDark = Color(0xFF0A0D14),
    onScrimDark = Color.White
)

val LocalAppSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

val LumaCastDarkColorScheme = darkColorScheme(
    primary = CinemaGold,
    onPrimary = OnCinemaGold,
    primaryContainer = CinemaGoldContainer,
    onPrimaryContainer = OnCinemaGoldContainer,
    secondary = CastCyan,
    onSecondary = OnCastCyan,
    secondaryContainer = CastCyanContainer,
    onSecondaryContainer = OnCastCyanContainer,
    tertiary = DirectCastEmerald,
    onTertiary = OnCastCyan,
    tertiaryContainer = DirectCastEmeraldBg,
    onTertiaryContainer = DirectCastEmerald,
    background = ObsidianCanvas,
    onBackground = InkPrimary,
    surface = ObsidianSurface,
    onSurface = InkPrimary,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurfaceContainerLow,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianSurfaceContainerHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = SignalCoral,
    onError = OnSignalCoral,
    errorContainer = SignalCoralBg,
    onErrorContainer = SignalCoral
)

val LumaCastLightColorScheme = lightColorScheme(
    primary = LightCinemaGold,
    onPrimary = LightOnCinemaGold,
    primaryContainer = LightCinemaGoldContainer,
    onPrimaryContainer = LightOnCinemaGoldContainer,
    secondary = LightCastCyan,
    onSecondary = LightOnCastCyan,
    secondaryContainer = LightCastCyanContainer,
    onSecondaryContainer = LightOnCastCyanContainer,
    tertiary = LightDirectCastEmerald,
    onTertiary = Color.White,
    tertiaryContainer = LightDirectCastEmeraldBg,
    onTertiaryContainer = LightDirectCastEmerald,
    background = LightCanvas,
    onBackground = LightInkPrimary,
    surface = LightSurface,
    onSurface = LightInkPrimary,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightInkSecondary,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightSignalCoral,
    onError = LightOnSignalCoral,
    errorContainer = LightSignalCoralBg,
    onErrorContainer = LightSignalCoral
)

val LumaCastShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object AppThemeTokens {
    val typography: AppTypographyTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTypographyTokens.current

    val semanticColors: AppSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppSemanticColors.current
}

/**
 * Centralized Material 3 application theme supporting both Dark and Light modes
 * with high-contrast text hierarchy and the bundled Inter typography system.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LumaCastDarkColorScheme else LumaCastLightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(
        LocalAppTypographyTokens provides DefaultAppTypographyTokens,
        LocalAppSemanticColors provides semanticColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = LumaCastShapes,
            content = content
        )
    }
}
