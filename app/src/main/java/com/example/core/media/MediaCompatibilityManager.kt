package com.example.core.media

import com.example.core.common.AudioConversionPlan
import com.example.core.common.AudioTrackInfo
import com.example.core.common.CompatibilityIssue
import com.example.core.common.CompatibilityReport
import com.example.core.common.Formatters
import com.example.core.common.HdrFormat
import com.example.core.common.IssueSeverity
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ProcessingBackendType
import com.example.core.common.ReceiverProfile
import java.util.Locale

/**
 * Intelligent 6-strategy media compatibility & conversion decision engine.
 *
 * Independently evaluates container, video track, and selected audio track against
 * the target Chromecast ReceiverProfile:
 * - Strategy A: DIRECT_CAST (zero conversion, original quality)
 * - Strategy B: REMUX (lossless container repackaging to MP4)
 * - Strategy C: AUDIO_ONLY_TRANSCODE (preserve original video bitstream untouched, convert audio to AAC-LC)
 * - Strategy D: VIDEO_ONLY_TRANSCODE (preserve compatible audio bitstream untouched, convert video to H.264)
 * - Strategy E: FULL_TRANSCODE (convert both tracks to H.264 + AAC-LC MP4)
 * - Strategy F: CONSERVATIVE_FALLBACK (1080p SDR H.264 + AAC-LC Stereo 192kbps safe retry)
 */
class MediaCompatibilityManager {

    fun evaluate(
        videoId: String,
        info: MediaTechnicalInfo,
        profile: ReceiverProfile,
        autoRemuxEnabled: Boolean = true,
        allowHardwareTranscode: Boolean = true,
        maxTranscodeHeight: Int = 1080,
        selectedAudioTrackIndex: Int? = null,
        preferredAudioLanguage: String = "auto",
        preferAc3Passthrough: Boolean = true,
        ffmpegAvailable: Boolean = false
    ): CompatibilityReport {
        val issues = mutableListOf<CompatibilityIssue>()
        val container = info.containerFormat.uppercase(Locale.US)
        val primaryVideo = info.primaryVideoTrack
        val videoMime = (primaryVideo?.mimeType ?: info.videoCodecMime)?.lowercase(Locale.US)
        val videoLabel = primaryVideo?.codecLabel ?: info.videoCodecLabel

        // 1. Resolve target audio track independently
        val selectedAudio = selectAudioTrack(
            info = info,
            explicitTrackIndex = selectedAudioTrackIndex,
            preferredLanguage = preferredAudioLanguage
        )
        val audioMime = selectedAudio?.mimeType?.lowercase(Locale.US)

        if (videoMime == null) {
            issues += CompatibilityIssue(
                component = "Video Track",
                detail = "No readable video stream could be extracted from this $container file.",
                severity = IssueSeverity.BLOCKING
            )
            return CompatibilityReport(
                videoId = videoId,
                receiverProfile = profile,
                technicalInfo = info,
                strategy = PlaybackStrategy.UNSUPPORTED,
                userHeadline = "No readable video stream found in $container file.",
                summary = "Missing or unreadable video stream in $container container.",
                preservesOriginalQuality = false,
                videoCompatible = false,
                audioCompatible = selectedAudio != null,
                containerCompatible = false,
                selectedAudioTrack = selectedAudio,
                audioConversionPlan = null,
                outputVideoCodec = "None",
                outputAudioCodec = selectedAudio?.codecLabel ?: "None",
                outputHdrFormat = HdrFormat.SDR,
                recommendedBackend = ProcessingBackendType.DIRECT,
                issues = issues,
                outputMimeType = info.mimeType,
                estimatedTargetResolution = Formatters.resolutionTag(info.width, info.height),
                estimatedOutputBytes = 0L
            )
        }

        // 2. Evaluate Video Compatibility independently
        val videoCodecSupported = isVideoCodecSupported(videoMime, profile)
        val resolutionSupported = (info.width <= 0 || info.width <= profile.maxWidth) &&
            (info.height <= 0 || info.height <= profile.maxHeight)
        val hdrSupported = isHdrSupported(info.hdrFormat, profile)
        val interlaced = primaryVideo?.isInterlaced == true
        val videoTrackCompatible = videoCodecSupported && resolutionSupported && hdrSupported && !interlaced

        // 3. Evaluate Audio Compatibility independently (never assume audio works just because video works!)
        val effectiveAc3Support = profile.supportsAc3Passthrough && preferAc3Passthrough
        val audioCodecSupported = selectedAudio == null ||
            isAudioCodecSupported(audioMime!!, profile, effectiveAc3Support)
        val audioChannelsSupported = selectedAudio == null ||
            isAudioChannelCountSupported(selectedAudio.channelCount, audioMime!!, profile)
        val audioTrackCompatible = audioCodecSupported && audioChannelsSupported

        // 4. Evaluate Container Compatibility & MP4 muxability
        val containerDirectSupported = container in DIRECT_CONTAINERS
        val containerCodecPairValid = when (container) {
            "WEBM" -> videoMime in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9") &&
                (audioMime == null || audioMime in setOf("audio/opus", "audio/vorbis"))
            "MP4" -> videoMime in setOf("video/avc", "video/hevc", "video/dolby-vision") &&
                (audioMime == null || isMp4MuxableAudio(audioMime))
            else -> false
        }

        // Record detailed findings
        if (!containerDirectSupported) {
            issues += CompatibilityIssue(
                component = "Container ($container)",
                detail = "Chromecast Default Media Receiver cannot play $container containers directly; requires MP4 repackaging.",
                severity = IssueSeverity.WARNING
            )
        }
        if (!videoCodecSupported) {
            issues += CompatibilityIssue(
                component = "Video Codec ($videoLabel)",
                detail = "$videoLabel is not natively decoded by ${profile.displayName}.",
                severity = IssueSeverity.WARNING
            )
        }
        if (!resolutionSupported) {
            issues += CompatibilityIssue(
                component = "Resolution (${info.width}×${info.height})",
                detail = "Exceeds ${profile.displayName} ceiling (${profile.maxWidth}×${profile.maxHeight}).",
                severity = IssueSeverity.WARNING
            )
        }
        if (!hdrSupported) {
            issues += CompatibilityIssue(
                component = "HDR (${info.hdrFormat.label})",
                detail = "${info.hdrFormat.label} requires tone-mapping to SDR for ${profile.displayName}.",
                severity = IssueSeverity.WARNING
            )
        }
        if (interlaced) {
            issues += CompatibilityIssue(
                component = "Interlaced Video",
                detail = "Interlaced frames require deinterlacing to progressive H.264 for smooth TV playback.",
                severity = IssueSeverity.WARNING
            )
        }
        if (selectedAudio != null && !audioCodecSupported) {
            issues += CompatibilityIssue(
                component = "Audio Codec (${selectedAudio.codecLabel})",
                detail = "${selectedAudio.codecLabel} (${selectedAudio.channelLayout}) is not natively supported by ${profile.displayName}.",
                severity = IssueSeverity.WARNING
            )
        }
        if (selectedAudio != null && audioCodecSupported && !audioChannelsSupported) {
            issues += CompatibilityIssue(
                component = "Audio Channels (${selectedAudio.channelLayout})",
                detail = "${selectedAudio.channelLayout} exceeds supported channel layout on ${profile.displayName}; proper downmix required.",
                severity = IssueSeverity.WARNING
            )
        }
        if (info.subtitleTrackCount > 0) {
            issues += CompatibilityIssue(
                component = "Embedded Subtitles (${info.subtitleTrackCount})",
                detail = "Embedded subtitle tracks require a Custom Web Receiver or external WebVTT sidecar.",
                severity = IssueSeverity.INFO
            )
        }

        val mp4MuxableVideo = videoMime in setOf("video/avc", "video/hevc")
        val mp4MuxableAudio = audioMime == null || isMp4MuxableAudio(audioMime)

        // STRATEGY A: Direct Cast (Original file, zero conversion)
        if (containerDirectSupported && containerCodecPairValid && videoTrackCompatible && audioTrackCompatible) {
            val outMime = if (container == "WEBM") "video/webm" else "video/mp4"
            val audioPlan = selectedAudio?.let { buildPreserveAudioPlan(it) }
            return CompatibilityReport(
                videoId = videoId,
                receiverProfile = profile,
                technicalInfo = info,
                strategy = PlaybackStrategy.DIRECT_CAST,
                userHeadline = "Video and audio supported. Casting original file.",
                summary = "Native ${Formatters.resolutionTag(info.width, info.height)} $videoLabel + ${selectedAudio?.codecLabel ?: "no audio"} in $container streams directly with zero quality loss.",
                preservesOriginalQuality = true,
                videoCompatible = true,
                audioCompatible = true,
                containerCompatible = true,
                selectedAudioTrack = selectedAudio,
                audioConversionPlan = audioPlan,
                outputVideoCodec = videoLabel,
                outputAudioCodec = selectedAudio?.let { "${it.codecLabel} (${it.channelLayout})" } ?: "None",
                outputHdrFormat = info.hdrFormat,
                recommendedBackend = ProcessingBackendType.DIRECT,
                issues = issues,
                outputMimeType = outMime,
                estimatedTargetResolution = Formatters.resolutionTag(info.width, info.height),
                estimatedOutputBytes = info.fileSizeBytes
            )
        }

        // STRATEGY B: Container Remux (both video & audio compatible, MP4-muxable, only container incompatible)
        if (videoTrackCompatible && audioTrackCompatible && mp4MuxableVideo && mp4MuxableAudio) {
            if (!autoRemuxEnabled) {
                issues += CompatibilityIssue(
                    component = "Auto-Remux Disabled",
                    detail = "Enable Auto-Remux in Settings to repackage $container to MP4 without quality loss.",
                    severity = IssueSeverity.BLOCKING
                )
                return buildBlockedReport(
                    videoId = videoId,
                    info = info,
                    profile = profile,
                    selectedAudio = selectedAudio,
                    videoCompatible = true,
                    audioCompatible = true,
                    issues = issues,
                    headline = "$container requires lossless MP4 remuxing, which is disabled in Settings."
                )
            }
            val audioPlan = selectedAudio?.let { buildPreserveAudioPlan(it) }
            return CompatibilityReport(
                videoId = videoId,
                receiverProfile = profile,
                technicalInfo = info,
                strategy = PlaybackStrategy.REMUX,
                userHeadline = "Video and audio supported. Repackaging $container to MP4 without re-encoding.",
                summary = "Preserving original $videoLabel video and ${selectedAudio?.codecLabel ?: "audio"} bitstreams while changing container from $container to MP4.",
                preservesOriginalQuality = true,
                videoCompatible = true,
                audioCompatible = true,
                containerCompatible = false,
                selectedAudioTrack = selectedAudio,
                audioConversionPlan = audioPlan,
                outputVideoCodec = "$videoLabel (Copy)",
                outputAudioCodec = selectedAudio?.let { "${it.codecLabel} (Copy)" } ?: "None",
                outputHdrFormat = info.hdrFormat,
                recommendedBackend = if (ffmpegAvailable && info.audioTracks.size > 1) {
                    ProcessingBackendType.FFMPEG
                } else {
                    ProcessingBackendType.NATIVE_MUXER
                },
                issues = issues,
                outputMimeType = "video/mp4",
                estimatedTargetResolution = Formatters.resolutionTag(info.width, info.height),
                estimatedOutputBytes = (info.fileSizeBytes * 1.02).toLong().coerceAtLeast(1024L)
            )
        }

        // Any remaining path requires selective or full transcoding
        if (!allowHardwareTranscode) {
            issues += CompatibilityIssue(
                component = "Automatic Transcoding Disabled",
                detail = "Enable Hardware Transcode Fallback in Settings to convert incompatible tracks automatically.",
                severity = IssueSeverity.BLOCKING
            )
            return buildBlockedReport(
                videoId = videoId,
                info = info,
                profile = profile,
                selectedAudio = selectedAudio,
                videoCompatible = videoTrackCompatible,
                audioCompatible = audioTrackCompatible,
                issues = issues,
                headline = "Media requires track conversion, but automatic transcoding is disabled in Settings."
            )
        }

        // STRATEGY C: Audio-Only Transcode (Video compatible & MP4-muxable, ONLY audio incompatible)
        if (videoTrackCompatible && mp4MuxableVideo && selectedAudio != null && (!audioTrackCompatible || !mp4MuxableAudio)) {
            val audioPlan = buildAacConversionPlan(selectedAudio, profile)
            val needsFfmpegForAudio = audioMime in FFMPEG_PREFERRED_AUDIO_MIMES ||
                !selectedAudio.hardwareDecoderAvailable
            val backend = if (needsFfmpegForAudio && ffmpegAvailable) {
                ProcessingBackendType.FFMPEG
            } else if (needsFfmpegForAudio && !selectedAudio.hardwareDecoderAvailable) {
                ProcessingBackendType.FFMPEG
            } else {
                ProcessingBackendType.MEDIA3_TRANSFORMER
            }

            val audioDesc = if (audioPlan.downmixRequired) {
                "${audioPlan.targetCodecLabel} ${audioPlan.targetChannelLayout} (${audioPlan.targetBitrateBps / 1000} kbps, downmixed)"
            } else {
                "${audioPlan.targetCodecLabel} ${audioPlan.targetChannelLayout} (${audioPlan.targetBitrateBps / 1000} kbps)"
            }

            return CompatibilityReport(
                videoId = videoId,
                receiverProfile = profile,
                technicalInfo = info,
                strategy = PlaybackStrategy.AUDIO_ONLY_TRANSCODE,
                userHeadline = "Video supported. Converting ${selectedAudio.codecLabel} audio to AAC.",
                summary = "Preserving original ${Formatters.resolutionTag(info.width, info.height)} $videoLabel video untouched while converting ${selectedAudio.codecLabel} (${selectedAudio.channelLayout}) to $audioDesc.",
                preservesOriginalQuality = true,
                videoCompatible = true,
                audioCompatible = false,
                containerCompatible = containerDirectSupported,
                selectedAudioTrack = selectedAudio,
                audioConversionPlan = audioPlan,
                outputVideoCodec = "$videoLabel (Original Copy)",
                outputAudioCodec = audioDesc,
                outputHdrFormat = info.hdrFormat,
                recommendedBackend = backend,
                issues = issues,
                outputMimeType = "video/mp4",
                estimatedTargetResolution = Formatters.resolutionTag(info.width, info.height),
                estimatedOutputBytes = (info.fileSizeBytes * 1.05).toLong().coerceAtLeast(1024L)
            )
        }

        // Compute target video resolution without EVER upscaling
        val effectiveHeight = computeTargetHeight(info.height, profile.maxHeight, maxTranscodeHeight)
        val targetResTag = computeTargetResolutionTag(info.width, info.height, effectiveHeight)
        val targetHdr = if (hdrSupported && info.hdrFormat != HdrFormat.SDR && videoCodecSupported) {
            info.hdrFormat
        } else {
            HdrFormat.SDR // Never falsely label SDR output as HDR
        }

        // STRATEGY D: Video-Only Transcode (Audio compatible & MP4-muxable, ONLY video incompatible)
        if (!videoTrackCompatible && audioTrackCompatible && mp4MuxableAudio) {
            val audioPlan = selectedAudio?.let { buildPreserveAudioPlan(it) }
            val needsFfmpegForVideo = videoMime in FFMPEG_PREFERRED_VIDEO_MIMES ||
                primaryVideo?.hardwareDecoderAvailable == false
            val backend = if (needsFfmpegForVideo && ffmpegAvailable) {
                ProcessingBackendType.FFMPEG
            } else if (needsFfmpegForVideo && primaryVideo?.hardwareDecoderAvailable == false) {
                ProcessingBackendType.FFMPEG
            } else {
                ProcessingBackendType.MEDIA3_TRANSFORMER
            }

            return CompatibilityReport(
                videoId = videoId,
                receiverProfile = profile,
                technicalInfo = info,
                strategy = PlaybackStrategy.VIDEO_ONLY_TRANSCODE,
                userHeadline = "Video codec unsupported. Converting video while preserving compatible original audio.",
                summary = "Converting $videoLabel to H.264 ($targetResTag, ${targetHdr.label}) while copying original ${selectedAudio?.codecLabel ?: "audio"} untouched.",
                preservesOriginalQuality = false,
                videoCompatible = false,
                audioCompatible = true,
                containerCompatible = containerDirectSupported,
                selectedAudioTrack = selectedAudio,
                audioConversionPlan = audioPlan,
                outputVideoCodec = "H.264 / AVC ($targetResTag)",
                outputAudioCodec = selectedAudio?.let { "${it.codecLabel} (Original Copy)" } ?: "None",
                outputHdrFormat = targetHdr,
                recommendedBackend = backend,
                issues = issues,
                outputMimeType = "video/mp4",
                estimatedTargetResolution = targetResTag,
                estimatedOutputBytes = estimateTranscodedBytes(info, effectiveHeight)
            )
        }

        // STRATEGY E: Full Transcode (Both video and audio require conversion)
        val audioPlan = selectedAudio?.let { buildAacConversionPlan(it, profile) }
        val needsFfmpeg = (videoMime in FFMPEG_PREFERRED_VIDEO_MIMES || primaryVideo?.hardwareDecoderAvailable == false) ||
            (audioMime != null && (audioMime in FFMPEG_PREFERRED_AUDIO_MIMES || selectedAudio?.hardwareDecoderAvailable == false))
        val backend = if (needsFfmpeg && ffmpegAvailable) {
            ProcessingBackendType.FFMPEG
        } else if (needsFfmpeg && (primaryVideo?.hardwareDecoderAvailable == false || selectedAudio?.hardwareDecoderAvailable == false)) {
            ProcessingBackendType.FFMPEG
        } else {
            ProcessingBackendType.MEDIA3_TRANSFORMER
        }

        val outAudioText = audioPlan?.let {
            "${it.targetCodecLabel} ${it.targetChannelLayout} (${it.targetBitrateBps / 1000} kbps)"
        } ?: "None"

        return CompatibilityReport(
            videoId = videoId,
            receiverProfile = profile,
            technicalInfo = info,
            strategy = PlaybackStrategy.FULL_TRANSCODE,
            userHeadline = "Both audio and video require conversion.",
            summary = "Converting $videoLabel → H.264 ($targetResTag) and ${selectedAudio?.codecLabel ?: "audio"} → $outAudioText in MP4.",
            preservesOriginalQuality = false,
            videoCompatible = false,
            audioCompatible = false,
            containerCompatible = false,
            selectedAudioTrack = selectedAudio,
            audioConversionPlan = audioPlan,
            outputVideoCodec = "H.264 / AVC ($targetResTag)",
            outputAudioCodec = outAudioText,
            outputHdrFormat = HdrFormat.SDR,
            recommendedBackend = backend,
            issues = issues,
            outputMimeType = "video/mp4",
            estimatedTargetResolution = targetResTag,
            estimatedOutputBytes = estimateTranscodedBytes(info, effectiveHeight)
        )
    }

    /**
     * Strategy F: Conservative Compatibility Fallback when a receiver rejects
     * primary stream playback or hardware transcoding encounters an exotic profile.
     * Targets guaranteed Chromecast universal baseline: H.264 <=1080p SDR + AAC-LC Stereo 192 kbps 48 kHz MP4.
     */
    fun buildConservativeFallbackReport(
        videoId: String,
        info: MediaTechnicalInfo,
        selectedAudioTrack: AudioTrackInfo?,
        ffmpegAvailable: Boolean = false
    ): CompatibilityReport {
        val safeHeight = computeTargetHeight(info.height, 1080, 1080)
        val safeResTag = computeTargetResolutionTag(info.width, info.height, safeHeight)
        val stereoPlan = selectedAudioTrack?.let { track ->
            val downmix = (track.channelCount ?: 2) > 2
            AudioConversionPlan(
                sourceTrack = track,
                preserveOriginal = false,
                targetCodecMime = "audio/mp4a-latm",
                targetCodecLabel = "AAC-LC",
                targetSampleRateHz = 48_000,
                targetChannels = 2,
                targetChannelLayout = "Stereo (2.0)",
                targetBitrateBps = 192_000,
                downmixRequired = downmix,
                downmixDescription = if (downmix) "ITU-R BS.775 downmix (${track.channelLayout} → Stereo 2.0)" else null
            )
        }
        return CompatibilityReport(
            videoId = videoId,
            receiverProfile = ReceiverProfile.STANDARD_1080P,
            technicalInfo = info,
            strategy = PlaybackStrategy.CONSERVATIVE_FALLBACK,
            userHeadline = "Retrying with universal Chromecast H.264 / AAC-LC Stereo fallback.",
            summary = "Conservative universal profile: H.264 $safeResTag SDR + AAC-LC Stereo 192 kbps (48 kHz) MP4.",
            preservesOriginalQuality = false,
            videoCompatible = false,
            audioCompatible = false,
            containerCompatible = false,
            selectedAudioTrack = selectedAudioTrack,
            audioConversionPlan = stereoPlan,
            outputVideoCodec = "H.264 / AVC ($safeResTag SDR)",
            outputAudioCodec = "AAC-LC Stereo (192 kbps)",
            outputHdrFormat = HdrFormat.SDR,
            recommendedBackend = if (ffmpegAvailable) ProcessingBackendType.FFMPEG else ProcessingBackendType.MEDIA3_TRANSFORMER,
            issues = listOf(
                CompatibilityIssue(
                    component = "Conservative Fallback",
                    detail = "Receiver rejected initial stream; converting to guaranteed 1080p SDR H.264 + AAC-LC Stereo.",
                    severity = IssueSeverity.WARNING
                )
            ),
            outputMimeType = "video/mp4",
            estimatedTargetResolution = safeResTag,
            estimatedOutputBytes = estimateTranscodedBytes(info, safeHeight)
        )
    }

    fun selectAudioTrack(
        info: MediaTechnicalInfo,
        explicitTrackIndex: Int?,
        preferredLanguage: String
    ): AudioTrackInfo? {
        val tracks = info.audioTracks
        if (tracks.isNotEmpty()) {
            if (explicitTrackIndex != null) {
                tracks.firstOrNull { it.trackIndex == explicitTrackIndex }?.let { return it }
            }
            if (preferredLanguage.isNotBlank() && !preferredLanguage.equals("auto", ignoreCase = true)) {
                val pref = preferredLanguage.lowercase(Locale.US)
                tracks.firstOrNull { track ->
                    val code = track.languageCode?.lowercase(Locale.US)
                    code != null && (code == pref || code.startsWith(pref.take(2)))
                }?.let { return it }
            }
            return tracks.firstOrNull { it.isDefault } ?: tracks.first()
        }

        // Synthesize single audio track from summary fields if caller built MediaTechnicalInfo directly in tests
        val mime = info.audioCodecMime ?: return null
        return AudioTrackInfo(
            trackIndex = 1,
            audioStreamOrdinal = 0,
            mimeType = mime,
            codecLabel = info.audioCodecLabel,
            sampleRateHz = info.audioSampleRateHz,
            bitrateBps = null,
            channelCount = info.audioChannels,
            channelLayout = Formatters.channelLayoutLabel(info.audioChannels),
            bitDepth = null,
            audioProfile = null,
            languageCode = "und",
            languageDisplay = "Default",
            title = null,
            isDefault = true,
            audioDelayMs = 0L,
            hardwareDecoderAvailable = mime.lowercase(Locale.US) !in FFMPEG_PREFERRED_AUDIO_MIMES
        )
    }

    /**
     * Builds receiver-tailored AAC-LC audio conversion plan:
     * - Preserves 5.1 surround when receiver supports 5.1 AAC (never silently drops 5.1 to stereo!)
     * - Properly downmixes 7.1 (8ch) -> 5.1 (on 4K receiver) or Stereo 2.0 (on 1080p receiver)
     * - Scales stereo bitrate (192-256 kbps) and 5.1 bitrate (384-512 kbps) to source quality
     */
    fun buildAacConversionPlan(
        source: AudioTrackInfo,
        profile: ReceiverProfile
    ): AudioConversionPlan {
        val srcChannels = (source.channelCount ?: 2).coerceAtLeast(1)
        val targetChannels = when {
            srcChannels == 1 -> 1
            srcChannels == 2 -> 2
            srcChannels in 3..6 && profile.supportsMultichannelAac51 -> 6
            srcChannels > 6 && profile.supportsMultichannelAac51 -> 6 // Downmix 7.1 -> 5.1
            else -> 2 // Proper ITU-R BS.775 downmix to stereo
        }
        val downmixRequired = srcChannels > targetChannels
        val downmixDesc = if (downmixRequired) {
            "ITU-R BS.775 downmix (${Formatters.channelLayoutLabel(srcChannels)} → ${Formatters.channelLayoutLabel(targetChannels)})"
        } else null

        val highQualitySource = (source.bitrateBps ?: 0L) >= 320_000L ||
            source.mimeType.lowercase(Locale.US) in LOSSLESS_OR_HIGH_BITRATE_AUDIO
        val targetBitrate = when (targetChannels) {
            1 -> 128_000
            2 -> if (highQualitySource) 256_000 else 192_000
            else -> if (highQualitySource) 512_000 else 384_000
        }

        val sampleRate = when (source.sampleRateHz) {
            44_100 -> 44_100
            else -> 48_000
        }

        return AudioConversionPlan(
            sourceTrack = source,
            preserveOriginal = false,
            targetCodecMime = "audio/mp4a-latm",
            targetCodecLabel = "AAC-LC",
            targetSampleRateHz = sampleRate,
            targetChannels = targetChannels,
            targetChannelLayout = Formatters.channelLayoutLabel(targetChannels),
            targetBitrateBps = targetBitrate,
            downmixRequired = downmixRequired,
            downmixDescription = downmixDesc
        )
    }

    private fun buildPreserveAudioPlan(source: AudioTrackInfo): AudioConversionPlan {
        val channels = (source.channelCount ?: 2).coerceAtLeast(1)
        return AudioConversionPlan(
            sourceTrack = source,
            preserveOriginal = true,
            targetCodecMime = source.mimeType,
            targetCodecLabel = source.codecLabel,
            targetSampleRateHz = source.sampleRateHz ?: 48_000,
            targetChannels = channels,
            targetChannelLayout = source.channelLayout,
            targetBitrateBps = (source.bitrateBps ?: 192_000L).toInt(),
            downmixRequired = false,
            downmixDescription = null
        )
    }

    private fun buildBlockedReport(
        videoId: String,
        info: MediaTechnicalInfo,
        profile: ReceiverProfile,
        selectedAudio: AudioTrackInfo?,
        videoCompatible: Boolean,
        audioCompatible: Boolean,
        issues: List<CompatibilityIssue>,
        headline: String
    ) = CompatibilityReport(
        videoId = videoId,
        receiverProfile = profile,
        technicalInfo = info,
        strategy = PlaybackStrategy.UNSUPPORTED,
        userHeadline = headline,
        summary = headline,
        preservesOriginalQuality = false,
        videoCompatible = videoCompatible,
        audioCompatible = audioCompatible,
        containerCompatible = false,
        selectedAudioTrack = selectedAudio,
        audioConversionPlan = null,
        outputVideoCodec = info.videoCodecLabel,
        outputAudioCodec = selectedAudio?.codecLabel ?: "None",
        outputHdrFormat = info.hdrFormat,
        recommendedBackend = ProcessingBackendType.DIRECT,
        issues = issues,
        outputMimeType = info.mimeType,
        estimatedTargetResolution = Formatters.resolutionTag(info.width, info.height),
        estimatedOutputBytes = 0L
    )

    private fun isVideoCodecSupported(mime: String, profile: ReceiverProfile): Boolean = when (mime) {
        "video/avc", "video/x-vnd.on2.vp8" -> true
        "video/hevc" -> profile.supportsHevc
        "video/x-vnd.on2.vp9" -> profile.supportsVp9
        "video/dolby-vision" -> profile.supportsDolbyVision
        else -> false // Xvid, DivX, MPEG-2, MPEG-4, AV1 on older receivers
    }

    private fun isAudioCodecSupported(
        mime: String,
        profile: ReceiverProfile,
        allowAc3Passthrough: Boolean
    ): Boolean = when (mime) {
        "audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/vorbis" -> true
        "audio/ac3", "audio/eac3", "audio/eac3-joc" -> allowAc3Passthrough
        // DTS, DTS-HD, Dolby TrueHD, FLAC in MP4/MKV, ALAC, PCM/WAV require conversion to AAC
        else -> false
    }

    /**
     * Evaluates every discovered audio track independently against [profile] so the UI
     * and playback controller know exactly which tracks are natively compatible vs which
     * require on-demand audio-only conversion, without converting all tracks unnecessarily.
     */
    fun annotateAudioTrackCompatibility(
        tracks: List<AudioTrackInfo>,
        profile: ReceiverProfile,
        preferAc3Passthrough: Boolean = true
    ): List<AudioTrackInfo> {
        val effectiveAc3 = profile.supportsAc3Passthrough && preferAc3Passthrough
        return tracks.map { track ->
            val mime = track.mimeType.lowercase(Locale.US)
            val codecOk = isAudioCodecSupported(mime, profile, effectiveAc3)
            val channelsOk = isAudioChannelCountSupported(track.channelCount, mime, profile)
            track.copy(isCompatibleWithReceiver = codecOk && channelsOk)
        }
    }

    private fun isAudioChannelCountSupported(
        channels: Int?,
        audioMime: String,
        profile: ReceiverProfile
    ): Boolean {
        val ch = channels ?: 2
        if (ch <= 2) return true
        if (ch <= 6) {
            // 5.1 supported only on 4K/Google TV receivers for AAC/AC-3/E-AC-3
            return profile.supportsMultichannelAac51 && audioMime in setOf(
                "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/eac3-joc"
            )
        }
        // 7.1 (8ch) is not reliably decoded directly by Default Media Receiver; needs downmix
        return false
    }

    private fun isMp4MuxableAudio(mime: String): Boolean = mime in setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/ac3", "audio/eac3", "audio/eac3-joc"
    )

    private fun isHdrSupported(hdr: HdrFormat, profile: ReceiverProfile): Boolean = when (hdr) {
        HdrFormat.SDR -> true
        HdrFormat.HDR10, HdrFormat.HLG -> profile.supportsHdr10
        HdrFormat.DOLBY_VISION -> profile.supportsDolbyVision
    }

    private fun computeTargetHeight(sourceHeight: Int, receiverMax: Int, userCap: Int): Int {
        val cap = minOf(receiverMax, userCap).coerceAtLeast(360)
        return if (sourceHeight > 0) minOf(sourceHeight, cap) else cap
    }

    private fun computeTargetResolutionTag(sourceW: Int, sourceH: Int, targetH: Int): String {
        val targetW = if (sourceW > 0 && sourceH > 0) (sourceW * targetH) / sourceH else 0
        return Formatters.resolutionTag(targetW, targetH)
    }

    private fun estimateTranscodedBytes(info: MediaTechnicalInfo, targetHeight: Int): Long {
        if (info.durationMs <= 0L) return info.fileSizeBytes.coerceAtLeast(50L * 1024 * 1024)
        val estimatedVideoBps = when {
            targetHeight >= 2100 -> 18_000_000L
            targetHeight >= 1400 -> 10_000_000L
            targetHeight >= 1000 -> 6_500_000L
            else -> 3_000_000L
        }
        val seconds = (info.durationMs / 1000L).coerceAtLeast(1L)
        return ((estimatedVideoBps + 256_000L) / 8L) * seconds
    }

    companion object {
        private val DIRECT_CONTAINERS = setOf("MP4", "WEBM")

        private val FFMPEG_PREFERRED_AUDIO_MIMES = setOf(
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/vnd.dts.hd;profile=lbr",
            "audio/vnd.dts.uhd",
            "audio/true-hd",
            "audio/alac"
        )

        private val FFMPEG_PREFERRED_VIDEO_MIMES = setOf(
            "video/x-xvid",
            "video/xvid",
            "video/divx",
            "video/x-divx",
            "video/mpeg2"
        )

        private val LOSSLESS_OR_HIGH_BITRATE_AUDIO = setOf(
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
            "audio/flac",
            "audio/alac",
            "audio/raw",
            "audio/wav",
            "audio/eac3",
            "audio/eac3-joc"
        )
    }
}
