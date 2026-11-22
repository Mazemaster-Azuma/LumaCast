package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.CastConnectionState
import com.example.core.common.Formatters
import com.example.core.common.ImageItem
import com.example.core.common.LibraryAccessState
import com.example.core.common.MediaLibraryTab
import com.example.core.common.MediaPermissionStatus
import com.example.core.common.SortOption
import com.example.core.common.VideoItem
import com.example.core.common.VideoSourceType
import com.example.core.common.ViewMode
import com.example.core.media.LocalVideoRepository
import com.example.core.media.MediaPermissionHelper
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.LibraryUiState
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.LumaCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalVideoLibraryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleVideos(): List<VideoItem> = listOf(
        VideoItem(
            id = "ms_1",
            uri = Uri.parse("content://media/external/video/media/1"),
            title = "Cinema_4K_Landscape",
            displayName = "Cinema_4K_Landscape.mkv",
            durationMs = 5_400_000L, // 1:30:00
            sizeBytes = 4_200_000_000L,
            width = 3840,
            height = 2160,
            mimeType = "video/x-matroska",
            extension = "mkv",
            dateAddedSec = 1_700_000_100L,
            dateModifiedSec = 1_700_000_100L,
            folderName = "Movies",
            relativePath = "Movies/",
            orientationDegrees = 0,
            sourceType = VideoSourceType.MEDIA_STORE,
            canRename = true,
            canDelete = true
        ),
        VideoItem(
            id = "ms_2",
            uri = Uri.parse("content://media/external/video/media/2"),
            title = "Vertical_Reel",
            displayName = "Vertical_Reel.mp4",
            durationMs = 65_000L, // 1:05
            sizeBytes = 48_000_000L,
            width = 1920,
            height = 1080,
            mimeType = "video/mp4",
            extension = "mp4",
            dateAddedSec = 1_700_000_500L,
            dateModifiedSec = 1_700_000_500L,
            folderName = "Camera",
            relativePath = "DCIM/Camera/",
            orientationDegrees = 90, // Portrait recording stored rotated!
            sourceType = VideoSourceType.MEDIA_STORE,
            canRename = true,
            canDelete = true
        ),
        VideoItem(
            id = "ms_3",
            uri = Uri.parse("content://media/external/video/media/3"),
            title = "Alpha_Clip",
            displayName = "Alpha_Clip.mov",
            durationMs = 300_000L, // 5:00
            sizeBytes = 120_000_000L,
            width = 1280,
            height = 720,
            mimeType = "video/quicktime",
            extension = "mov",
            dateAddedSec = 1_700_000_300L,
            dateModifiedSec = 1_700_000_300L,
            folderName = "WhatsApp Video",
            relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/",
            orientationDegrees = 0,
            sourceType = VideoSourceType.SAF_FOLDER,
            canRename = true,
            canDelete = true
        )
    )

    @Test
    fun `portrait and landscape resolution formatting preserves orientation accurately`() {
        assertEquals("1920 × 1080", Formatters.formatExactResolution(1920, 1080, 0))
        assertEquals("3840 × 2160", Formatters.formatExactResolution(3840, 2160, 180))
        assertEquals("1080 × 1920", Formatters.formatExactResolution(1920, 1080, 90))
        assertEquals("720 × 1280", Formatters.formatExactResolution(1280, 720, 270))
        assertEquals("Resolution unavailable", Formatters.formatExactResolution(0, 0, 0))

        val videos = sampleVideos()
        assertFalse(videos[0].isPortrait)
        assertEquals("3840 × 2160", videos[0].exactResolutionLabel)
        assertTrue(videos[1].isPortrait)
        assertEquals("1080 × 1920", videos[1].exactResolutionLabel)
        assertEquals("1280 × 720", videos[2].exactResolutionLabel)
    }

    @Test
    fun `human readable container formats map cleanly`() {
        assertEquals("MP4", Formatters.humanReadableContainerFormat("mp4", "video/mp4"))
        assertEquals("MKV (Matroska)", Formatters.humanReadableContainerFormat("mkv", "video/x-matroska"))
        assertEquals("MOV (QuickTime)", Formatters.humanReadableContainerFormat("mov", "video/quicktime"))
        assertEquals("WebM", Formatters.humanReadableContainerFormat("webm", "video/webm"))
        assertEquals("MPEG-TS", Formatters.humanReadableContainerFormat("ts", "video/mp2t"))
    }

    @Test
    fun `all six sort modes and search filters work deterministically`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalVideoRepository(context)
        val videos = sampleVideos()

        // 1. Newest first
        val newest = repo.filterAndSort(videos, "", SortOption.DATE_DESC)
        assertEquals(listOf("ms_2", "ms_3", "ms_1"), newest.map { it.id })

        // 2. Oldest first
        val oldest = repo.filterAndSort(videos, "", SortOption.DATE_ASC)
        assertEquals(listOf("ms_1", "ms_3", "ms_2"), oldest.map { it.id })

        // 3. Alphabetical
        val alpha = repo.filterAndSort(videos, "", SortOption.NAME_ASC)
        assertEquals(listOf("ms_3", "ms_1", "ms_2"), alpha.map { it.id })

        // 4. Duration descending
        val byDuration = repo.filterAndSort(videos, "", SortOption.DURATION_DESC)
        assertEquals(listOf("ms_1", "ms_3", "ms_2"), byDuration.map { it.id })

        // 5. File size descending
        val bySize = repo.filterAndSort(videos, "", SortOption.SIZE_DESC)
        assertEquals(listOf("ms_1", "ms_3", "ms_2"), bySize.map { it.id })

        // 6. Resolution descending (4K > 1080x1920 > 1280x720)
        val byRes = repo.filterAndSort(videos, "", SortOption.RESOLUTION_DESC)
        assertEquals(listOf("ms_1", "ms_2", "ms_3"), byRes.map { it.id })

        // Search by filename
        val byFilename = repo.filterAndSort(videos, "Vertical", SortOption.DATE_DESC)
        assertEquals(1, byFilename.size)
        assertEquals("ms_2", byFilename.first().id)

        // Filter by folder
        val byFolder = repo.filterAndSort(videos, "", SortOption.DATE_DESC, folderFilter = "WhatsApp Video")
        assertEquals(1, byFolder.size)
        assertEquals("ms_3", byFolder.first().id)
    }

    @Test
    fun `library access state classifies permission and empty conditions accurately`() {
        assertEquals(
            LibraryAccessState.MEDIA_STORE_UNAVAILABLE,
            LocalVideoRepository.classifyAccessState(
                mediaStoreAccessible = false,
                mediaStoreCount = 0,
                safFolderCount = 0,
                pickedVideoCount = 0,
                totalVideos = 0
            )
        )
        assertEquals(
            LibraryAccessState.EMPTY_NO_SOURCES,
            LocalVideoRepository.classifyAccessState(
                mediaStoreAccessible = true,
                mediaStoreCount = 0,
                safFolderCount = 0,
                pickedVideoCount = 0,
                totalVideos = 0
            )
        )
        assertEquals(
            LibraryAccessState.PARTIAL_ACCESS,
            LocalVideoRepository.classifyAccessState(
                mediaStoreAccessible = true,
                mediaStoreCount = 0,
                safFolderCount = 1,
                pickedVideoCount = 0,
                totalVideos = 3
            )
        )
        assertEquals(
            LibraryAccessState.ACCESSIBLE,
            LocalVideoRepository.classifyAccessState(
                mediaStoreAccessible = true,
                mediaStoreCount = 12,
                safFolderCount = 0,
                pickedVideoCount = 0,
                totalVideos = 12
            )
        )
    }

    @Test
    fun `video thumbnail loader caches by video id and modified timestamp and invalidates cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = VideoThumbnailLoader(context)
        val video = sampleVideos().first()
        val key = loader.cacheKey(video, 320, 180)
        val bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)

        assertNull(loader.getCached(video, 320, 180))
        loader.putForTesting(key, bitmap)
        assertNotNull(loader.getCached(video, 320, 180))

        // Modified timestamp change produces a new cache key automatically
        val modified = video.copy(dateModifiedSec = video.dateModifiedSec + 60L)
        assertNull(loader.getCached(modified, 320, 180))

        // Invalidating video evicts its cached thumbnails
        loader.invalidateVideo(video.id)
        assertNull(loader.getCached(video, 320, 180))
        bitmap.recycle()
    }

    @Test
    fun `video library screen renders toolbar, video metadata rows, and functional overflow menu`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = VideoThumbnailLoader(context)
        val videos = sampleVideos()
        var castClickedId: String? = null
        var detailsClickedId: String? = null
        var helpClicked = false
        var drawerClicked = false

        composeRule.setContent {
            LumaCastTheme {
                LibraryScreen(
                    state = LibraryUiState(
                        isLoading = false,
                        allVideos = videos,
                        filteredVideos = videos,
                        availableFolderNames = listOf("Camera", "Movies", "WhatsApp Video"),
                        accessState = LibraryAccessState.ACCESSIBLE,
                        viewMode = ViewMode.LIST
                    ),
                    thumbnailLoader = loader,
                    connectionState = CastConnectionState.DISCONNECTED,
                    connectedDeviceName = null,
                    discoveredDeviceCount = 1,
                    onOpenDrawer = { drawerClicked = true },
                    onOpenHelp = { helpClicked = true },
                    onOpenCastDevices = {},
                    onSearchChange = {},
                    onSortChange = {},
                    onViewModeChange = {},
                    onFolderFilterChange = {},
                    onRefresh = {},
                    onDismissBanner = {},
                    onVideoClick = { detailsClickedId = it.id },
                    onCastVideo = { castClickedId = it.id },
                    onPlayLocally = {},
                    onShareVideo = {},
                    onOpenContainingFolder = {},
                    onRenameVideo = { _, _ -> },
                    onDeleteVideo = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Top bar elements
        composeRule.onNodeWithText("Media").assertIsDisplayed()
        composeRule.onNodeWithTag("library_add_videos_button").assertDoesNotExist()
        composeRule.onNodeWithTag("library_add_folder_button").assertDoesNotExist()
        composeRule.onNodeWithTag("video_menu_drawer_button").performClick()
        assertTrue(drawerClicked)
        composeRule.onNodeWithTag("video_help_button").performClick()
        assertTrue(helpClicked)

        // Video row metadata: filename, container format, original portrait/landscape resolution, duration
        composeRule.onNodeWithText("Cinema_4K_Landscape.mkv").assertIsDisplayed()
        composeRule.onNodeWithText("MKV (Matroska)").assertIsDisplayed()
        composeRule.onNodeWithText("3840 × 2160").assertIsDisplayed()
        composeRule.onNodeWithText("1:30:00").assertIsDisplayed()

        composeRule.onNodeWithText("Vertical_Reel.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("MP4").assertIsDisplayed()
        composeRule.onNodeWithText("1080 × 1920").assertIsDisplayed()
        composeRule.onNodeWithText("1:05").assertIsDisplayed()

        // Overflow menu -> Cast to TV
        composeRule.onNodeWithTag("video_menu_ms_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("menu_cast_ms_1").performClick()
        assertEquals("ms_1", castClickedId)

        // Tap row -> Video details
        composeRule.onNodeWithTag("video_row_ms_2").performClick()
        assertEquals("ms_2", detailsClickedId)
    }

    private fun sampleImages(): List<ImageItem> = listOf(
        ImageItem(
            id = "img_ms_1",
            uri = Uri.parse("content://media/external/images/media/1"),
            title = "Sunset_HDR",
            displayName = "Sunset_HDR.jpg",
            sizeBytes = 6_400_000L,
            width = 4032,
            height = 3024,
            mimeType = "image/jpeg",
            extension = "jpg",
            dateAddedSec = 1_700_000_200L,
            dateModifiedSec = 1_700_000_200L,
            folderName = "Camera",
            relativePath = "DCIM/Camera/",
            orientationDegrees = 0
        ),
        ImageItem(
            id = "img_ms_2",
            uri = Uri.parse("content://media/external/images/media/2"),
            title = "Portrait_Shot",
            displayName = "Portrait_Shot.png",
            sizeBytes = 2_100_000L,
            width = 2160,
            height = 1080,
            mimeType = "image/png",
            extension = "png",
            dateAddedSec = 1_700_000_600L,
            dateModifiedSec = 1_700_000_600L,
            folderName = "Pictures",
            relativePath = "Pictures/",
            orientationDegrees = 90
        )
    )

    @Test
    fun `media permission helper maps sdk levels and permission states accurately`() {
        val perms30 = MediaPermissionHelper.requiredMediaPermissions(Build.VERSION_CODES.R)
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), perms30.toList())

        val perms33 = MediaPermissionHelper.requiredMediaPermissions(Build.VERSION_CODES.TIRAMISU)
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES),
            perms33.toList()
        )

        val perms34 = MediaPermissionHelper.requiredMediaPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            perms34.toList()
        )

        // Android 14+ evaluation
        assertEquals(
            MediaPermissionStatus.FULL_ACCESS,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                it == Manifest.permission.READ_MEDIA_VIDEO || it == Manifest.permission.READ_MEDIA_IMAGES
            }
        )
        assertEquals(
            MediaPermissionStatus.PARTIAL_ACCESS,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
        )
        assertEquals(
            MediaPermissionStatus.DENIED,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { false }
        )

        // Android 13 evaluation
        assertEquals(
            MediaPermissionStatus.PARTIAL_ACCESS,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.TIRAMISU) {
                it == Manifest.permission.READ_MEDIA_IMAGES
            }
        )

        // Android 11 evaluation
        assertEquals(
            MediaPermissionStatus.FULL_ACCESS,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.R) {
                it == Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
        assertEquals(
            MediaPermissionStatus.DENIED,
            MediaPermissionHelper.evaluateFromCheck(Build.VERSION_CODES.R) { false }
        )
    }

    @Test
    fun `image repository filtering sorting and format helpers work accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalVideoRepository(context)
        val images = sampleImages()

        assertEquals("JPEG", images[0].formatLabel)
        assertEquals("4032 × 3024", images[0].exactResolutionLabel)
        assertEquals("PNG", images[1].formatLabel)
        assertEquals("1080 × 2160", images[1].exactResolutionLabel)

        val newest = repo.filterAndSortImages(images, "", SortOption.DATE_DESC)
        assertEquals(listOf("img_ms_2", "img_ms_1"), newest.map { it.id })

        val bySize = repo.filterAndSortImages(images, "", SortOption.SIZE_DESC)
        assertEquals(listOf("img_ms_1", "img_ms_2"), bySize.map { it.id })

        val byFolder = repo.filterAndSortImages(images, "", SortOption.DATE_DESC, folderFilter = "Camera")
        assertEquals(listOf("img_ms_1"), byFolder.map { it.id })
    }

    @Test
    fun `library screen switches between videos and images tabs and surfaces permission prompts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = VideoThumbnailLoader(context)
        val images = sampleImages()
        var selectedTab: MediaLibraryTab? = null
        var grantClicked = false
        var settingsClicked = false

        composeRule.setContent {
            LumaCastTheme {
                LibraryScreen(
                    state = LibraryUiState(
                        isLoading = false,
                        allVideos = emptyList(),
                        filteredVideos = emptyList(),
                        allImages = images,
                        filteredImages = images,
                        availableImageFolderNames = listOf("Camera", "Pictures"),
                        activeMediaTab = MediaLibraryTab.IMAGES,
                        mediaPermissionStatus = MediaPermissionStatus.PARTIAL_ACCESS,
                        viewMode = ViewMode.LIST
                    ),
                    thumbnailLoader = loader,
                    connectionState = CastConnectionState.CONNECTED,
                    connectedDeviceName = "Living Room TV",
                    discoveredDeviceCount = 1,
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
                    onDeleteVideo = {},
                    onSelectMediaTab = { selectedTab = it },
                    onRequestMediaPermission = { grantClicked = true },
                    onOpenAppSettings = { settingsClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        // Partial access banner + allow all CTA
        composeRule.onNodeWithTag("library_partial_access_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("library_manage_media_permission_button").performClick()
        assertTrue(grantClicked)

        // Images list & preview dialog
        composeRule.onNodeWithText("Sunset_HDR.jpg").assertIsDisplayed()
        composeRule.onNodeWithTag("image_row_img_ms_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("image_preview_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("image_preview_close_button").performClick()

        // Switching tabs
        composeRule.onNodeWithTag("library_tab_videos").performClick()
        assertEquals(MediaLibraryTab.VIDEOS, selectedTab)
        assertFalse(settingsClicked)
    }

    @Test
    fun `library screen empty state displays grant permission and app settings buttons when denied`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = VideoThumbnailLoader(context)
        var grantClicked = false
        var settingsClicked = false

        composeRule.setContent {
            LumaCastTheme {
                LibraryScreen(
                    state = LibraryUiState(
                        isLoading = false,
                        allVideos = emptyList(),
                        filteredVideos = emptyList(),
                        allImages = emptyList(),
                        filteredImages = emptyList(),
                        accessState = LibraryAccessState.MEDIA_STORE_UNAVAILABLE,
                        mediaPermissionStatus = MediaPermissionStatus.DENIED,
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
                    onDeleteVideo = {},
                    onRequestMediaPermission = { grantClicked = true },
                    onOpenAppSettings = { settingsClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library_grant_media_permission_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(grantClicked)
        composeRule.onNodeWithTag("library_open_app_settings_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(settingsClicked)
    }
}
