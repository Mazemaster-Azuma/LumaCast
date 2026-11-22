package com.example.core.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.core.common.AudioTrackInfo
import com.example.core.common.Formatters
import com.example.core.common.HdrFormat
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.OutputValidationResult
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.VideoItem
import com.example.core.common.VideoTrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Inspects complete local media files (every video, audio, and subtitle track)
 * using Android MediaExtractor, MediaMetadataRetriever, and MediaCodecList
 * without loading media files into RAM or decoding video frames.
 */
class MediaInspector(private val context: Context) {

    private val cachedDecoderMimes: Map<String, Boolean> by lazy {
        buildDecoderAvailabilityMap()
    }

    suspend fun inspect(video: VideoItem): MediaTechnicalInfo = withContext(Dispatchers.IO) {
        val videoTracks = mutableListOf<VideoTrackInfo>()
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val subtitleTracks = mutableListOf<SubtitleTrackInfo>()
        var durationMs = video.durationMs
        var overallBitrateBps: Long? = null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, video.uri, null)
            var audioOrdinal = 0
            var subtitleOrdinal = 0
            var primaryVideoFirstPtsUs = 0L

            // First pass: capture video tracks & primary video start PTS for A/V sync offset
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US) ?: continue
                if (mime.startsWith("video/")) {
                    val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else video.width
                    val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else video.height
                    val frameRate = extractFrameRate(format)
                    val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                        mapVideoProfile(mime, format.getInteger(MediaFormat.KEY_PROFILE))
                    } else null
                    val level = if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                        "L${format.getInteger(MediaFormat.KEY_LEVEL)}"
                    } else null
                    val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else null
                    if (format.containsKey(MediaFormat.KEY_DURATION) && durationMs <= 0L) {
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    }
                    val hdrFormat = detectHdrFormat(mime, format)
                    val colorSpace = detectColorSpace(format)
                    val pixelFormat = detectPixelFormat(hdrFormat, format)
                    val isInterlaced = detectInterlacing(format)
                    val firstPtsUs = readFirstTrackPtsUs(extractor, i)
                    if (videoTracks.isEmpty()) {
                        primaryVideoFirstPtsUs = firstPtsUs
                    }

                    videoTracks += VideoTrackInfo(
                        trackIndex = i,
                        mimeType = mime,
                        codecLabel = Formatters.friendlyVideoCodec(mime),
                        profile = profile,
                        level = level,
                        width = width,
                        height = height,
                        frameRate = frameRate,
                        bitrateBps = bitrate,
                        pixelFormat = pixelFormat,
                        colorSpace = colorSpace,
                        hdrFormat = hdrFormat,
                        aspectRatio = Formatters.aspectRatioLabel(width, height),
                        isInterlaced = isInterlaced,
                        hardwareDecoderAvailable = hasDecoderForMime(mime),
                        firstPresentationTimeUs = firstPtsUs
                    )
                }
            }

            // Second pass: capture every audio track and every embedded subtitle track independently
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US) ?: continue
                when {
                    mime.startsWith("audio/") -> {
                        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else null
                        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else null
                        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        } else null
                        val bitDepth = extractAudioBitDepth(format)
                        val audioProfile = extractAudioProfile(mime, format)
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        val normLang = Formatters.normalizeLanguageCode(rawLang)
                        val trackTitle = extractTrackTitle(format)
                        val isDefault = extractBooleanFlag(format, MediaFormat.KEY_IS_DEFAULT, audioOrdinal == 0)
                        val audioFirstPtsUs = readFirstTrackPtsUs(extractor, i)
                        val audioDelayMs = (audioFirstPtsUs - primaryVideoFirstPtsUs) / 1000L

                        audioTracks += AudioTrackInfo(
                            trackIndex = i,
                            audioStreamOrdinal = audioOrdinal,
                            mimeType = mime,
                            codecLabel = Formatters.friendlyAudioCodec(mime),
                            sampleRateHz = sampleRate,
                            bitrateBps = bitrate,
                            channelCount = channels,
                            channelLayout = Formatters.channelLayoutLabel(channels),
                            bitDepth = bitDepth,
                            audioProfile = audioProfile,
                            languageCode = normLang ?: rawLang,
                            languageDisplay = Formatters.languageDisplayName(normLang ?: rawLang),
                            title = trackTitle,
                            isDefault = isDefault,
                            audioDelayMs = audioDelayMs,
                            hardwareDecoderAvailable = hasDecoderForMime(mime),
                            uniqueId = "audio_${video.id.hashCode()}_${i}_${audioOrdinal}",
                            role = detectAudioRole(trackTitle),
                            origin = com.example.core.common.TrackOrigin.ORIGINAL
                        )
                        audioOrdinal++
                    }

                    isSubtitleMime(mime) -> {
                        val subFormat = mapSubtitleFormat(mime)
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        val normLang = Formatters.normalizeLanguageCode(rawLang)
                        val langDisplay = Formatters.languageDisplayName(normLang ?: rawLang)
                        val trackTitle = extractTrackTitle(format) ?: "$langDisplay (${subFormat.displayLabel})"
                        val isForced = extractBooleanFlag(format, MediaFormat.KEY_IS_FORCED_SUBTITLE, false) ||
                            trackTitle.contains("forced", ignoreCase = true)
                        val isDefault = extractBooleanFlag(format, MediaFormat.KEY_IS_DEFAULT, subtitleOrdinal == 0)
                        val isSdh = trackTitle.contains("sdh", ignoreCase = true) ||
                            trackTitle.contains("hearing", ignoreCase = true) ||
                            trackTitle.contains("cc", ignoreCase = true)

                        subtitleTracks += com.example.core.common.SubtitleTrackInfo(
                            trackId = "sub_emb_${video.id.hashCode()}_${i}_${subtitleOrdinal}",
                            castTrackId = 1000L + subtitleOrdinal,
                            sourceType = com.example.core.common.SubtitleSourceType.EMBEDDED,
                            embeddedTrackIndex = i,
                            subtitleStreamOrdinal = subtitleOrdinal,
                            uri = video.uri,
                            fileName = video.displayName,
                            languageCode = normLang ?: rawLang,
                            languageDisplay = langDisplay,
                            title = trackTitle,
                            format = subFormat,
                            isDefault = isDefault,
                            isForced = isForced,
                            isSdh = isSdh,
                            characterEncoding = "UTF-8"
                        )
                        subtitleOrdinal++
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to retriever fallback below
        } finally {
            runCatching { extractor.release() }
        }

        // Fill missing overall bitrate / duration / dimensions via MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        var retrieverWidth = video.width
        var retrieverHeight = video.height
        var retrieverFps: Float? = null
        try {
            retriever.setDataSource(context, video.uri)
            overallBitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
            if (retrieverWidth <= 0) {
                retrieverWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            }
            if (retrieverHeight <= 0) {
                retrieverHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            }
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toFloatOrNull()
            if (frameCount != null && durationMs > 0L) {
                retrieverFps = frameCount / (durationMs / 1000f)
            }
        } catch (_: Exception) {
            // Ignore retriever failures on exotic containers
        } finally {
            runCatching { retriever.release() }
        }

        val primaryVideo = videoTracks.firstOrNull()
        val primaryAudio = audioTracks.firstOrNull()
        val container = inferContainerName(video.extension, video.mimeType)

        MediaTechnicalInfo(
            containerFormat = container,
            mimeType = video.mimeType,
            videoCodecMime = primaryVideo?.mimeType,
            videoCodecLabel = primaryVideo?.codecLabel ?: "Unknown",
            videoProfile = primaryVideo?.profile,
            videoLevel = primaryVideo?.level,
            width = primaryVideo?.width?.takeIf { it > 0 } ?: retrieverWidth,
            height = primaryVideo?.height?.takeIf { it > 0 } ?: retrieverHeight,
            frameRate = primaryVideo?.frameRate ?: retrieverFps,
            bitrateBps = primaryVideo?.bitrateBps ?: overallBitrateBps,
            audioCodecMime = primaryAudio?.mimeType,
            audioCodecLabel = primaryAudio?.codecLabel ?: "None",
            audioChannels = primaryAudio?.channelCount,
            audioSampleRateHz = primaryAudio?.sampleRateHz,
            hdrFormat = primaryVideo?.hdrFormat ?: HdrFormat.SDR,
            subtitleTrackCount = subtitleTracks.size,
            durationMs = durationMs,
            fileSizeBytes = video.sizeBytes,
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    private fun extractTrackTitle(format: MediaFormat): String? {
        return runCatching {
            when {
                format.containsKey("track-title") -> format.getString("track-title")
                format.containsKey("title") -> format.getString("title")
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun extractBooleanFlag(format: MediaFormat, key: String, fallback: Boolean): Boolean {
        return runCatching {
            if (format.containsKey(key)) format.getInteger(key) != 0 else fallback
        }.getOrDefault(fallback)
    }

    private fun detectAudioRole(title: String?): com.example.core.common.AudioTrackRole {
        val lower = title?.lowercase(Locale.US) ?: return com.example.core.common.AudioTrackRole.MAIN
        return when {
            lower.contains("commentary") || lower.contains("director") ->
                com.example.core.common.AudioTrackRole.COMMENTARY
            lower.contains("descriptive") || lower.contains("audio description") ||
                lower.contains("visually impaired") || lower.endsWith(" ad") ->
                com.example.core.common.AudioTrackRole.DESCRIPTIVE
            lower.contains("dub") ->
                com.example.core.common.AudioTrackRole.DUB
            else -> com.example.core.common.AudioTrackRole.MAIN
        }
    }

    private fun isSubtitleMime(mime: String): Boolean {
        return mime.startsWith("text/") ||
            mime.contains("subrip") ||
            mime.contains("webvtt") ||
            mime.contains("ttml") ||
            mime.contains("ssa") ||
            mime.contains("ass") ||
            mime.contains("cea-608") ||
            mime.contains("cea-708") ||
            mime.contains("pgs") ||
            mime.contains("vobsub") ||
            mime.contains("dvbsubs")
    }

    private fun mapSubtitleFormat(mime: String): com.example.core.common.SubtitleFormat = when {
        mime.contains("vtt") -> com.example.core.common.SubtitleFormat.WEBVTT
        mime.contains("subrip") || mime.contains("srt") -> com.example.core.common.SubtitleFormat.SRT
        mime.contains("ass") -> com.example.core.common.SubtitleFormat.ASS
        mime.contains("ssa") -> com.example.core.common.SubtitleFormat.SSA
        mime.contains("ttml") || mime.contains("dfxp") -> com.example.core.common.SubtitleFormat.TTML
        mime.contains("pgs") -> com.example.core.common.SubtitleFormat.PGS
        mime.contains("vobsub") || mime.contains("dvbsubs") -> com.example.core.common.SubtitleFormat.VOBSUB
        else -> com.example.core.common.SubtitleFormat.UNKNOWN
    }

    /**
     * Validates a prepared MP4 output file before exposing it to the local HTTP server.
     * Ensures non-zero size, valid video track, valid audio track (when expected),
     * and reasonable A/V start timestamp alignment.
     */
    suspend fun validatePreparedOutput(
        file: File,
        expectAudioTrack: Boolean
    ): OutputValidationResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            return@withContext OutputValidationResult(
                isValid = false,
                hasVideoTrack = false,
                hasAudioTrack = false,
                durationMs = 0L,
                avSyncDeltaMs = 0L,
                reason = "Converted file is empty or missing."
            )
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var durationMs = 0L
            var videoPtsUs: Long? = null
            var audioPtsUs: Long? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = maxOf(durationMs, format.getLong(MediaFormat.KEY_DURATION) / 1000L)
                }
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true
                    videoPtsUs = readFirstTrackPtsUs(extractor, i)
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    audioPtsUs = readFirstTrackPtsUs(extractor, i)
                }
            }

            val syncDeltaMs = if (videoPtsUs != null && audioPtsUs != null) {
                abs(audioPtsUs - videoPtsUs) / 1000L
            } else 0L

            when {
                !hasVideo -> OutputValidationResult(
                    isValid = false,
                    hasVideoTrack = false,
                    hasAudioTrack = hasAudio,
                    durationMs = durationMs,
                    avSyncDeltaMs = syncDeltaMs,
                    reason = "Converted MP4 does not contain a readable video track."
                )

                expectAudioTrack && !hasAudio -> OutputValidationResult(
                    isValid = false,
                    hasVideoTrack = true,
                    hasAudioTrack = false,
                    durationMs = durationMs,
                    avSyncDeltaMs = syncDeltaMs,
                    reason = "Converted MP4 is missing its audio track."
                )

                else -> OutputValidationResult(
                    isValid = true,
                    hasVideoTrack = true,
                    hasAudioTrack = hasAudio,
                    durationMs = durationMs,
                    avSyncDeltaMs = syncDeltaMs,
                    reason = null
                )
            }
        } catch (e: Exception) {
            OutputValidationResult(
                isValid = false,
                hasVideoTrack = false,
                hasAudioTrack = false,
                durationMs = 0L,
                avSyncDeltaMs = 0L,
                reason = "Failed to validate converted MP4: ${e.message ?: "corrupt container"}"
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun hasDecoderForMime(mime: String): Boolean {
        return cachedDecoderMimes[mime.lowercase(Locale.US)] == true
    }

    private fun buildDecoderAvailabilityMap(): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    map[type.lowercase(Locale.US)] = true
                }
            }
        }
        return map
    }

    private fun readFirstTrackPtsUs(extractor: MediaExtractor, trackIndex: Int): Long {
        return runCatching {
            extractor.selectTrack(trackIndex)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val pts = extractor.sampleTime.coerceAtLeast(0L)
            extractor.unselectTrack(trackIndex)
            pts
        }.getOrDefault(0L)
    }

    private fun extractFrameRate(format: MediaFormat): Float? {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrElse {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
            }
    }

    private fun detectHdrFormat(mime: String, format: MediaFormat): HdrFormat {
        if (mime == "video/dolby-vision") return HdrFormat.DOLBY_VISION
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            return when (format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> HdrFormat.HDR10
                MediaFormat.COLOR_TRANSFER_HLG -> HdrFormat.HLG
                else -> HdrFormat.SDR
            }
        }
        return HdrFormat.SDR
    }

    private fun detectColorSpace(format: MediaFormat): String? {
        if (!format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) return null
        return when (format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
            MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
            MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
            MediaFormat.COLOR_STANDARD_BT601_NTSC,
            MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601"
            else -> null
        }
    }

    private fun detectPixelFormat(hdr: HdrFormat, format: MediaFormat): String {
        val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
            format.getInteger(MediaFormat.KEY_PROFILE)
        } else -1
        return when {
            hdr != HdrFormat.SDR || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "yuv420p10le (10-bit)"
            else -> "yuv420p (8-bit)"
        }
    }

    private fun detectInterlacing(format: MediaFormat): Boolean {
        return runCatching {
            if (format.containsKey("interlaced")) format.getInteger("interlaced") != 0 else false
        }.getOrDefault(false)
    }

    private fun extractAudioBitDepth(format: MediaFormat): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        ) {
            return when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_FLOAT -> 32
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                else -> 16
            }
        }
        return null
    }

    private fun extractAudioProfile(mime: String, format: MediaFormat): String? {
        if (mime == "audio/mp4a-latm" && format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
            return when (format.getInteger(MediaFormat.KEY_AAC_PROFILE)) {
                MediaCodecInfo.CodecProfileLevel.AACObjectLC -> "AAC-LC"
                MediaCodecInfo.CodecProfileLevel.AACObjectHE -> "HE-AAC"
                MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS -> "HE-AAC v2"
                MediaCodecInfo.CodecProfileLevel.AACObjectMain -> "AAC Main"
                else -> "AAC"
            }
        }
        return null
    }

    private fun mapVideoProfile(mime: String, profile: Int): String = when (mime) {
        "video/avc" -> when (profile) {
            1 -> "Baseline"
            2 -> "Main"
            8 -> "High"
            16 -> "High 10"
            else -> "Profile $profile"
        }

        "video/hevc" -> when (profile) {
            1 -> "Main"
            2 -> "Main 10"
            4096 -> "Main 10 HDR10"
            else -> "Profile $profile"
        }

        else -> "Profile $profile"
    }

    companion object {
        fun inferContainerName(extension: String, mimeType: String): String {
            val ext = extension.lowercase(Locale.US)
            return when {
                ext == "mp4" || ext == "m4v" || mimeType.contains("mp4") -> "MP4"
                ext == "mkv" || mimeType.contains("matroska") -> "MKV"
                ext == "webm" || mimeType.contains("webm") -> "WebM"
                ext == "mov" || mimeType.contains("quicktime") -> "MOV"
                ext == "ts" || ext == "m2ts" || mimeType.contains("mp2t") -> "MPEG-TS"
                ext == "avi" || mimeType.contains("msvideo") -> "AVI"
                ext == "3gp" -> "3GP"
                ext.isNotBlank() -> ext.uppercase(Locale.US)
                else -> "UNKNOWN"
            }
        }
    }
}
