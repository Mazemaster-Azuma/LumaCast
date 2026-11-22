package com.example.core.media

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.core.common.ImageItem
import com.example.core.common.IndexedFolder
import com.example.core.common.LibraryAccessState
import com.example.core.common.SortOption
import com.example.core.common.VideoItem
import com.example.core.common.VideoSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class LibraryScanResult(
    val videos: List<VideoItem>,
    val images: List<ImageItem> = emptyList(),
    val folders: List<IndexedFolder>,
    val mediaStoreAccessible: Boolean,
    val accessState: LibraryAccessState = LibraryAccessState.ACCESSIBLE,
    val revokedFolderUris: List<String> = emptyList(),
    val missingPickedVideoUris: List<String> = emptyList(),
    val mediaStoreVideoCount: Int = 0,
    val mediaStoreImageCount: Int = 0
)

sealed interface MediaMutationResult {
    data class Success(val message: String) : MediaMutationResult
    data class RemovedReferenceOnly(val uriString: String, val message: String) : MediaMutationResult
    data class NeedsUserConsent(val intentSender: IntentSender, val actionLabel: String) : MediaMutationResult
    data class NotPermitted(val reason: String) : MediaMutationResult
    data class Failed(val reason: String) : MediaMutationResult
}

/**
 * Production Android Local Video Library repository.
 *
 * Discovery Architecture:
 * - Automatically queries `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` (retrieving DISPLAY_NAME,
 *   CONTENT_URI, DURATION, WIDTH, HEIGHT, SIZE, MIME_TYPE, DATE_ADDED, DATE_MODIFIED,
 *   RELATIVE_PATH, BUCKET_DISPLAY_NAME, and ORIENTATION where available) without hardcoding paths.
 * - Indexes user-authorized folders via Android Storage Access Framework (`OpenDocumentTree`).
 * - Resolves individually selected videos from Android's zero-permission Photo Picker.
 * - Observes live `MediaStore` additions, renames, and deletions via `ContentObserver`.
 */
class LocalVideoRepository(private val context: Context) {

    fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val videoRegistered = runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            true
        }.getOrDefault(false)
        val imageRegistered = runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            true
        }.getOrDefault(false)

        awaitClose {
            if (videoRegistered || imageRegistered) {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }

    suspend fun scanLibrary(
        safFolderUris: Set<String>,
        pickedVideoUris: Set<String>
    ): LibraryScanResult = withContext(Dispatchers.IO) {
        val discovered = LinkedHashMap<String, VideoItem>()
        val dedupeNameSizeKeys = HashSet<String>()
        val discoveredImages = LinkedHashMap<String, ImageItem>()
        val dedupeImageKeys = HashSet<String>()
        val folders = mutableListOf<IndexedFolder>()
        val revokedFolders = mutableListOf<String>()
        val missingPicked = mutableListOf<String>()

        // 1. Query MediaStore.Video.Media and MediaStore.Images.Media collections
        var videoStoreAccessible = true
        var imageStoreAccessible = true
        var mediaStoreCount = 0
        var mediaStoreImageCount = 0
        try {
            val msVideos = queryMediaStoreVideos()
            mediaStoreCount = msVideos.size
            msVideos.forEach { item ->
                discovered[item.uri.toString()] = item
                if (item.sizeBytes > 0L) {
                    dedupeNameSizeKeys.add(dedupeKey(item.displayName, item.sizeBytes))
                }
            }
        } catch (_: SecurityException) {
            videoStoreAccessible = false
        } catch (_: Exception) {
            videoStoreAccessible = false
        }

        try {
            val msImages = queryMediaStoreImages()
            mediaStoreImageCount = msImages.size
            msImages.forEach { item ->
                discoveredImages[item.uri.toString()] = item
                if (item.sizeBytes > 0L) {
                    dedupeImageKeys.add(dedupeKey(item.displayName, item.sizeBytes))
                }
            }
        } catch (_: SecurityException) {
            imageStoreAccessible = false
        } catch (_: Exception) {
            imageStoreAccessible = false
        }

        val mediaStoreAccessible = videoStoreAccessible || imageStoreAccessible

        // 2. Scan persisted Storage Access Framework (SAF) folders (both videos and images)
        safFolderUris.forEach { uriStr ->
            val treeUri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@forEach
            val docTree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            if (docTree == null || !docTree.exists() || !docTree.canRead()) {
                revokedFolders += uriStr
                return@forEach
            }
            val folderName = docTree.name?.takeIf { it.isNotBlank() } ?: "Selected Folder"
            val folderVideos = mutableListOf<VideoItem>()
            val folderImages = mutableListOf<ImageItem>()
            scanDocumentTreeRecursive(
                directory = docTree,
                rootTreeUri = treeUri,
                rootFolderName = folderName,
                relativeSubPath = folderName,
                depth = 0,
                maxDepth = 5,
                outVideos = folderVideos,
                outImages = folderImages
            )
            folderVideos.forEach { item ->
                val dupKey = dedupeKey(item.displayName, item.sizeBytes)
                if (!discovered.containsKey(item.uri.toString()) &&
                    (item.sizeBytes <= 0L || !dedupeNameSizeKeys.contains(dupKey))
                ) {
                    discovered[item.uri.toString()] = item
                    if (item.sizeBytes > 0L) dedupeNameSizeKeys.add(dupKey)
                }
            }
            folderImages.forEach { item ->
                val dupKey = dedupeKey(item.displayName, item.sizeBytes)
                if (!discoveredImages.containsKey(item.uri.toString()) &&
                    (item.sizeBytes <= 0L || !dedupeImageKeys.contains(dupKey))
                ) {
                    discoveredImages[item.uri.toString()] = item
                    if (item.sizeBytes > 0L) dedupeImageKeys.add(dupKey)
                }
            }
            folders += IndexedFolder(
                treeUri = treeUri,
                displayName = folderName,
                videoCount = folderVideos.size
            )
        }

        // 3. Resolve individually picked videos from Android Photo Picker / OpenDocument
        pickedVideoUris.forEach { uriStr ->
            if (discovered.containsKey(uriStr)) return@forEach
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@forEach
            val item = resolveSingleVideoUri(uri, "Picked Videos", VideoSourceType.PHOTO_PICKER)
            if (item != null) {
                val dupKey = dedupeKey(item.displayName, item.sizeBytes)
                if (item.sizeBytes <= 0L || !dedupeNameSizeKeys.contains(dupKey)) {
                    discovered[uriStr] = item
                    if (item.sizeBytes > 0L) dedupeNameSizeKeys.add(dupKey)
                }
            } else {
                missingPicked += uriStr
            }
        }

        val allVideos = discovered.values.toList()
        val allImages = discoveredImages.values.toList()
        val accessState = classifyAccessState(
            mediaStoreAccessible = mediaStoreAccessible,
            mediaStoreCount = mediaStoreCount + mediaStoreImageCount,
            safFolderCount = folders.size,
            pickedVideoCount = pickedVideoUris.size - missingPicked.size,
            totalVideos = allVideos.size + allImages.size
        )

        LibraryScanResult(
            videos = allVideos,
            images = allImages,
            folders = folders.sortedBy { it.displayName.lowercase(Locale.US) },
            mediaStoreAccessible = mediaStoreAccessible,
            accessState = accessState,
            revokedFolderUris = revokedFolders,
            missingPickedVideoUris = missingPicked,
            mediaStoreVideoCount = mediaStoreCount,
            mediaStoreImageCount = mediaStoreImageCount
        )
    }

    fun filterAndSort(
        videos: List<VideoItem>,
        query: String,
        sortOption: SortOption,
        folderFilter: String? = null
    ): List<VideoItem> {
        val trimmed = query.trim().lowercase(Locale.US)
        val filtered = videos.filter { item ->
            val matchesFolder = folderFilter == null || item.folderName.equals(folderFilter, ignoreCase = true)
            val matchesQuery = trimmed.isEmpty() ||
                item.displayName.lowercase(Locale.US).contains(trimmed) ||
                item.title.lowercase(Locale.US).contains(trimmed) ||
                item.folderName.lowercase(Locale.US).contains(trimmed) ||
                item.relativePath?.lowercase(Locale.US)?.contains(trimmed) == true ||
                item.containerLabel.lowercase(Locale.US).contains(trimmed) ||
                item.humanReadableFormat.lowercase(Locale.US).contains(trimmed) ||
                item.resolutionLabel.lowercase(Locale.US).contains(trimmed) ||
                item.exactResolutionLabel.lowercase(Locale.US).contains(trimmed)
            matchesFolder && matchesQuery
        }

        return when (sortOption) {
            SortOption.DATE_DESC -> filtered.sortedWith(
                compareByDescending<VideoItem> { maxOf(it.dateAddedSec, it.dateModifiedSec) }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.DATE_ASC -> filtered.sortedWith(
                compareBy<VideoItem> { maxOf(it.dateAddedSec, it.dateModifiedSec) }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase(Locale.US) }
            SortOption.DURATION_DESC -> filtered.sortedWith(
                compareByDescending<VideoItem> { it.durationMs }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.SIZE_DESC -> filtered.sortedWith(
                compareByDescending<VideoItem> { it.sizeBytes }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.RESOLUTION_DESC -> filtered.sortedWith(
                compareByDescending<VideoItem> { it.pixelCount }
                    .thenByDescending { it.effectiveWidth }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
        }
    }

    fun filterAndSortImages(
        images: List<ImageItem>,
        query: String,
        sortOption: SortOption,
        folderFilter: String? = null
    ): List<ImageItem> {
        val trimmed = query.trim().lowercase(Locale.US)
        val filtered = images.filter { item ->
            val matchesFolder = folderFilter == null || item.folderName.equals(folderFilter, ignoreCase = true)
            val matchesQuery = trimmed.isEmpty() ||
                item.displayName.lowercase(Locale.US).contains(trimmed) ||
                item.title.lowercase(Locale.US).contains(trimmed) ||
                item.folderName.lowercase(Locale.US).contains(trimmed) ||
                item.relativePath?.lowercase(Locale.US)?.contains(trimmed) == true ||
                item.formatLabel.lowercase(Locale.US).contains(trimmed) ||
                item.exactResolutionLabel.lowercase(Locale.US).contains(trimmed)
            matchesFolder && matchesQuery
        }

        return when (sortOption) {
            SortOption.DATE_DESC, SortOption.DURATION_DESC -> filtered.sortedWith(
                compareByDescending<ImageItem> { maxOf(it.dateAddedSec, it.dateModifiedSec) }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.DATE_ASC -> filtered.sortedWith(
                compareBy<ImageItem> { maxOf(it.dateAddedSec, it.dateModifiedSec) }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase(Locale.US) }
            SortOption.SIZE_DESC -> filtered.sortedWith(
                compareByDescending<ImageItem> { it.sizeBytes }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
            SortOption.RESOLUTION_DESC -> filtered.sortedWith(
                compareByDescending<ImageItem> { it.pixelCount }
                    .thenByDescending { it.effectiveWidth }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
        }
    }

    suspend fun verifyUriAccessible(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Renames a local video where permitted by Android Scoped Storage / SAF.
     * Preserves the original file extension cleanly if the user omits it.
     */
    suspend fun renameVideo(video: VideoItem, requestedName: String): MediaMutationResult = withContext(Dispatchers.IO) {
        val sanitizedBase = requestedName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "")
        if (sanitizedBase.isBlank()) {
            return@withContext MediaMutationResult.Failed("Please enter a valid video name.")
        }
        val finalName = if (video.extension.isNotBlank() &&
            !sanitizedBase.lowercase(Locale.US).endsWith(".${video.extension.lowercase(Locale.US)}")
        ) {
            "$sanitizedBase.${video.extension}"
        } else {
            sanitizedBase
        }

        when (video.sourceType) {
            VideoSourceType.SAF_FOLDER -> {
                val doc = runCatching { DocumentFile.fromSingleUri(context, video.uri) }.getOrNull()
                if (doc == null || !doc.exists()) {
                    return@withContext MediaMutationResult.Failed("Video file no longer exists.")
                }
                if (!doc.canWrite()) {
                    return@withContext MediaMutationResult.NotPermitted("This folder is read-only. Re-authorize folder write access to rename.")
                }
                val renamed = runCatching { doc.renameTo(finalName) }.getOrDefault(false)
                if (renamed) {
                    MediaMutationResult.Success("Renamed to $finalName")
                } else {
                    MediaMutationResult.Failed("Could not rename video. A file with that name may already exist.")
                }
            }

            VideoSourceType.MEDIA_STORE -> {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                    }
                    val updated = context.contentResolver.update(video.uri, values, null, null)
                    if (updated > 0) {
                        MediaMutationResult.Success("Renamed to $finalName")
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = MediaStore.createWriteRequest(context.contentResolver, listOf(video.uri))
                        MediaMutationResult.NeedsUserConsent(pi.intentSender, "Allow rename")
                    } else {
                        MediaMutationResult.NotPermitted("Android storage permissions prevented renaming this file.")
                    }
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        MediaMutationResult.NeedsUserConsent(e.userAction.actionIntent.intentSender, "Allow rename")
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = runCatching {
                            MediaStore.createWriteRequest(context.contentResolver, listOf(video.uri))
                        }.getOrNull()
                        if (pi != null) {
                            MediaMutationResult.NeedsUserConsent(pi.intentSender, "Allow rename")
                        } else {
                            MediaMutationResult.NotPermitted("Renaming this MediaStore item requires system write approval.")
                        }
                    } else {
                        MediaMutationResult.NotPermitted("Renaming is not permitted for this file.")
                    }
                } catch (e: Exception) {
                    MediaMutationResult.Failed(e.message ?: "Failed to rename video.")
                }
            }

            VideoSourceType.PHOTO_PICKER -> {
                MediaMutationResult.NotPermitted("Videos added via Android Photo Picker are read-only and cannot be renamed here.")
            }
        }
    }

    /**
     * Deletes a local video where permitted (or removes the read-only Photo Picker reference).
     * Uses Android 11+ `MediaStore.createDeleteRequest` for system-verified deletion when needed.
     */
    suspend fun deleteVideo(video: VideoItem): MediaMutationResult = withContext(Dispatchers.IO) {
        when (video.sourceType) {
            VideoSourceType.PHOTO_PICKER -> {
                MediaMutationResult.RemovedReferenceOnly(
                    uriString = video.uri.toString(),
                    message = "Removed ${video.displayName} from LumaCast library."
                )
            }

            VideoSourceType.SAF_FOLDER -> {
                val doc = runCatching { DocumentFile.fromSingleUri(context, video.uri) }.getOrNull()
                if (doc == null || !doc.exists()) {
                    return@withContext MediaMutationResult.Success("Video was already removed.")
                }
                if (!doc.canWrite()) {
                    return@withContext MediaMutationResult.NotPermitted("This folder does not permit deleting files.")
                }
                val deleted = runCatching { doc.delete() }.getOrDefault(false)
                if (deleted) {
                    MediaMutationResult.Success("Deleted ${video.displayName}")
                } else {
                    MediaMutationResult.Failed("Unable to delete ${video.displayName}.")
                }
            }

            VideoSourceType.MEDIA_STORE -> {
                try {
                    val rows = context.contentResolver.delete(video.uri, null, null)
                    if (rows > 0) {
                        MediaMutationResult.Success("Deleted ${video.displayName}")
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
                        MediaMutationResult.NeedsUserConsent(pi.intentSender, "Confirm delete")
                    } else {
                        MediaMutationResult.NotPermitted("Could not delete this MediaStore video.")
                    }
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = runCatching {
                            MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
                        }.getOrNull()
                        if (pi != null) {
                            MediaMutationResult.NeedsUserConsent(pi.intentSender, "Confirm delete")
                        } else {
                            MediaMutationResult.NotPermitted("Deletion was denied by Android storage security.")
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        MediaMutationResult.NeedsUserConsent(e.userAction.actionIntent.intentSender, "Confirm delete")
                    } else {
                        MediaMutationResult.NotPermitted("Deletion is not permitted for this video.")
                    }
                } catch (e: Exception) {
                    MediaMutationResult.Failed(e.message ?: "Failed to delete video.")
                }
            }
        }
    }

    private fun queryMediaStoreVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val projectionList = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList += MediaStore.Video.Media.RELATIVE_PATH
            projectionList += MediaStore.Video.Media.ORIENTATION
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projectionList.toTypedArray(),
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val titleIdx = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
            val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val widthIdx = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val mimeIdx = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val dateAddedIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val relPathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            } else -1
            val orientationIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
            } else -1

            if (idIdx < 0) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val displayName = if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } else null
                val resolvedName = displayName ?: "Video_$id.mp4"
                val title = (if (titleIdx >= 0) cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } else null)
                    ?: resolvedName.substringBeforeLast('.')
                val ext = resolvedName.substringAfterLast('.', "").lowercase(Locale.US)
                val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx)?.takeIf { it.isNotBlank() } else null)
                    ?: guessMimeFromExtension(ext)
                var durationMs = if (durationIdx >= 0 && !cursor.isNull(durationIdx)) cursor.getLong(durationIdx).coerceAtLeast(0L) else 0L
                val sizeBytes = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                var width = if (widthIdx >= 0 && !cursor.isNull(widthIdx)) cursor.getInt(widthIdx).coerceAtLeast(0) else 0
                var height = if (heightIdx >= 0 && !cursor.isNull(heightIdx)) cursor.getInt(heightIdx).coerceAtLeast(0) else 0
                val dateAddedSec = if (dateAddedIdx >= 0 && !cursor.isNull(dateAddedIdx)) cursor.getLong(dateAddedIdx) else 0L
                val dateModifiedSec = if (dateModifiedIdx >= 0 && !cursor.isNull(dateModifiedIdx)) cursor.getLong(dateModifiedIdx) else dateAddedSec
                val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx)?.takeIf { it.isNotBlank() } else null
                val relativePath = if (relPathIdx >= 0) cursor.getString(relPathIdx)?.takeIf { it.isNotBlank() } else null
                var orientation = if (orientationIdx >= 0 && !cursor.isNull(orientationIdx)) cursor.getInt(orientationIdx) else 0

                // Fallback metadata extraction only when MediaStore omits dimensions/duration
                if (width <= 0 || height <= 0 || durationMs <= 0L) {
                    val quick = extractQuickMetadata(uri)
                    if (width <= 0) width = quick.width
                    if (height <= 0) height = quick.height
                    if (durationMs <= 0L) durationMs = quick.durationMs
                    if (orientation == 0) orientation = quick.rotationDegrees
                }

                val folderLabel = bucket
                    ?: relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "Device Videos"

                result += VideoItem(
                    id = "ms_$id",
                    uri = uri,
                    title = title,
                    displayName = resolvedName,
                    durationMs = durationMs,
                    sizeBytes = sizeBytes,
                    width = width,
                    height = height,
                    mimeType = mime,
                    extension = ext,
                    dateAddedSec = dateAddedSec,
                    dateModifiedSec = dateModifiedSec,
                    folderName = folderLabel,
                    relativePath = relativePath,
                    bucketDisplayName = bucket,
                    orientationDegrees = orientation,
                    sourceType = VideoSourceType.MEDIA_STORE,
                    canRename = true,
                    canDelete = true
                )
            }
        }
        return result
    }

    private fun queryMediaStoreImages(): List<ImageItem> {
        val result = mutableListOf<ImageItem>()
        val projectionList = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.TITLE,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList += MediaStore.Images.Media.RELATIVE_PATH
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projectionList.toTypedArray(),
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val titleIdx = cursor.getColumnIndex(MediaStore.Images.Media.TITLE)
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val dateAddedIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationIdx = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val relPathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else -1

            if (idIdx < 0) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val displayName = if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } else null
                val resolvedName = displayName ?: "Image_$id.jpg"
                val title = (if (titleIdx >= 0) cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } else null)
                    ?: resolvedName.substringBeforeLast('.')
                val ext = resolvedName.substringAfterLast('.', "").lowercase(Locale.US)
                val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx)?.takeIf { it.isNotBlank() } else null)
                    ?: guessImageMimeFromExtension(ext)
                val sizeBytes = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                var width = if (widthIdx >= 0 && !cursor.isNull(widthIdx)) cursor.getInt(widthIdx).coerceAtLeast(0) else 0
                var height = if (heightIdx >= 0 && !cursor.isNull(heightIdx)) cursor.getInt(heightIdx).coerceAtLeast(0) else 0
                val dateAddedSec = if (dateAddedIdx >= 0 && !cursor.isNull(dateAddedIdx)) cursor.getLong(dateAddedIdx) else 0L
                val dateModifiedSec = if (dateModifiedIdx >= 0 && !cursor.isNull(dateModifiedIdx)) cursor.getLong(dateModifiedIdx) else dateAddedSec
                val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx)?.takeIf { it.isNotBlank() } else null
                val orientation = if (orientationIdx >= 0 && !cursor.isNull(orientationIdx)) cursor.getInt(orientationIdx) else 0
                val relativePath = if (relPathIdx >= 0) cursor.getString(relPathIdx)?.takeIf { it.isNotBlank() } else null

                if (width <= 0 || height <= 0) {
                    val (w, h) = extractImageBounds(uri)
                    if (width <= 0) width = w
                    if (height <= 0) height = h
                }

                val folderLabel = bucket
                    ?: relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "Device Photos"

                result += ImageItem(
                    id = "img_ms_$id",
                    uri = uri,
                    title = title,
                    displayName = resolvedName,
                    sizeBytes = sizeBytes,
                    width = width,
                    height = height,
                    mimeType = mime,
                    extension = ext,
                    dateAddedSec = dateAddedSec,
                    dateModifiedSec = dateModifiedSec,
                    folderName = folderLabel,
                    relativePath = relativePath,
                    bucketDisplayName = bucket,
                    orientationDegrees = orientation,
                    sourceType = VideoSourceType.MEDIA_STORE
                )
            }
        }
        return result
    }

    private fun scanDocumentTreeRecursive(
        directory: DocumentFile,
        rootTreeUri: Uri,
        rootFolderName: String,
        relativeSubPath: String,
        depth: Int,
        maxDepth: Int,
        outVideos: MutableList<VideoItem>,
        outImages: MutableList<ImageItem>
    ) {
        if (depth > maxDepth) return
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val childName = child.name?.takeIf { it.isNotBlank() } ?: "Subfolder"
                scanDocumentTreeRecursive(
                    directory = child,
                    rootTreeUri = rootTreeUri,
                    rootFolderName = rootFolderName,
                    relativeSubPath = "$relativeSubPath/$childName",
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    outVideos = outVideos,
                    outImages = outImages
                )
            } else if (child.isFile && isVideoDocument(child)) {
                val name = child.name ?: "Video.mp4"
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                val mime = child.type?.takeIf { it.startsWith("video/") }
                    ?: guessMimeFromExtension(ext)
                val meta = extractQuickMetadata(child.uri)
                val modifiedSec = (child.lastModified() / 1000L).coerceAtLeast(0L)
                val writable = runCatching { child.canWrite() }.getOrDefault(false)
                outVideos += VideoItem(
                    id = "saf_${child.uri.toString().hashCode()}",
                    uri = child.uri,
                    title = name.substringBeforeLast('.').ifBlank { name },
                    displayName = name,
                    durationMs = meta.durationMs,
                    sizeBytes = child.length().coerceAtLeast(0L),
                    width = meta.width,
                    height = meta.height,
                    mimeType = mime,
                    extension = ext,
                    dateAddedSec = modifiedSec,
                    dateModifiedSec = modifiedSec,
                    folderName = rootFolderName,
                    relativePath = "$relativeSubPath/",
                    bucketDisplayName = rootFolderName,
                    parentTreeUri = rootTreeUri,
                    orientationDegrees = meta.rotationDegrees,
                    sourceType = VideoSourceType.SAF_FOLDER,
                    canRename = writable,
                    canDelete = writable
                )
            } else if (child.isFile && isImageDocument(child)) {
                val name = child.name ?: "Image.jpg"
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                val mime = child.type?.takeIf { it.startsWith("image/") }
                    ?: guessImageMimeFromExtension(ext)
                val (width, height) = extractImageBounds(child.uri)
                val modifiedSec = (child.lastModified() / 1000L).coerceAtLeast(0L)
                outImages += ImageItem(
                    id = "img_saf_${child.uri.toString().hashCode()}",
                    uri = child.uri,
                    title = name.substringBeforeLast('.').ifBlank { name },
                    displayName = name,
                    sizeBytes = child.length().coerceAtLeast(0L),
                    width = width,
                    height = height,
                    mimeType = mime,
                    extension = ext,
                    dateAddedSec = modifiedSec,
                    dateModifiedSec = modifiedSec,
                    folderName = rootFolderName,
                    relativePath = "$relativeSubPath/",
                    bucketDisplayName = rootFolderName,
                    sourceType = VideoSourceType.SAF_FOLDER
                )
            }
        }
    }

    private fun resolveSingleVideoUri(
        uri: Uri,
        folderName: String,
        sourceType: VideoSourceType
    ): VideoItem? {
        return try {
            var displayName = "Selected Video.mp4"
            var sizeBytes = 0L
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                    }
                }
            }
            if (sizeBytes == 0L) {
                sizeBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    it.length.coerceAtLeast(0L)
                } ?: return null
            }
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") }
                ?: guessMimeFromExtension(ext)
            val meta = extractQuickMetadata(uri)
            val nowSec = System.currentTimeMillis() / 1000L
            VideoItem(
                id = "pick_${uri.toString().hashCode()}",
                uri = uri,
                title = displayName.substringBeforeLast('.').ifBlank { displayName },
                displayName = displayName,
                durationMs = meta.durationMs,
                sizeBytes = sizeBytes,
                width = meta.width,
                height = meta.height,
                mimeType = mime,
                extension = ext.ifBlank { mime.substringAfter("/", "mp4") },
                dateAddedSec = nowSec,
                dateModifiedSec = nowSec,
                folderName = folderName,
                bucketDisplayName = folderName,
                orientationDegrees = meta.rotationDegrees,
                sourceType = sourceType,
                canRename = false,
                canDelete = false
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class QuickMeta(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    private fun extractQuickMetadata(uri: Uri): QuickMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            QuickMeta(duration, width, height, rotation)
        } catch (_: Exception) {
            QuickMeta(0L, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractImageBounds(uri: Uri): Pair<Int, Int> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
        } catch (_: Exception) {
            0 to 0
        }
    }

    private fun isVideoDocument(doc: DocumentFile): Boolean {
        val mime = doc.type?.lowercase(Locale.US)
        if (mime != null && mime.startsWith("video/")) return true
        val ext = doc.name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: return false
        return ext in SUPPORTED_VIDEO_EXTENSIONS
    }

    private fun isImageDocument(doc: DocumentFile): Boolean {
        val mime = doc.type?.lowercase(Locale.US)
        if (mime != null && mime.startsWith("image/")) return true
        val ext = doc.name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: return false
        return ext in SUPPORTED_IMAGE_EXTENSIONS
    }

    companion object {
        private val SUPPORTED_VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "3gp", "flv", "wmv", "mpg", "mpeg"
        )

        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif"
        )

        fun dedupeKey(displayName: String, sizeBytes: Long): String {
            return "${displayName.lowercase(Locale.US)}|$sizeBytes"
        }

        fun classifyAccessState(
            mediaStoreAccessible: Boolean,
            mediaStoreCount: Int,
            safFolderCount: Int,
            pickedVideoCount: Int,
            totalVideos: Int
        ): LibraryAccessState {
            return when {
                !mediaStoreAccessible && totalVideos == 0 -> LibraryAccessState.MEDIA_STORE_UNAVAILABLE
                totalVideos == 0 -> LibraryAccessState.EMPTY_NO_SOURCES
                mediaStoreCount == 0 && (pickedVideoCount > 0 || safFolderCount > 0) -> LibraryAccessState.PARTIAL_ACCESS
                else -> LibraryAccessState.ACCESSIBLE
            }
        }

        fun guessMimeFromExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "video/mp4"
        }

        fun guessImageMimeFromExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }
    }
}
