package com.example.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.core.common.AudioConversionPlan
import com.example.core.common.CompatibilityReport
import com.example.core.common.HdrFormat
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ProcessingBackendType
import com.example.core.common.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PreparedMediaFile(
    val file: File,
    val mimeType: String,
    val backendUsed: ProcessingBackendType
)

/**
 * Dual-backend media preparation engine:
 * - Backend A: Android MediaExtractor/MediaMuxer (lossless remux) + Media3 Transformer
 *   (selective audio-only, video-only, or full hardware transcoding with ITU-R BS.775 downmixing).
 * - Backend B: Bundled native FFmpeg backend (libffmpeg.so) for DTS, DTS-HD, TrueHD, Xvid/DivX,
 *   multi-language audio selection, and advanced processing when bundled.
 *
 * Guarantees:
 * - Writes exclusively to `.part` files and atomically renames to `.mp4` only after success,
 *   so partially written files are NEVER exposed to Chromecast.
 * - Tracks actively streamed files (`inUsePaths`) so cache cleanup NEVER deletes a file
 *   currently being read by the TV.
 */
class MediaPreparationEngine(
    private val context: Context,
    val ffmpegBackend: FfmpegTranscodeBackend = FfmpegTranscodeBackend(context)
) {

    private val inUsePaths = ConcurrentHashMap.newKeySet<String>()

    private val cacheDir: File
        get() = File(context.cacheDir, "lumacast_prepared").apply { mkdirs() }

    fun markFileInUse(file: File) {
        inUsePaths.add(file.absolutePath)
    }

    fun releaseAllInUseFiles() {
        inUsePaths.clear()
    }

    fun isFileInUse(file: File): Boolean = inUsePaths.contains(file.absolutePath)

    /**
     * Executes the strategy selected by [MediaCompatibilityManager] using the optimal backend.
     */
    suspend fun prepareForCast(
        video: VideoItem,
        report: CompatibilityReport,
        maxTargetHeight: Int,
        onProgress: (Int) -> Unit
    ): PreparedMediaFile {
        return when (report.strategy) {
            PlaybackStrategy.REMUX -> {
                if (report.recommendedBackend == ProcessingBackendType.FFMPEG && ffmpegBackend.isAvailable()) {
                    executeWithFfmpeg(video, report, maxTargetHeight, onProgress)
                } else {
                    remuxToMp4(
                        video = video,
                        selectedAudioTrackIndex = report.selectedAudioTrack?.trackIndex,
                        durationMs = report.technicalInfo.durationMs.coerceAtLeast(video.durationMs),
                        onProgress = onProgress
                    )
                }
            }

            PlaybackStrategy.AUDIO_ONLY_TRANSCODE,
            PlaybackStrategy.VIDEO_ONLY_TRANSCODE,
            PlaybackStrategy.FULL_TRANSCODE,
            PlaybackStrategy.CONSERVATIVE_FALLBACK -> {
                if (report.recommendedBackend == ProcessingBackendType.FFMPEG && ffmpegBackend.isAvailable()) {
                    executeWithFfmpeg(video, report, maxTargetHeight, onProgress)
                } else {
                    executeWithMedia3Transformer(video, report, maxTargetHeight, onProgress)
                }
            }

            else -> throw IllegalArgumentException("Strategy ${report.strategy} does not require local file preparation.")
        }
    }

    /**
     * Strategy B: Lossless container remux (MediaExtractor -> MediaMuxer) preserving exact
     * video & selected audio bitstreams and presentation timestamps without re-encoding.
     */
    suspend fun remuxToMp4(
        video: VideoItem,
        selectedAudioTrackIndex: Int?,
        durationMs: Long,
        onProgress: (Int) -> Unit
    ): PreparedMediaFile = withContext(Dispatchers.IO) {
        val baseName = "remux_${video.id.hashCode()}_${System.currentTimeMillis()}"
        val partFile = File(cacheDir, "$baseName.mp4.part")
        val finalFile = File(cacheDir, "$baseName.mp4")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, video.uri, null)
            muxer = MediaMuxer(partFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackIndexMap = mutableMapOf<Int, Int>()
            var addedVideo = false
            var addedAudio = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && !addedVideo -> {
                        extractor.selectTrack(i)
                        trackIndexMap[i] = muxer.addTrack(format)
                        addedVideo = true
                    }

                    mime.startsWith("audio/") && !addedAudio &&
                        (selectedAudioTrackIndex == null || selectedAudioTrackIndex == i) -> {
                        extractor.selectTrack(i)
                        trackIndexMap[i] = muxer.addTrack(format)
                        addedAudio = true
                    }
                }
            }

            if (trackIndexMap.isEmpty()) {
                throw IllegalStateException("No compatible video or audio tracks found to remux.")
            }

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
            var lastReported = -1

            while (isActive) {
                bufferInfo.offset = 0
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sourceTrack = extractor.sampleTrackIndex
                val targetTrack = trackIndexMap[sourceTrack]
                if (targetTrack != null) {
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(targetTrack, buffer, bufferInfo)

                    val pct = ((bufferInfo.presentationTimeUs * 100L) / durationUs)
                        .toInt().coerceIn(0, 99)
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(pct)
                    }
                }
                extractor.advance()
            }

            if (!isActive) {
                throw CancellationException("Remux cancelled")
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException("Failed to finalize remuxed MP4 output.")
            }
            onProgress(100)
            PreparedMediaFile(finalFile, "video/mp4", ProcessingBackendType.NATIVE_MUXER)
        } catch (t: Throwable) {
            partFile.delete()
            finalFile.delete()
            throw t
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Backend A: AndroidX Media3 Transformer with selective track passthrough:
     * - AUDIO_ONLY_TRANSCODE: sets ONLY setAudioMimeType(AUDIO_AAC) -> video track is transmuxed untouched!
     * - VIDEO_ONLY_TRANSCODE: sets ONLY setVideoMimeType(VIDEO_H264) -> audio track is transmuxed untouched!
     * - FULL_TRANSCODE / CONSERVATIVE_FALLBACK: encodes both tracks + optional SDR tone-mapping + no upscaling.
     */
    @OptIn(UnstableApi::class)
    private suspend fun executeWithMedia3Transformer(
        video: VideoItem,
        report: CompatibilityReport,
        maxTargetHeight: Int,
        onProgress: (Int) -> Unit
    ): PreparedMediaFile {
        val baseName = "${report.strategy.name.lowercase()}_${video.id.hashCode()}_${System.currentTimeMillis()}"
        val partFile = File(cacheDir, "$baseName.mp4.part")
        val finalFile = File(cacheDir, "$baseName.mp4")

        val videoEffects = buildList {
            if (!report.strategy.preservesVideoBitstream) {
                val cap = if (report.strategy == PlaybackStrategy.CONSERVATIVE_FALLBACK) {
                    1080
                } else {
                    minOf(report.receiverProfile.maxHeight, maxTargetHeight)
                }
                val sourceHeight = report.technicalInfo.height
                // Never upscale lower-resolution videos
                if (sourceHeight > cap && cap > 0) {
                    add(Presentation.createForHeight(cap))
                }
            }
        }

        val audioProcessors = buildList {
            val plan = report.audioConversionPlan
            if (!report.strategy.preservesAudioBitstream && plan != null && plan.downmixRequired) {
                buildChannelMixingProcessor(plan)?.let { add(it) }
            }
        }

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(video.uri))
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        val hdrMode = if (report.outputHdrFormat == HdrFormat.SDR &&
            report.technicalInfo.hdrFormat != HdrFormat.SDR &&
            !report.strategy.preservesVideoBitstream
        ) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
            Composition.HDR_MODE_KEEP_HDR
        }

        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(editedMediaItem).build()
        )
            .setHdrMode(hdrMode)
            .build()

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var completed = false
                val builder = Transformer.Builder(context)

                // Selective track configuration preserves compatible tracks without re-encoding!
                when (report.strategy) {
                    PlaybackStrategy.AUDIO_ONLY_TRANSCODE -> {
                        builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }

                    PlaybackStrategy.VIDEO_ONLY_TRANSCODE -> {
                        builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    }

                    PlaybackStrategy.FULL_TRANSCODE,
                    PlaybackStrategy.CONSERVATIVE_FALLBACK -> {
                        builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                        builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }

                    else -> Unit
                }

                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            completed = true
                            if (!partFile.renameTo(finalFile)) {
                                partFile.delete()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("Failed to finalize transcoded MP4 file.")
                                    )
                                }
                                return
                            }
                            onProgress(100)
                            if (continuation.isActive) {
                                continuation.resume(
                                    PreparedMediaFile(
                                        file = finalFile,
                                        mimeType = "video/mp4",
                                        backendUsed = ProcessingBackendType.MEDIA3_TRANSFORMER
                                    )
                                )
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            completed = true
                            partFile.delete()
                            finalFile.delete()
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    if (!completed) {
                        transformer.cancel()
                        partFile.delete()
                        finalFile.delete()
                    }
                }

                transformer.start(composition, partFile.absolutePath)

                val progressHolder = ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                val poller = object : Runnable {
                    override fun run() {
                        if (completed || !continuation.isActive) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 99))
                        }
                        handler.postDelayed(this, 250L)
                    }
                }
                handler.post(poller)
            }
        }
    }

    /**
     * Backend B execution via bundled FFmpeg binary. Streams content URI via 64 KB buffer
     * to a scoped input staging file if SAF URI cannot be passed directly, executes FFmpeg,
     * atomically renames `.part` -> `.mp4`, and cleans up staging file immediately.
     */
    private suspend fun executeWithFfmpeg(
        video: VideoItem,
        report: CompatibilityReport,
        maxTargetHeight: Int,
        onProgress: (Int) -> Unit
    ): PreparedMediaFile = withContext(Dispatchers.IO) {
        val baseName = "ffmpeg_${report.strategy.name.lowercase()}_${video.id.hashCode()}_${System.currentTimeMillis()}"
        val stagedInput = File(cacheDir, "$baseName.input.${video.extension.ifBlank { "bin" }}")
        val partFile = File(cacheDir, "$baseName.mp4.part")
        val finalFile = File(cacheDir, "$baseName.mp4")

        try {
            // Stream-copy input via 64 KB buffered I/O (never loads full video into RAM)
            context.contentResolver.openInputStream(video.uri)?.use { rawIn ->
                BufferedInputStream(rawIn, 64 * 1024).use { input ->
                    BufferedOutputStream(FileOutputStream(stagedInput), 64 * 1024).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (isActive) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to read source media URI for FFmpeg backend.")

            ffmpegBackend.execute(
                inputFile = stagedInput,
                tempPartOutputFile = partFile,
                report = report,
                maxTargetHeight = maxTargetHeight,
                durationMs = report.technicalInfo.durationMs.coerceAtLeast(video.durationMs),
                onProgress = onProgress
            )

            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException("Failed to finalize FFmpeg output MP4.")
            }
            PreparedMediaFile(finalFile, "video/mp4", ProcessingBackendType.FFMPEG)
        } catch (t: Throwable) {
            partFile.delete()
            finalFile.delete()
            throw t
        } finally {
            stagedInput.delete()
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildChannelMixingProcessor(plan: AudioConversionPlan): ChannelMixingAudioProcessor? {
        val src = plan.sourceTrack.channelCount ?: return null
        val dst = plan.targetChannels
        if (src <= dst) return null
        val matrix = createItuDownmixMatrix(src, dst) ?: return null
        return ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(matrix)
        }
    }

    fun cacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Safely cleans temporary prepared media files while NEVER deleting any file
     * actively being streamed to Chromecast over HTTP.
     */
    fun clearCache(): Long {
        var cleared = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (!inUsePaths.contains(file.absolutePath)) {
                val len = file.length()
                if (file.delete()) cleared += len
            }
        }
        return cleared
    }

    companion object {
        /**
         * Creates an ITU-R BS.775 compliant [ChannelMixingMatrix] so 7.1 and 5.1 surround
         * downmixes preserve center dialogue, LFE, and surround channels cleanly.
         */
        @OptIn(UnstableApi::class)
        fun createItuDownmixMatrix(sourceChannels: Int, targetChannels: Int): ChannelMixingMatrix? {
            return when {
                // 5.1 (FL, FR, FC, LFE, BL, BR) -> Stereo (FL, FR)
                sourceChannels == 6 && targetChannels == 2 -> ChannelMixingMatrix(
                    6,
                    2,
                    floatArrayOf(
                        0.50f, 0.00f, // FL
                        0.00f, 0.50f, // FR
                        0.354f, 0.354f, // FC
                        0.25f, 0.25f, // LFE
                        0.354f, 0.00f, // BL
                        0.00f, 0.354f  // BR
                    )
                )

                // 7.1 (FL, FR, FC, LFE, BL, BR, SL, SR) -> Stereo (FL, FR)
                sourceChannels == 8 && targetChannels == 2 -> ChannelMixingMatrix(
                    8,
                    2,
                    floatArrayOf(
                        0.50f, 0.00f,   // FL
                        0.00f, 0.50f,   // FR
                        0.354f, 0.354f, // FC
                        0.25f, 0.25f,   // LFE
                        0.354f, 0.00f,  // BL
                        0.00f, 0.354f,  // BR
                        0.354f, 0.00f,  // SL
                        0.00f, 0.354f   // SR
                    )
                )

                // 7.1 -> 5.1 (merges side & back surrounds cleanly)
                sourceChannels == 8 && targetChannels == 6 -> ChannelMixingMatrix(
                    8,
                    6,
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f, 0f,         // FL
                        0f, 1f, 0f, 0f, 0f, 0f,         // FR
                        0f, 0f, 1f, 0f, 0f, 0f,         // FC
                        0f, 0f, 0f, 1f, 0f, 0f,         // LFE
                        0f, 0f, 0f, 0f, 0.707f, 0f,     // BL
                        0f, 0f, 0f, 0f, 0f, 0.707f,     // BR
                        0f, 0f, 0f, 0f, 0.707f, 0f,     // SL -> BL
                        0f, 0f, 0f, 0f, 0f, 0.707f      // SR -> BR
                    )
                )

                else -> ChannelMixingMatrix.create(sourceChannels, targetChannels)
            }
        }
    }
}
