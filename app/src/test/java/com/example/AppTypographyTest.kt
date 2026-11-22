package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.CastConnectionState
import com.example.core.common.LibraryAccessState
import com.example.core.common.VideoItem
import com.example.core.common.VideoSourceType
import com.example.core.common.ViewMode
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.LibraryUiState
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.AppFontFamily
import com.example.ui.theme.AppTheme
import com.example.ui.theme.AppThemeTokens
import com.example.ui.theme.AppTypography
import com.example.ui.theme.DefaultAppTypographyTokens
import com.example.ui.theme.LumaCastDarkColorScheme
import com.example.ui.theme.LumaCastLightColorScheme
import com.example.ui.theme.TypographyScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppTypographyTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `all 15 Material 3 typography slots use bundled Inter and follow the scale`() {
        val allSlots = listOf(
            AppTypography.displayLarge,
            AppTypography.displayMedium,
            AppTypography.displaySmall,
            AppTypography.headlineLarge,
            AppTypography.headlineMedium,
            AppTypography.headlineSmall,
            AppTypography.titleLarge,
            AppTypography.titleMedium,
            AppTypography.titleSmall,
            AppTypography.bodyLarge,
            AppTypography.bodyMedium,
            AppTypography.bodySmall,
            AppTypography.labelLarge,
            AppTypography.labelMedium,
            AppTypography.labelSmall
        )

        allSlots.forEach { style ->
            assertEquals(AppFontFamily.Primary, style.fontFamily)
            assertTrue("Line height should exceed font size", style.lineHeight.value > style.fontSize.value)
        }

        // Strict scale ordering checks
        assertEquals(32.sp, AppTypography.displayLarge.fontSize)
        assertEquals(FontWeight.Bold, AppTypography.displayLarge.fontWeight)
        assertEquals(22.sp, AppTypography.headlineMedium.fontSize)
        assertEquals(FontWeight.SemiBold, AppTypography.headlineMedium.fontWeight)
        assertEquals(18.sp, AppTypography.titleLarge.fontSize)
        assertEquals(16.sp, AppTypography.titleMedium.fontSize)
        assertEquals(14.sp, AppTypography.titleSmall.fontSize)
        assertEquals(16.sp, AppTypography.bodyLarge.fontSize)
        assertEquals(14.sp, AppTypography.bodyMedium.fontSize)
        assertEquals(12.sp, AppTypography.bodySmall.fontSize)
        assertEquals(14.sp, AppTypography.labelLarge.fontSize)
        assertEquals(12.sp, AppTypography.labelMedium.fontSize)
        assertEquals(11.sp, AppTypography.labelSmall.fontSize)
    }

    @Test
    fun `all numeric and media telemetry typography tokens enable tabular numerals`() {
        val numericTokens = listOf(
            DefaultAppTypographyTokens.toolbarSubtitle,
            DefaultAppTypographyTokens.videoMetadata,
            DefaultAppTypographyTokens.durationOverlay,
            DefaultAppTypographyTokens.connectionStatus,
            DefaultAppTypographyTokens.playbackTimestamp,
            DefaultAppTypographyTokens.numericMetric,
            DefaultAppTypographyTokens.numericHeadline,
            DefaultAppTypographyTokens.telemetryCompact,
            DefaultAppTypographyTokens.trackMetadata,
            DefaultAppTypographyTokens.badgeLabel
        )

        numericTokens.forEach { token ->
            assertEquals(AppFontFamily.Primary, token.fontFamily)
            assertEquals(TypographyScale.TABULAR_NUMERALS, token.fontFeatureSettings)
        }

        // Video library hierarchy: filename larger & heavier than metadata
        assertTrue(DefaultAppTypographyTokens.videoFilename.fontSize > DefaultAppTypographyTokens.videoMetadata.fontSize)
        assertEquals(FontWeight.Medium, DefaultAppTypographyTokens.videoFilename.fontWeight)
        assertEquals(FontWeight.Normal, DefaultAppTypographyTokens.videoMetadata.fontWeight)
        // Selected track weight stronger than unselected track
        assertEquals(FontWeight.SemiBold, DefaultAppTypographyTokens.trackTitleSelected.fontWeight)
        assertEquals(FontWeight.Medium, DefaultAppTypographyTokens.trackTitle.fontWeight)
    }

    @Test
    fun `AppTheme switches cleanly between dark and light color schemes and provides tokens`() {
        var darkPrimaryObserved = false
        var lightPrimaryObserved = false
        var tokensProvided = false

        composeRule.setContent {
            AppTheme(darkTheme = true) {
                darkPrimaryObserved = MaterialTheme.colorScheme.background == LumaCastDarkColorScheme.background
                tokensProvided = AppThemeTokens.typography.videoFilename == DefaultAppTypographyTokens.videoFilename
            }
            AppTheme(darkTheme = false) {
                lightPrimaryObserved = MaterialTheme.colorScheme.background == LumaCastLightColorScheme.background
            }
        }
        composeRule.waitForIdle()

        assertTrue(darkPrimaryObserved)
        assertTrue(lightPrimaryObserved)
        assertTrue(tokensProvided)
    }

    @Test
    fun `video library renders cleanly in light theme under 1_5x accessibility font scaling`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = VideoThumbnailLoader(context)
        val sampleVideo = VideoItem(
            id = "scale_1",
            uri = Uri.parse("content://media/external/video/media/99"),
            title = "Interstellar_IMAX_Remux",
            displayName = "Interstellar_IMAX_Remux_2160p_HDR10.mkv",
            durationMs = 10_140_000L, // 2:49:00
            sizeBytes = 12_800_000_000L,
            width = 3840,
            height = 2160,
            mimeType = "video/x-matroska",
            extension = "mkv",
            dateAddedSec = 1_700_000_000L,
            dateModifiedSec = 1_700_000_000L,
            folderName = "Movies",
            relativePath = "Movies/",
            orientationDegrees = 0,
            sourceType = VideoSourceType.MEDIA_STORE,
            canRename = true,
            canDelete = true
        )

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 1.5f)
            ) {
                AppTheme(darkTheme = false) {
                    LibraryScreen(
                        state = LibraryUiState(
                            isLoading = false,
                            allVideos = listOf(sampleVideo),
                            filteredVideos = listOf(sampleVideo),
                            availableFolderNames = listOf("Movies"),
                            accessState = LibraryAccessState.ACCESSIBLE,
                            viewMode = ViewMode.LIST
                        ),
                        thumbnailLoader = loader,
                        connectionState = CastConnectionState.DISCONNECTED,
                        connectedDeviceName = null,
                        discoveredDeviceCount = 0,
                        onOpenDrawer = {},
                        onOpenHelp = {},
                        onOpenCastDevices = {},
                        onSearchChange = {},
                        onSortChange = {},
                        onViewModeChange = {},
                        onFolderFilterChange = {},
                        onRefresh = {},
                        onDismissBanner = {},
                        onVideoClick = {},
                        onCastVideo = {},
                        onPlayLocally = {},
                        onShareVideo = {},
                        onOpenContainingFolder = {},
                        onRenameVideo = { _, _ -> },
                        onDeleteVideo = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Media").assertIsDisplayed()
        composeRule.onNodeWithText("Interstellar_IMAX_Remux_2160p_HDR10.mkv").assertIsDisplayed()
        composeRule.onNodeWithText("MKV (Matroska)").assertIsDisplayed()
        composeRule.onNodeWithText("3840 × 2160").assertIsDisplayed()
        composeRule.onNodeWithText("2:49:00").assertIsDisplayed()
    }
}
