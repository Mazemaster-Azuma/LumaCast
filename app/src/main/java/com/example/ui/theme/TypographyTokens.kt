package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for numeric typography scale constants and OpenType features.
 * All sizes use `sp` to guarantee full Android system font-scale accessibility compliance.
 */
object TypographyScale {
    const val TABULAR_NUMERALS = "tnum"

    // Display
    val DisplayLargeSize = 32.sp
    val DisplayLargeLineHeight = 38.sp
    val DisplayLargeTracking = (-0.4).sp

    val DisplayMediumSize = 28.sp
    val DisplayMediumLineHeight = 34.sp
    val DisplayMediumTracking = (-0.3).sp

    val DisplaySmallSize = 24.sp
    val DisplaySmallLineHeight = 30.sp
    val DisplaySmallTracking = (-0.2).sp

    // Headlines
    val HeadlineLargeSize = 24.sp
    val HeadlineLargeLineHeight = 30.sp
    val HeadlineLargeTracking = (-0.2).sp

    val HeadlineMediumSize = 22.sp
    val HeadlineMediumLineHeight = 28.sp
    val HeadlineMediumTracking = (-0.2).sp

    val HeadlineSmallSize = 20.sp
    val HeadlineSmallLineHeight = 26.sp
    val HeadlineSmallTracking = (-0.15).sp

    // Titles
    val TitleLargeSize = 18.sp
    val TitleLargeLineHeight = 24.sp
    val TitleLargeTracking = (-0.1).sp

    val TitleMediumSize = 16.sp
    val TitleMediumLineHeight = 22.sp
    val TitleMediumTracking = 0.sp

    val TitleSmallSize = 14.sp
    val TitleSmallLineHeight = 20.sp
    val TitleSmallTracking = 0.sp

    // Body
    val BodyLargeSize = 16.sp
    val BodyLargeLineHeight = 24.sp
    val BodyLargeTracking = 0.sp

    val BodyMediumSize = 14.sp
    val BodyMediumLineHeight = 20.sp
    val BodyMediumTracking = 0.sp

    val BodySmallSize = 12.sp
    val BodySmallLineHeight = 17.sp
    val BodySmallTracking = 0.1.sp

    // Labels
    val LabelLargeSize = 14.sp
    val LabelLargeLineHeight = 20.sp
    val LabelLargeTracking = 0.05.sp

    val LabelMediumSize = 12.sp
    val LabelMediumLineHeight = 16.sp
    val LabelMediumTracking = 0.15.sp

    val LabelSmallSize = 11.sp
    val LabelSmallLineHeight = 15.sp
    val LabelSmallTracking = 0.2.sp
}

/**
 * Semantic media-application typography tokens layered cleanly on top of Material 3.
 * Dynamic numeric fields (durations, timestamps, bitrates, file sizes, percentages,
 * resolutions) explicitly enable tabular numerals (`tnum`) to prevent layout jitter.
 */
@Immutable
data class AppTypographyTokens(
    val toolbarTitle: TextStyle,
    val toolbarSubtitle: TextStyle,
    val videoFilename: TextStyle,
    val videoMetadata: TextStyle,
    val durationOverlay: TextStyle,
    val deviceName: TextStyle,
    val connectionStatus: TextStyle,
    val mediaTitle: TextStyle,
    val playbackTimestamp: TextStyle,
    val numericMetric: TextStyle,
    val numericHeadline: TextStyle,
    val telemetryCompact: TextStyle,
    val trackTitle: TextStyle,
    val trackTitleSelected: TextStyle,
    val trackMetadata: TextStyle,
    val sectionHeading: TextStyle,
    val statusMessage: TextStyle,
    val badgeLabel: TextStyle,
    val dialogTitle: TextStyle
)

val DefaultAppTypographyTokens = AppTypographyTokens(
    toolbarTitle = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp
    ),
    toolbarSubtitle = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    videoFilename = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp
    ),
    videoMetadata = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    durationOverlay = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    deviceName = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    connectionStatus = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    mediaTitle = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    playbackTimestamp = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    numericMetric = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    numericHeadline = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    telemetryCompact = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    trackTitle = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.05).sp
    ),
    trackTitleSelected = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.05).sp
    ),
    trackMetadata = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    sectionHeading = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    statusMessage = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    badgeLabel = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = TypographyScale.TABULAR_NUMERALS
    ),
    dialogTitle = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp
    )
)

val LocalAppTypographyTokens = staticCompositionLocalOf { DefaultAppTypographyTokens }
