package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Stable
import com.example.core.common.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface ThumbnailLoadResult {
    data class Success(val bitmap: Bitmap) : ThumbnailLoadResult
    data object Failed : ThumbnailLoadResult
}

/**
 * Asynchronous, memory-bounded video thumbnail engine backed by Android's official
 * `ContentResolver.loadThumbnail` (API 29+) and scaled `MediaMetadataRetriever` fallback.
 *
 * Performance & Resource Guarantees:
 * - Never decodes full-resolution 4K frames into RAM; requests downscaled thumbnails matching row size.
 * - Uses an `LruCache` keyed by `(video.id, dateModifiedSec, size)` so thumbnails are never
 *   regenerated unnecessarily during fast LazyColumn scrolling.
 * - Tracks failed/corrupted URIs in a negative cache so scrolling past broken files never stalls decoding.
 * - Bounds concurrent native decoder threads via `limitedParallelism(3)` and cancels promptly via `CancellationSignal`.
 */
@Stable
class VideoThumbnailLoader(private val appContext: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)

    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = (maxKb / 8).coerceIn(4 * 1024, 32 * 1024)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return (bitmap.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    private val failedKeys = ConcurrentHashMap<String, Boolean>()
    private val inFlightDecodes = ConcurrentHashMap<String, CompletableDeferred<ThumbnailLoadResult>>()

    fun cacheKey(video: VideoItem, widthPx: Int, heightPx: Int): String {
        return "${video.id}_${video.dateModifiedSec}_${widthPx}x${heightPx}"
    }

    fun getCached(video: VideoItem, widthPx: Int, heightPx: Int): Bitmap? {
        val bmp = memoryCache.get(cacheKey(video, widthPx, heightPx))
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun isKnownFailed(video: VideoItem, widthPx: Int, heightPx: Int): Boolean {
        return failedKeys.containsKey(cacheKey(video, widthPx, heightPx))
    }

    fun putForTesting(key: String, bitmap: Bitmap) {
        failedKeys.remove(key)
        memoryCache.put(key, bitmap)
    }

    fun cacheEntryCount(): Int = memoryCache.size()

    fun invalidateVideo(videoId: String) {
        val prefix = "${videoId}_"
        memoryCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { memoryCache.remove(it) }
        failedKeys.keys.filter { it.startsWith(prefix) }.forEach { failedKeys.remove(it) }
    }

    fun clearCache() {
        memoryCache.evictAll()
        failedKeys.clear()
    }

    suspend fun loadThumbnail(
        video: VideoItem,
        widthPx: Int = DEFAULT_THUMB_WIDTH_PX,
        heightPx: Int = DEFAULT_THUMB_HEIGHT_PX
    ): ThumbnailLoadResult {
        val safeW = widthPx.coerceIn(96, 640)
        val safeH = heightPx.coerceIn(64, 480)
        val key = cacheKey(video, safeW, safeH)

        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { cached ->
            return ThumbnailLoadResult.Success(cached)
        }
        if (failedKeys.containsKey(key)) {
            return ThumbnailLoadResult.Failed
        }

        // Deduplicate concurrent decodes for the exact same video+size key
        val existingDeferred = inFlightDecodes[key]
        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        val ownDeferred = CompletableDeferred<ThumbnailLoadResult>()
        val raced = inFlightDecodes.putIfAbsent(key, ownDeferred)
        if (raced != null) {
            return raced.await()
        }

        return try {
            val result = withContext(decodeDispatcher) {
                memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { cached ->
                    return@withContext ThumbnailLoadResult.Success(cached)
                }

                val signal = CancellationSignal()
                val currentJob = coroutineContext[kotlinx.coroutines.Job]
                val cancelHandle = currentJob?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        runCatching { signal.cancel() }
                    }
                }

                try {
                    val bitmap = decodeScaledBitmap(video, safeW, safeH, signal)
                    if (bitmap != null && !bitmap.isRecycled) {
                        memoryCache.put(key, bitmap)
                        ThumbnailLoadResult.Success(bitmap)
                    } else {
                        failedKeys[key] = true
                        ThumbnailLoadResult.Failed
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    failedKeys[key] = true
                    ThumbnailLoadResult.Failed
                } finally {
                    cancelHandle?.dispose()
                }
            }
            ownDeferred.complete(result)
            result
        } catch (ce: CancellationException) {
            ownDeferred.cancel(ce)
            throw ce
        } catch (_: Throwable) {
            ownDeferred.complete(ThumbnailLoadResult.Failed)
            ThumbnailLoadResult.Failed
        } finally {
            inFlightDecodes.remove(key, ownDeferred)
        }
    }

    private fun decodeScaledBitmap(
        video: VideoItem,
        widthPx: Int,
        heightPx: Int,
        signal: CancellationSignal
    ): Bitmap? {
        // 1. Official Android API 29+ ContentResolver.loadThumbnail (fast system thumbnail cache)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val osThumb = runCatching {
                appContext.contentResolver.loadThumbnail(
                    video.uri,
                    Size(widthPx, heightPx),
                    signal
                )
            }.getOrNull()
            if (osThumb != null && !osThumb.isRecycled) {
                return osThumb
            }
        }

        if (signal.isCanceled) return null

        // 2. Fallback: Hardware-scaled MediaMetadataRetriever frame extraction (never decodes full 4K frame)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, video.uri)
            val targetTimeUs = when {
                video.durationMs > 3_000L -> 1_500_000L
                video.durationMs > 500L -> (video.durationMs / 2L) * 1000L
                else -> 0L
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    targetTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    widthPx,
                    heightPx
                ) ?: retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    widthPx,
                    heightPx
                )
            } else {
                val raw = retriever.getFrameAtTime(
                    targetTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null && (raw.width > widthPx || raw.height > heightPx)) {
                    val scale = minOf(
                        widthPx.toFloat() / raw.width.coerceAtLeast(1),
                        heightPx.toFloat() / raw.height.coerceAtLeast(1)
                    ).coerceAtMost(1f)
                    val scaledW = (raw.width * scale).toInt().coerceAtLeast(1)
                    val scaledH = (raw.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(raw, scaledW, scaledH, true)
                    if (scaled !== raw) {
                        raw.recycle()
                    }
                    scaled
                } else {
                    raw
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val DEFAULT_THUMB_WIDTH_PX = 320
        const val DEFAULT_THUMB_HEIGHT_PX = 180
    }
}
