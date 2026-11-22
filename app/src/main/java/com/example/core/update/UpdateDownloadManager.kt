package com.example.core.update

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApkDownloadResult {
    data class Success(val apkFile: File, val actualSha256: String) : ApkDownloadResult
    data class Failure(val reason: String) : ApkDownloadResult
}

/**
 * Streams APK update packages to `cacheDir/updates/` with disk headroom validation,
 * HTTPS redirect checks, HTTP Range resumption for interrupted `.part` files,
 * and coroutine cancellation support. Never buffers complete APKs in RAM.
 */
class UpdateDownloadManager(
    private val context: Context,
    private val availableSpaceProvider: (File) -> Long = { dir ->
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }
) {

    private val updatesDir: File
        get() = File(context.cacheDir, UPDATES_FOLDER).apply { mkdirs() }

    suspend fun downloadApk(
        asset: GitHubApkAsset,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        if (!asset.downloadUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext ApkDownloadResult.Failure("Insecure APK download URL rejected.")
        }

        val dir = updatesDir
        if (asset.sizeBytes > 0L) {
            val requiredBytes = asset.sizeBytes + STORAGE_MARGIN_BYTES
            val available = runCatching { availableSpaceProvider(dir) }.getOrDefault(Long.MAX_VALUE)
            if (available < requiredBytes) {
                return@withContext ApkDownloadResult.Failure(
                    "Insufficient storage space to download update. Free up space and try again."
                )
            }
        }

        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val tempFile = File(dir, "$safeName.part")
        val targetFile = File(dir, safeName)

        // Remove stale APKs from previous versions while preserving an in-progress .part file for resume
        dir.listFiles()?.forEach { existing ->
            if (existing.absolutePath != tempFile.absolutePath) {
                existing.delete()
            }
        }

        try {
            val existingPartialBytes = if (tempFile.exists()) tempFile.length() else 0L
            var currentUrl = asset.downloadUrl
            var redirectCount = 0
            var connection: HttpURLConnection

            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "LumaCast-Android")
                    if (existingPartialBytes > 0L && (asset.sizeBytes <= 0L || existingPartialBytes < asset.sizeBytes)) {
                        setRequestProperty("Range", "bytes=$existingPartialBytes-")
                    }
                }
                val code = connection.responseCode
                if (code in listOf(
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || !location.startsWith("https://", ignoreCase = true)) {
                        return@withContext ApkDownloadResult.Failure("Invalid redirect while downloading update.")
                    }
                    if (++redirectCount > MAX_REDIRECTS) {
                        return@withContext ApkDownloadResult.Failure("Too many redirects while downloading update.")
                    }
                    currentUrl = location
                } else {
                    break
                }
            }

            try {
                val code = connection.responseCode
                if (code == 416 && tempFile.exists() && asset.sizeBytes > 0L && tempFile.length() == asset.sizeBytes) {
                    // Range already complete from previous download attempt
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    val sha = ApkVerificationManager.computeSha256(targetFile)
                    onProgress(asset.sizeBytes, asset.sizeBytes, 100)
                    return@withContext ApkDownloadResult.Success(targetFile, sha)
                }

                if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                    return@withContext ApkDownloadResult.Failure("Download failed (HTTP $code).")
                }

                val isResuming = code == HttpURLConnection.HTTP_PARTIAL && existingPartialBytes > 0L
                val startOffset = if (isResuming) existingPartialBytes else {
                    if (tempFile.exists()) tempFile.delete()
                    0L
                }

                val remainingLength = connection.contentLengthLong
                val totalBytes = when {
                    remainingLength > 0L -> startOffset + remainingLength
                    asset.sizeBytes > 0L -> asset.sizeBytes
                    else -> -1L
                }

                val buffer = ByteArray(16 * 1024)
                var downloaded = startOffset
                var lastEmitMs = 0L
                var lastPercent = -1

                onProgress(
                    downloaded,
                    totalBytes.coerceAtLeast(0L),
                    if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                )

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, isResuming).use { output ->
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("APK download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            val now = System.currentTimeMillis()
                            if (pct != lastPercent && (now - lastEmitMs >= 120L || pct == 100)) {
                                lastEmitMs = now
                                lastPercent = pct
                                onProgress(downloaded, totalBytes.coerceAtLeast(0L), pct)
                            }
                        }
                        output.flush()
                    }
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                val sha256 = ApkVerificationManager.computeSha256(targetFile)
                onProgress(downloaded, totalBytes.coerceAtLeast(downloaded), 100)
                ApkDownloadResult.Success(targetFile, sha256)
            } finally {
                connection.disconnect()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            ApkDownloadResult.Failure("Download interrupted: ${io.message ?: "connection lost. Tap retry to resume."}")
        } catch (e: Exception) {
            tempFile.delete()
            ApkDownloadResult.Failure("Download failed: ${e.message ?: "unexpected error"}")
        }
    }

    fun clearUpdatesCache() {
        runCatching {
            updatesDir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        private const val UPDATES_FOLDER = "updates"
        private const val MAX_REDIRECTS = 6
        private const val STORAGE_MARGIN_BYTES = 16L * 1024L * 1024L
    }
}
