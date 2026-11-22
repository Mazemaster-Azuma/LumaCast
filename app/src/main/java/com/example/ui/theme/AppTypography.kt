package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Full Material Design 3 Typography scale built cohesively on locally bundled Inter.
 * Every text role is explicitly configured with intentional weight, line height,
 * and letter spacing to maintain a refined, commercial-grade visual hierarchy.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Bold,
        fontSize = TypographyScale.DisplayLargeSize,
        lineHeight = TypographyScale.DisplayLargeLineHeight,
        letterSpacing = TypographyScale.DisplayLargeTracking
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypographyScale.DisplayMediumSize,
        lineHeight = TypographyScale.DisplayMediumLineHeight,
        letterSpacing = TypographyScale.DisplayMediumTracking
    ),
    displaySmall = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypographyScale.DisplaySmallSize,
        lineHeight = TypographyScale.DisplaySmallLineHeight,
        letterSpacing = TypographyScale.DisplaySmallTracking
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypographyScale.HeadlineLargeSize,
        lineHeight = TypographyScale.HeadlineLargeLineHeight,
        letterSpacing = TypographyScale.HeadlineLargeTracking
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypographyScale.HeadlineMediumSize,
        lineHeight = TypographyScale.HeadlineMediumLineHeight,
        letterSpacing = TypographyScale.HeadlineMediumTracking
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.HeadlineSmallSize,
        lineHeight = TypographyScale.HeadlineSmallLineHeight,
        letterSpacing = TypographyScale.HeadlineSmallTracking
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypographyScale.TitleLargeSize,
        lineHeight = TypographyScale.TitleLargeLineHeight,
        letterSpacing = TypographyScale.TitleLargeTracking
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.TitleMediumSize,
        lineHeight = TypographyScale.TitleMediumLineHeight,
        letterSpacing = TypographyScale.TitleMediumTracking
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.TitleSmallSize,
        lineHeight = TypographyScale.TitleSmallLineHeight,
        letterSpacing = TypographyScale.TitleSmallTracking
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = TypographyScale.BodyLargeSize,
        lineHeight = TypographyScale.BodyLargeLineHeight,
        letterSpacing = TypographyScale.BodyLargeTracking
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = TypographyScale.BodyMediumSize,
        lineHeight = TypographyScale.BodyMediumLineHeight,
        letterSpacing = TypographyScale.BodyMediumTracking
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Normal,
        fontSize = TypographyScale.BodySmallSize,
        lineHeight = TypographyScale.BodySmallLineHeight,
        letterSpacing = TypographyScale.BodySmallTracking
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.LabelLargeSize,
        lineHeight = TypographyScale.LabelLargeLineHeight,
        letterSpacing = TypographyScale.LabelLargeTracking
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.LabelMediumSize,
        lineHeight = TypographyScale.LabelMediumLineHeight,
        letterSpacing = TypographyScale.LabelMediumTracking
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily.Primary,
        fontWeight = FontWeight.Medium,
        fontSize = TypographyScale.LabelSmallSize,
        lineHeight = TypographyScale.LabelSmallLineHeight,
        letterSpacing = TypographyScale.LabelSmallTracking
    )
)
