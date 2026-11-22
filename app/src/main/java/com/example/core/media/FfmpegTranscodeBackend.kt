package com.example.core.media

import android.content.Context
import com.example.core.common.AudioConversionPlan
import com.example.core.common.CompatibilityReport
import com.example.core.common.PlaybackStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Backend B: Play-Policy compliant FFmpeg execution bridge.
 *
 * Uses a legally bundled, build-time compiled native FFmpeg executable packaged
 * inside `applicationInfo.nativeLibraryDir/libffmpeg.so` (NEVER uses retired
 * FFmpegKit artifacts and NEVER downloads binaries at runtime).
 *
 * Supports selective track conversion (-c:v copy / -c:a copy), multi-language
 * audio mapping (-map 0:a:<ordinal>), ITU-R BS.775 downmixing, A/V sync preservation,
 * faststart MP4 atom placement, and real-time progress parsing.
 */
class FfmpegTranscodeBackend(private val context: Context) {

    fun binaryFile(): File {
        return File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    }

    fun isAvailable(): Boolean {
        val file = binaryFile()
        return file.exists() && file.canExecute()
    }

    /**
     * Builds the exact FFmpeg command-line arguments for the selected [CompatibilityReport].
     * Exposed internally for deterministic unit verification across all 6 strategies.
     */
    fun buildCommand(
        executablePath: String,
        inputPath: String,
        outputPath: String,
        report: CompatibilityReport,
        maxTargetHeight: Int
    ): List<String> {
        val args = mutableListOf(
            executablePath,
            "-y",
            "-hide_banner",
            "-loglevel", "error",
            "-progress", "pipe:1",
            "-i", inputPath,
            "-map", "0:v:0"
        )

        val audioOrdinal = report.selectedAudioTrack?.audioStreamOrdinal
        if (audioOrdinal != null) {
            args += listOf("-map", "0:a:$audioOrdinal")
        }

        // Preserve A/V sync timestamps cleanly
        args += listOf("-avoid_negative_ts", "make_zero")

        when (report.strategy) {
            PlaybackStrategy.REMUX -> {
                args += listOf("-c:v", "copy", "-c:a", "copy")
            }

            PlaybackStrategy.AUDIO_ONLY_TRANSCODE -> {
                // CRITICAL: Preserve original 4K/1080p video bitstream with ZERO re-encoding
                args += listOf("-c:v", "copy")
                appendAudioEncodeArgs(args, report.audioConversionPlan)
            }

            PlaybackStrategy.VIDEO_ONLY_TRANSCODE -> {
                appendVideoEncodeArgs(args, report, maxTargetHeight)
                // CRITICAL: Copy compatible original audio bitstream untouched
                args += listOf("-c:a", "copy")
            }

            PlaybackStrategy.FULL_TRANSCODE,
            PlaybackStrategy.CONSERVATIVE_FALLBACK -> {
                val cap = if (report.strategy == PlaybackStrategy.CONSERVATIVE_FALLBACK) {
                    1080
                } else {
                    maxTargetHeight
                }
                appendVideoEncodeArgs(args, report, cap)
                appendAudioEncodeArgs(args, report.audioConversionPlan)
            }

            else -> {
                args += listOf("-c:v", "copy", "-c:a", "copy")
            }
        }

        // Faststart puts MOOV atom at head of MP4 for instant Chromecast HTTP Range seeking
        args += listOf("-movflags", "+faststart", "-f", "mp4", outputPath)
        return args
    }

    suspend fun execute(
        inputFile: File,
        tempPartOutputFile: File,
        report: CompatibilityReport,
        maxTargetHeight: Int,
        durationMs: Long,
        onProgress: (Int) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val binary = binaryFile()
        if (!binary.exists() || !binary.canExecute()) {
            throw IllegalStateException("Bundled native FFmpeg binary (libffmpeg.so) is not installed in this build.")
        }

        val command = buildCommand(
            executablePath = binary.absolutePath,
            inputPath = inputFile.absolutePath,
            outputPath = tempPartOutputFile.absolutePath,
            report = report,
            maxTargetHeight = maxTargetHeight
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
        val errorTail = ArrayDeque<String>(12)

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!isActive) {
                        process.destroyForcibly()
                        throw CancellationException("FFmpeg conversion cancelled")
                    }
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("out_time_us=")) {
                        val us = trimmed.removePrefix("out_time_us=").toLongOrNull()
                        if (us != null && us >= 0L) {
                            val pct = ((us * 100L) / durationUs).toInt().coerceIn(0, 99)
                            onProgress(pct)
                        }
                    } else if (trimmed.isNotEmpty() && !trimmed.contains("=")) {
                        if (errorTail.size >= 12) errorTail.removeFirst()
                        errorTail.addLast(trimmed)
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "FFmpeg exited with code $exitCode: ${errorTail.joinToString(" | ")}"
                )
            }
            onProgress(100)
        } catch (t: Throwable) {
            runCatching { process.destroyForcibly() }
            throw t
        }
    }

    private fun appendVideoEncodeArgs(
        args: MutableList<String>,
        report: CompatibilityReport,
        maxTargetHeight: Int
    ) {
        val sourceH = report.technicalInfo.height
        val targetCap = minOf(report.receiverProfile.maxHeight, maxTargetHeight)
        val filters = mutableListOf<String>()

        if (report.technicalInfo.primaryVideoTrack?.isInterlaced == true) {
            filters += "yadif=0:-1:0"
        }
        // Downscale ONLY if source exceeds ceiling — NEVER upscale lower-resolution video
        if (sourceH > targetCap && targetCap > 0) {
            filters += "scale=-2:$targetCap"
        }
        if (filters.isNotEmpty()) {
            args += listOf("-vf", filters.joinToString(","))
        }

        args += listOf(
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "20",
            "-profile:v", "high",
            "-level:v", "4.1",
            "-pix_fmt", "yuv420p"
        )
    }

    private fun appendAudioEncodeArgs(
        args: MutableList<String>,
        plan: AudioConversionPlan?
    ) {
        if (plan == null) {
            args += "-an"
            return
        }
        val audioFilters = mutableListOf("aresample=async=1:first_pts=0")
        if (plan.downmixRequired) {
            val srcChannels = plan.sourceTrack.channelCount ?: 6
            val panFilter = buildItuDownmixFilter(srcChannels, plan.targetChannels)
            if (panFilter != null) {
                audioFilters += panFilter
            }
        }

        args += listOf(
            "-c:a", "aac",
            "-profile:a", "aac_low",
            "-ar", plan.targetSampleRateHz.toString(),
            "-ac", plan.targetChannels.toString(),
            "-b:a", "${plan.targetBitrateBps / 1000}k",
            "-af", audioFilters.joinToString(",")
        )
    }

    companion object {
        /**
         * ITU-R BS.775 compliant downmix pan formulas preserving center, LFE, and surround energy
         * instead of dropping channels.
         */
        fun buildItuDownmixFilter(sourceChannels: Int, targetChannels: Int): String? = when {
            sourceChannels >= 8 && targetChannels == 6 ->
                "pan=5.1|FL=FL|FR=FR|FC=FC|LFE=LFE|BL=0.707*BL+0.707*SL|BR=0.707*BR+0.707*SR"

            sourceChannels >= 8 && targetChannels == 2 ->
                "pan=stereo|FL=0.5*FL+0.354*FC+0.354*BL+0.354*SL+0.25*LFE|FR=0.5*FR+0.354*FC+0.354*BR+0.354*SR+0.25*LFE"

            sourceChannels == 6 && targetChannels == 2 ->
                "pan=stereo|FL=0.5*FL+0.354*FC+0.354*BL+0.25*LFE|FR=0.5*FR+0.354*FC+0.354*BR+0.25*LFE"

            else -> null
        }
    }
}
