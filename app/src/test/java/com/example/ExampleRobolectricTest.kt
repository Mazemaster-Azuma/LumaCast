package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.Formatters
import com.example.core.common.SortOption
import com.example.core.common.VideoItem
import com.example.core.common.VideoSourceType
import com.example.core.media.LocalVideoRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches LumaCast brand`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LumaCast", context.getString(R.string.app_name))
    }

    @Test
    fun `video library repository filters by search and sorts accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalVideoRepository(context)
        val videos = listOf(
            VideoItem(
                id = "1",
                uri = Uri.parse("content://test/1"),
                title = "Interstellar 4K",
                displayName = "Interstellar.mkv",
                durationMs = 10_140_000L,
                sizeBytes = 12_000_000_000L,
                width = 3840,
                height = 2160,
                mimeType = "video/x-matroska",
                extension = "mkv",
                dateAddedSec = 100L,
                folderName = "Movies",
                sourceType = VideoSourceType.SAF_FOLDER
            ),
            VideoItem(
                id = "2",
                uri = Uri.parse("content://test/2"),
                title = "Alpine Documentary",
                displayName = "Alpine.mp4",
                durationMs = 1_800_000L,
                sizeBytes = 1_200_000_000L,
                width = 1920,
                height = 1080,
                mimeType = "video/mp4",
                extension = "mp4",
                dateAddedSec = 300L,
                folderName = "Docs",
                sourceType = VideoSourceType.MEDIA_STORE
            )
        )

        val byName = repo.filterAndSort(videos, query = "", sortOption = SortOption.NAME_ASC)
        assertEquals("Alpine Documentary", byName.first().title)

        val bySize = repo.filterAndSort(videos, query = "", sortOption = SortOption.SIZE_DESC)
        assertEquals("Interstellar 4K", bySize.first().title)

        val searchMkv = repo.filterAndSort(videos, query = "mkv", sortOption = SortOption.DATE_DESC)
        assertEquals(1, searchMkv.size)
        assertEquals("4K UHD", searchMkv.first().resolutionLabel)
    }

    @Test
    fun `formatters produce accurate durations and file sizes`() {
        assertEquals("1:05:09", Formatters.formatDuration((3600 + 300 + 9) * 1000L))
        assertEquals("0:42", Formatters.formatDuration(42_000L))
        assertEquals("4K UHD", Formatters.resolutionTag(3840, 2160))
        assertEquals("1080p", Formatters.resolutionTag(1920, 1080))
    }
}
