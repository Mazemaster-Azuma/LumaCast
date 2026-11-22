package com.example

import com.example.core.common.AudioTrackInfo
import com.example.core.common.HdrFormat
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ProcessingBackendType
import com.example.core.common.ReceiverProfile
import com.example.core.common.VideoTrackInfo
import com.example.core.media.MediaCompatibilityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive unit test suite covering the 6-strategy intelligent video & audio
 * compatibility decision engine across containers, video codecs, audio codecs,
 * multichannel surround preservation/downmixing, multi-language selection,
 * and Strategy F conservative fallback.
 */
class MediaCompatibilityManagerTest {

    private val manager = MediaCompatibilityManager()

    private fun sampleInfo(
        container: String = "MP4",
        mime: String = "video/mp4",
        videoMime: String? = "video/avc",
        videoLabel: String = "H.264 / AVC",
        width: Int = 1920,
        height: Int = 1080,
        audioMime: String? = "audio/mp4a-latm",
        audioLabel: String = "AAC",
        audioChannels: Int = 2,
        audioSampleRateHz: Int = 48_000,
        hdr: HdrFormat = HdrFormat.SDR,
        isInterlaced: Boolean = false,
        videoHwDecoder: Boolean = true,
        audioHwDecoder: Boolean = true,
        audioTracks: List<AudioTrackInfo>? = null
    ): MediaTechnicalInfo {
        val vTracks = if (videoMime != null) {
            listOf(
                VideoTrackInfo(
                    trackIndex = 0,
                    mimeType = videoMime,
                    codecLabel = videoLabel,
                    profile = "High",
                    level = "L4.1",
                    width = width,
                    height = height,
                    frameRate = 24f,
                    bitrateBps = 12_000_000L,
                    pixelFormat = "yuv420p",
                    colorSpace = if (hdr == HdrFormat.SDR) "BT.709" else "BT.2020",
                    hdrFormat = hdr,
                    aspectRatio = "16:9",
                    isInterlaced = isInterlaced,
                    hardwareDecoderAvailable = videoHwDecoder
                )
            )
        } else {
            emptyList()
        }

        val aTracks = audioTracks ?: if (audioMime != null) {
            listOf(
                AudioTrackInfo(
                    trackIndex = 1,
                    audioStreamOrdinal = 0,
                    mimeType = audioMime,
                    codecLabel = audioLabel,
                    sampleRateHz = audioSampleRateHz,
                    bitrateBps = 384_000L,
                    channelCount = audioChannels,
                    channelLayout = when (audioChannels) {
                        1 -> "Mono (1.0)"
                        2 -> "Stereo (2.0)"
                        6 -> "5.1 Surround"
                        8 -> "7.1 Surround"
                        else -> "${audioChannels}ch"
                    },
                    bitDepth = 16,
                    audioProfile = null,
                    languageCode = "en",
                    languageDisplay = "English",
                    title = "Main Audio",
                    isDefault = true,
                    audioDelayMs = 0L,
                    hardwareDecoderAvailable = audioHwDecoder
                )
            )
        } else {
            emptyList()
        }

        return MediaTechnicalInfo(
            containerFormat = container,
            mimeType = mime,
            videoCodecMime = videoMime,
            videoCodecLabel = videoLabel,
            videoProfile = "High",
            videoLevel = "L4.1",
            width = width,
            height = height,
            frameRate = 24f,
            bitrateBps = 12_000_000L,
            audioCodecMime = audioMime,
            audioCodecLabel = audioLabel,
            audioChannels = audioChannels,
            audioSampleRateHz = audioSampleRateHz,
            hdrFormat = hdr,
            subtitleTrackCount = 0,
            durationMs = 5_400_000L,
            fileSizeBytes = 2_400_000_000L,
            videoTracks = vTracks,
            audioTracks = aTracks
        )
    }

    // Case 1: MP4 with H.264 + AAC -> Strategy A (Direct Cast)
    @Test
    fun `case 1 - MP4 H264 AAC selects DIRECT_CAST with zero quality loss`() {
        val report = manager.evaluate(
            videoId = "c1",
            info = sampleInfo(),
            profile = ReceiverProfile.STANDARD_1080P
        )
        assertEquals(PlaybackStrategy.DIRECT_CAST, report.strategy)
        assertTrue(report.preservesOriginalQuality)
        assertTrue(report.videoCompatible)
        assertTrue(report.audioCompatible)
        assertTrue(report.containerCompatible)
        assertEquals("video/mp4", report.outputMimeType)
        assertEquals(ProcessingBackendType.DIRECT, report.recommendedBackend)
    }

    // Case 2: MKV with 4K HEVC + DTS -> Strategy C (Audio-Only Transcode, 4K HEVC preserved untouched!)
    @Test
    fun `case 2 - MKV 4K HEVC plus DTS selects AUDIO_ONLY_TRANSCODE preserving 4K HEVC video`() {
        val report = manager.evaluate(
            videoId = "c2",
            info = sampleInfo(
                container = "MKV",
                mime = "video/x-matroska",
                videoMime = "video/hevc",
                videoLabel = "H.265 / HEVC",
                width = 3840,
                height = 2160,
                audioMime = "audio/vnd.dts",
                audioLabel = "DTS",
                audioChannels = 6,
                audioHwDecoder = false
            ),
            profile = ReceiverProfile.ULTRA_4K_HDR,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, report.strategy)
        assertTrue(report.strategy.preservesVideoBitstream)
        assertFalse(report.strategy.preservesAudioBitstream)
        assertTrue(report.videoCompatible)
        assertFalse(report.audioCompatible)
        assertEquals("4K UHD", report.estimatedTargetResolution)
        assertEquals(ProcessingBackendType.FFMPEG, report.recommendedBackend)
        assertEquals("AAC-LC", report.audioConversionPlan?.targetCodecLabel)
        assertEquals(6, report.audioConversionPlan?.targetChannels)
    }

    // Case 3: MKV with H.264 + DTS-HD -> Strategy C (Audio-Only Transcode)
    @Test
    fun `case 3 - MKV H264 plus DTS-HD selects AUDIO_ONLY_TRANSCODE`() {
        val report = manager.evaluate(
            videoId = "c3",
            info = sampleInfo(
                container = "MKV",
                mime = "video/x-matroska",
                audioMime = "audio/vnd.dts.hd",
                audioLabel = "DTS-HD",
                audioChannels = 6,
                audioHwDecoder = false
            ),
            profile = ReceiverProfile.STANDARD_1080P,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, report.strategy)
        assertTrue(report.videoCompatible)
        assertFalse(report.audioCompatible)
        assertEquals("1080p", report.estimatedTargetResolution)
        // Standard 1080p receiver downmixes 5.1 DTS-HD -> Stereo 2.0 AAC-LC
        assertEquals(2, report.audioConversionPlan?.targetChannels)
        assertTrue(report.audioConversionPlan?.downmixRequired == true)
    }

    // Case 4: MKV with H.264 + FLAC -> Strategy C (Audio-Only Transcode)
    @Test
    fun `case 4 - MKV H264 plus FLAC selects AUDIO_ONLY_TRANSCODE without upscaling`() {
        val report = manager.evaluate(
            videoId = "c4",
            info = sampleInfo(
                container = "MKV",
                mime = "video/x-matroska",
                width = 1280,
                height = 720,
                audioMime = "audio/flac",
                audioLabel = "FLAC",
                audioChannels = 2,
                audioHwDecoder = true
            ),
            profile = ReceiverProfile.ULTRA_4K_HDR
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, report.strategy)
        assertTrue(report.videoCompatible)
        assertFalse(report.audioCompatible)
        assertEquals("720p", report.estimatedTargetResolution)
        assertEquals(ProcessingBackendType.MEDIA3_TRANSFORMER, report.recommendedBackend)
        assertEquals(256_000, report.audioConversionPlan?.targetBitrateBps)
    }

    // Case 5: MKV with unsupported video (Xvid) + AAC -> Strategy D (Video-Only Transcode, audio preserved)
    @Test
    fun `case 5 - MKV Xvid plus AAC selects VIDEO_ONLY_TRANSCODE preserving original AAC`() {
        val report = manager.evaluate(
            videoId = "c5",
            info = sampleInfo(
                container = "MKV",
                mime = "video/x-matroska",
                videoMime = "video/x-xvid",
                videoLabel = "Xvid / MPEG-4",
                width = 1280,
                height = 720,
                audioMime = "audio/mp4a-latm",
                audioLabel = "AAC",
                audioChannels = 2,
                videoHwDecoder = false
            ),
            profile = ReceiverProfile.STANDARD_1080P,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.VIDEO_ONLY_TRANSCODE, report.strategy)
        assertFalse(report.strategy.preservesVideoBitstream)
        assertTrue(report.strategy.preservesAudioBitstream)
        assertFalse(report.videoCompatible)
        assertTrue(report.audioCompatible)
        assertEquals(true, report.audioConversionPlan?.preserveOriginal)
        assertEquals("720p", report.estimatedTargetResolution)
        assertEquals(ProcessingBackendType.FFMPEG, report.recommendedBackend)
    }

    // Case 6: AVI with unsupported video + unsupported audio -> Strategy E (Full Transcode)
    @Test
    fun `case 6 - AVI Xvid plus PCM selects FULL_TRANSCODE`() {
        val report = manager.evaluate(
            videoId = "c6",
            info = sampleInfo(
                container = "AVI",
                mime = "video/x-msvideo",
                videoMime = "video/divx",
                videoLabel = "DivX",
                width = 1280,
                height = 720,
                audioMime = "audio/raw",
                audioLabel = "PCM",
                audioChannels = 2,
                videoHwDecoder = false
            ),
            profile = ReceiverProfile.STANDARD_1080P,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.FULL_TRANSCODE, report.strategy)
        assertFalse(report.videoCompatible)
        assertFalse(report.audioCompatible)
        assertFalse(report.preservesOriginalQuality)
        assertEquals("720p", report.estimatedTargetResolution)
        assertEquals(HdrFormat.SDR, report.outputHdrFormat)
    }

    // Case 7: 4K HEVC + Dolby TrueHD -> AUDIO_ONLY on 4K TV; FULL_TRANSCODE (1080p SDR) on 1080p TV
    @Test
    fun `case 7 - 4K HEVC HDR10 plus TrueHD preserves 4K video on 4K receiver and converts both on 1080p receiver`() {
        val info = sampleInfo(
            container = "MKV",
            mime = "video/x-matroska",
            videoMime = "video/hevc",
            videoLabel = "H.265 / HEVC",
            width = 3840,
            height = 2160,
            hdr = HdrFormat.HDR10,
            audioMime = "audio/true-hd",
            audioLabel = "Dolby TrueHD",
            audioChannels = 8,
            audioHwDecoder = false
        )

        val on4k = manager.evaluate(
            videoId = "c7-4k",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, on4k.strategy)
        assertEquals("4K UHD", on4k.estimatedTargetResolution)
        assertEquals(HdrFormat.HDR10, on4k.outputHdrFormat)
        assertEquals(6, on4k.audioConversionPlan?.targetChannels) // 7.1 -> 5.1 on 4K
        assertTrue(on4k.audioConversionPlan?.downmixRequired == true)

        val on1080p = manager.evaluate(
            videoId = "c7-hd",
            info = info,
            profile = ReceiverProfile.STANDARD_1080P,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.FULL_TRANSCODE, on1080p.strategy)
        assertEquals("1080p", on1080p.estimatedTargetResolution)
        assertEquals(HdrFormat.SDR, on1080p.outputHdrFormat) // Never mislabel SDR as HDR
        assertEquals(2, on1080p.audioConversionPlan?.targetChannels) // 7.1 -> 2.0 on 1080p
    }

    // Case 8: 1080p H.264 + AC-3 -> DIRECT_CAST on 4K (AC-3 passthrough) vs AUDIO_ONLY_TRANSCODE on 1080p
    @Test
    fun `case 8 - 1080p H264 plus AC3 casts directly on 4K receiver with passthrough and converts audio on HD receiver`() {
        val info = sampleInfo(
            container = "MP4",
            mime = "video/mp4",
            audioMime = "audio/ac3",
            audioLabel = "AC-3 (Dolby Digital)",
            audioChannels = 6
        )

        val on4kPassthrough = manager.evaluate(
            videoId = "c8-4k",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            preferAc3Passthrough = true
        )
        assertEquals(PlaybackStrategy.DIRECT_CAST, on4kPassthrough.strategy)

        val on4kDisabledPassthrough = manager.evaluate(
            videoId = "c8-4k-off",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            preferAc3Passthrough = false
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, on4kDisabledPassthrough.strategy)
        assertEquals(6, on4kDisabledPassthrough.audioConversionPlan?.targetChannels)

        val onStandard = manager.evaluate(
            videoId = "c8-hd",
            info = info,
            profile = ReceiverProfile.STANDARD_1080P,
            preferAc3Passthrough = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, onStandard.strategy)
        assertEquals(2, onStandard.audioConversionPlan?.targetChannels)
    }

    // Case 9: Multichannel 5.1 audio preserves 5.1 on 4K receiver and ITU downmixes on 1080p receiver
    @Test
    fun `case 9 - 5_1 audio preserves 5_1 at 384-512 kbps on 4K and downmixes to stereo on standard TV`() {
        val info = sampleInfo(
            container = "MKV",
            mime = "video/x-matroska",
            audioMime = "audio/vnd.dts",
            audioLabel = "DTS",
            audioChannels = 6
        )

        val on4k = manager.evaluate("c9-4k", info, ReceiverProfile.ULTRA_4K_HDR)
        val plan4k = on4k.audioConversionPlan
        assertNotNull(plan4k)
        assertEquals(6, plan4k!!.targetChannels)
        assertFalse(plan4k.downmixRequired)
        assertTrue(plan4k.targetBitrateBps in 384_000..512_000)

        val onHd = manager.evaluate("c9-hd", info, ReceiverProfile.STANDARD_1080P)
        val planHd = onHd.audioConversionPlan
        assertNotNull(planHd)
        assertEquals(2, planHd!!.targetChannels)
        assertTrue(planHd.downmixRequired)
        assertTrue(planHd.downmixDescription!!.contains("ITU-R BS.775"))
    }

    // Case 10: Unsupported 7.1 (8ch) audio downmixes cleanly to 5.1 (4K) or 2.0 (1080p)
    @Test
    fun `case 10 - 7_1 surround audio triggers proper ITU downmix even when codec is AAC`() {
        val info = sampleInfo(
            container = "MP4",
            mime = "video/mp4",
            audioMime = "audio/mp4a-latm",
            audioLabel = "AAC",
            audioChannels = 8
        )

        val on4k = manager.evaluate("c10-4k", info, ReceiverProfile.ULTRA_4K_HDR)
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, on4k.strategy)
        assertEquals(6, on4k.audioConversionPlan?.targetChannels)
        assertTrue(on4k.audioConversionPlan?.downmixRequired == true)

        val onHd = manager.evaluate("c10-hd", info, ReceiverProfile.STANDARD_1080P)
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, onHd.strategy)
        assertEquals(2, onHd.audioConversionPlan?.targetChannels)
        assertTrue(onHd.audioConversionPlan?.downmixRequired == true)
    }

    // Case 11: Multiple language audio tracks (preferred language + explicit track switches strategy!)
    @Test
    fun `case 11 - multi-language audio tracks respect preference and explicit user selection`() {
        val tracks = listOf(
            AudioTrackInfo(
                trackIndex = 1,
                audioStreamOrdinal = 0,
                mimeType = "audio/vnd.dts",
                codecLabel = "DTS",
                sampleRateHz = 48_000,
                bitrateBps = 1_509_000L,
                channelCount = 6,
                channelLayout = "5.1 Surround",
                bitDepth = 24,
                audioProfile = null,
                languageCode = "en",
                languageDisplay = "English",
                title = "English DTS 5.1",
                isDefault = true,
                hardwareDecoderAvailable = false
            ),
            AudioTrackInfo(
                trackIndex = 2,
                audioStreamOrdinal = 1,
                mimeType = "audio/mp4a-latm",
                codecLabel = "AAC",
                sampleRateHz = 48_000,
                bitrateBps = 192_000L,
                channelCount = 2,
                channelLayout = "Stereo (2.0)",
                bitDepth = 16,
                audioProfile = "LC",
                languageCode = "es",
                languageDisplay = "Spanish",
                title = "Español AAC",
                isDefault = false,
                hardwareDecoderAvailable = true
            ),
            AudioTrackInfo(
                trackIndex = 3,
                audioStreamOrdinal = 2,
                mimeType = "audio/mp4a-latm",
                codecLabel = "AAC",
                sampleRateHz = 48_000,
                bitrateBps = 192_000L,
                channelCount = 2,
                channelLayout = "Stereo (2.0)",
                bitDepth = 16,
                audioProfile = "LC",
                languageCode = "ja",
                languageDisplay = "Japanese",
                title = "日本語 AAC",
                isDefault = false,
                hardwareDecoderAvailable = true
            )
        )
        val info = sampleInfo(
            container = "MKV",
            mime = "video/x-matroska",
            audioMime = "audio/vnd.dts",
            audioLabel = "DTS",
            audioChannels = 6,
            audioTracks = tracks
        )

        // Default selects English DTS -> AUDIO_ONLY_TRANSCODE
        val defaultReport = manager.evaluate("c11-def", info, ReceiverProfile.STANDARD_1080P)
        assertEquals(1, defaultReport.selectedAudioTrack?.trackIndex)
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, defaultReport.strategy)

        // Preferred language "es" automatically picks Spanish AAC -> lossless REMUX!
        val spanishPrefReport = manager.evaluate(
            videoId = "c11-es",
            info = info,
            profile = ReceiverProfile.STANDARD_1080P,
            preferredAudioLanguage = "es"
        )
        assertEquals(2, spanishPrefReport.selectedAudioTrack?.trackIndex)
        assertEquals("es", spanishPrefReport.selectedAudioTrack?.languageCode)
        assertEquals(PlaybackStrategy.REMUX, spanishPrefReport.strategy)
        assertTrue(spanishPrefReport.preservesOriginalQuality)

        // Explicit user selection overrides preference (trackIndex = 3 -> Japanese AAC -> REMUX)
        val explicitJp = manager.evaluate(
            videoId = "c11-ja",
            info = info,
            profile = ReceiverProfile.STANDARD_1080P,
            selectedAudioTrackIndex = 3,
            preferredAudioLanguage = "es"
        )
        assertEquals(3, explicitJp.selectedAudioTrack?.trackIndex)
        assertEquals("ja", explicitJp.selectedAudioTrack?.languageCode)
        assertEquals(PlaybackStrategy.REMUX, explicitJp.strategy)
    }

    // Strategy B (MKV H.264 + AAC -> REMUX; disabled -> UNSUPPORTED) & WebM VP9/Opus -> DIRECT_CAST
    @Test
    fun `MKV H264 AAC selects lossless REMUX when enabled and UNSUPPORTED when disabled`() {
        val info = sampleInfo(container = "MKV", mime = "video/x-matroska")
        val enabled = manager.evaluate("remux-on", info, ReceiverProfile.STANDARD_1080P, autoRemuxEnabled = true)
        assertEquals(PlaybackStrategy.REMUX, enabled.strategy)
        assertTrue(enabled.preservesOriginalQuality)

        val disabled = manager.evaluate("remux-off", info, ReceiverProfile.STANDARD_1080P, autoRemuxEnabled = false)
        assertEquals(PlaybackStrategy.UNSUPPORTED, disabled.strategy)
    }

    @Test
    fun `WebM VP9 Opus selects DIRECT_CAST on 4K receiver`() {
        val report = manager.evaluate(
            videoId = "webm",
            info = sampleInfo(
                container = "WebM",
                mime = "video/webm",
                videoMime = "video/x-vnd.on2.vp9",
                videoLabel = "VP9",
                audioMime = "audio/opus",
                audioLabel = "Opus"
            ),
            profile = ReceiverProfile.ULTRA_4K_HDR
        )
        assertEquals(PlaybackStrategy.DIRECT_CAST, report.strategy)
        assertEquals("video/webm", report.outputMimeType)
    }

    // Strategy F: Conservative Compatibility Fallback (guaranteed 1080p SDR H.264 + AAC-LC Stereo 192kbps)
    @Test
    fun `strategy F conservative fallback produces universal 1080p SDR H264 plus AAC-LC Stereo 192kbps`() {
        val info = sampleInfo(
            container = "MKV",
            mime = "video/x-matroska",
            videoMime = "video/hevc",
            videoLabel = "H.265 / HEVC",
            width = 3840,
            height = 2160,
            hdr = HdrFormat.DOLBY_VISION,
            audioMime = "audio/true-hd",
            audioLabel = "Dolby TrueHD",
            audioChannels = 8
        )
        val fallback = manager.buildConservativeFallbackReport(
            videoId = "fallback",
            info = info,
            selectedAudioTrack = info.audioTracks.first(),
            ffmpegAvailable = false
        )
        assertEquals(PlaybackStrategy.CONSERVATIVE_FALLBACK, fallback.strategy)
        assertEquals("1080p", fallback.estimatedTargetResolution)
        assertEquals(HdrFormat.SDR, fallback.outputHdrFormat)
        assertEquals(2, fallback.audioConversionPlan?.targetChannels)
        assertEquals(192_000, fallback.audioConversionPlan?.targetBitrateBps)
        assertEquals(48_000, fallback.audioConversionPlan?.targetSampleRateHz)
        assertTrue(fallback.audioConversionPlan?.downmixRequired == true)
    }

    @Test
    fun `missing video track or disabled transcoding returns UNSUPPORTED with blocking issue`() {
        val missingVideo = manager.evaluate(
            videoId = "no-vid",
            info = sampleInfo(videoMime = null, videoLabel = "Unknown"),
            profile = ReceiverProfile.ULTRA_4K_HDR
        )
        assertEquals(PlaybackStrategy.UNSUPPORTED, missingVideo.strategy)
        assertTrue(missingVideo.issues.isNotEmpty())

        val transcodeDisabled = manager.evaluate(
            videoId = "no-tx",
            info = sampleInfo(audioMime = "audio/vnd.dts", audioLabel = "DTS", audioChannels = 6),
            profile = ReceiverProfile.STANDARD_1080P,
            allowHardwareTranscode = false
        )
        assertEquals(PlaybackStrategy.UNSUPPORTED, transcodeDisabled.strategy)
        assertTrue(transcodeDisabled.issues.any { it.component.contains("Disabled") })
    }
}
