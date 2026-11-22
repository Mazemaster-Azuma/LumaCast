package com.example

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.AudioTrackInfo
import com.example.core.common.CastError
import com.example.core.common.HdrFormat
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ReceiverProfile
import com.example.core.common.VideoTrackInfo
import com.example.core.media.FfmpegTranscodeBackend
import com.example.core.media.MediaCompatibilityManager
import com.example.core.media.MediaPreparationEngine
import com.example.core.media.MediaResourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies Backend A (Media3 ITU downmix matrices), Backend B (FFmpeg selective track CLI builder),
 * active-stream temporary cache protection, disk/thermal resource guards, and error recovery actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranscodingBackendAndProtectionTest {

    private val manager = MediaCompatibilityManager()

    private fun multiTrackInfo(
        videoMime: String = "video/hevc",
        videoLabel: String = "H.265 / HEVC",
        width: Int = 3840,
        height: Int = 2160,
        audioMime: String = "audio/vnd.dts",
        audioLabel: String = "DTS",
        audioChannels: Int = 6,
        audioOrdinal: Int = 0
    ): MediaTechnicalInfo {
        val vTrack = VideoTrackInfo(
            trackIndex = 0,
            mimeType = videoMime,
            codecLabel = videoLabel,
            profile = "Main 10",
            level = "L5.1",
            width = width,
            height = height,
            frameRate = 24f,
            bitrateBps = 20_000_000L,
            pixelFormat = "yuv420p10le",
            colorSpace = "BT.2020",
            hdrFormat = HdrFormat.HDR10,
            aspectRatio = "16:9",
            isInterlaced = false,
            hardwareDecoderAvailable = true
        )
        val aTrack = AudioTrackInfo(
            trackIndex = 1 + audioOrdinal,
            audioStreamOrdinal = audioOrdinal,
            mimeType = audioMime,
            codecLabel = audioLabel,
            sampleRateHz = 48_000,
            bitrateBps = 1_509_000L,
            channelCount = audioChannels,
            channelLayout = if (audioChannels == 8) "7.1 Surround" else if (audioChannels == 6) "5.1 Surround" else "Stereo (2.0)",
            bitDepth = 24,
            audioProfile = null,
            languageCode = "ja",
            languageDisplay = "Japanese",
            title = "Japanese Track",
            isDefault = true,
            hardwareDecoderAvailable = false
        )
        return MediaTechnicalInfo(
            containerFormat = "MKV",
            mimeType = "video/x-matroska",
            videoCodecMime = videoMime,
            videoCodecLabel = videoLabel,
            videoProfile = "Main 10",
            videoLevel = "L5.1",
            width = width,
            height = height,
            frameRate = 24f,
            bitrateBps = 20_000_000L,
            audioCodecMime = audioMime,
            audioCodecLabel = audioLabel,
            audioChannels = audioChannels,
            audioSampleRateHz = 48_000,
            hdrFormat = HdrFormat.HDR10,
            subtitleTrackCount = 1,
            durationMs = 7_200_000L,
            fileSizeBytes = 8_000_000_000L,
            videoTracks = listOf(vTrack),
            audioTracks = listOf(aTrack)
        )
    }

    @Test
    fun `ffmpeg command preserves video bitstream with -c_v copy during AUDIO_ONLY_TRANSCODE`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = FfmpegTranscodeBackend(context)
        val info = multiTrackInfo(audioChannels = 8, audioOrdinal = 2)
        val report = manager.evaluate(
            videoId = "ff-audio",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, report.strategy)

        val cmd = backend.buildCommand(
            executablePath = "/data/app/lib/arm64/libffmpeg.so",
            inputPath = "/cache/in.mkv",
            outputPath = "/cache/out.mp4.part",
            report = report,
            maxTargetHeight = 1080
        )

        // Must preserve 4K HEVC video untouched
        val cvIdx = cmd.indexOf("-c:v")
        assertTrue(cvIdx >= 0 && cmd[cvIdx + 1] == "copy")
        // Must select exact audio stream ordinal 2
        assertTrue(cmd.contains("0:a:2"))
        // Must encode AAC-LC 5.1 with ITU-R BS.775 7.1->5.1 pan filter
        val caIdx = cmd.indexOf("-c:a")
        assertTrue(caIdx >= 0 && cmd[caIdx + 1] == "aac")
        val afIdx = cmd.indexOf("-af")
        assertTrue(afIdx >= 0 && cmd[afIdx + 1].contains("pan=5.1"))
        assertTrue(cmd[afIdx + 1].contains("aresample=async=1:first_pts=0"))
        // Must place MOOV atom at head for instant Chromecast range seeking
        assertTrue(cmd.contains("+faststart"))
    }

    @Test
    fun `ffmpeg command preserves audio bitstream with -c_a copy during VIDEO_ONLY_TRANSCODE without upscaling 720p`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = FfmpegTranscodeBackend(context)
        val info = multiTrackInfo(
            videoMime = "video/x-xvid",
            videoLabel = "Xvid / MPEG-4",
            width = 1280,
            height = 720,
            audioMime = "audio/mp4a-latm",
            audioLabel = "AAC",
            audioChannels = 2
        )
        val report = manager.evaluate(
            videoId = "ff-video",
            info = info,
            profile = ReceiverProfile.STANDARD_1080P,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.VIDEO_ONLY_TRANSCODE, report.strategy)

        val cmd = backend.buildCommand(
            executablePath = "/data/app/lib/arm64/libffmpeg.so",
            inputPath = "/cache/in.mkv",
            outputPath = "/cache/out.mp4.part",
            report = report,
            maxTargetHeight = 1080
        )

        val cvIdx = cmd.indexOf("-c:v")
        assertTrue(cvIdx >= 0 && cmd[cvIdx + 1] == "libx264")
        val caIdx = cmd.indexOf("-c:a")
        assertTrue(caIdx >= 0 && cmd[caIdx + 1] == "copy")
        // 720p must NEVER be upscaled to 1080p
        assertFalse(cmd.any { it.contains("scale=-2:1080") })
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `media3 ITU downmix matrices build valid channel dimensions for 5_1 and 7_1`() {
        val m51To20 = MediaPreparationEngine.createItuDownmixMatrix(6, 2)
        assertNotNull(m51To20)
        assertEquals(6, m51To20!!.inputChannelCount)
        assertEquals(2, m51To20.outputChannelCount)

        val m71To20 = MediaPreparationEngine.createItuDownmixMatrix(8, 2)
        assertNotNull(m71To20)
        assertEquals(8, m71To20!!.inputChannelCount)
        assertEquals(2, m71To20.outputChannelCount)

        val m71To51 = MediaPreparationEngine.createItuDownmixMatrix(8, 6)
        assertNotNull(m71To51)
        assertEquals(8, m71To51!!.inputChannelCount)
        assertEquals(6, m71To51.outputChannelCount)
    }

    @Test
    fun `clearCache never deletes files actively streamed to Chromecast until released`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MediaPreparationEngine(context)
        val cacheDir = File(context.cacheDir, "lumacast_prepared").apply { mkdirs() }

        val activeStreamFile = File(cacheDir, "active_tv_stream.mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        val staleCacheFile = File(cacheDir, "stale_old_stream.mp4").apply { writeBytes(ByteArray(2048) { 3 }) }

        engine.markFileInUse(activeStreamFile)
        assertTrue(engine.isFileInUse(activeStreamFile))

        val firstCleared = engine.clearCache()
        assertEquals(2048L, firstCleared)
        assertTrue("Active TV stream file MUST NOT be deleted!", activeStreamFile.exists())
        assertFalse("Stale unused cache file should be deleted", staleCacheFile.exists())

        // After playback ends and file is released, cache clear removes it cleanly
        engine.releaseAllInUseFiles()
        assertFalse(engine.isFileInUse(activeStreamFile))
        val secondCleared = engine.clearCache()
        assertEquals(4096L, secondCleared)
        assertFalse(activeStreamFile.exists())
    }

    @Test
    fun `resource guard reports disk space headroom and thermal snapshot cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guard = MediaResourceGuard(context)
        val snapshot = guard.snapshot(ffmpegAvailable = false)
        assertFalse(snapshot.ffmpegAvailable)
        assertTrue(snapshot.thermalStatusLabel.isNotBlank())
        assertTrue(guard.requiredDiskBytes(100_000_000L) >= 100_000_000L + MediaResourceGuard.SAFETY_HEADROOM_BYTES)
    }

    @Test
    fun `cast errors expose actionable recovery guidance for transcoding failures`() {
        val diskErr = CastError.InsufficientDiskSpace(requiredLabel = "2.0 GB", availableLabel = "300 MB")
        assertTrue(diskErr.message.contains("2.0 GB"))
        assertTrue(diskErr.recoverySteps.any { it.contains("Clear Prepared Media Cache") })

        val decErr = CastError.DecoderUnavailable("DTS-HD")
        assertTrue(decErr.message.contains("DTS-HD"))
        assertTrue(decErr.recoverySteps.any { it.contains("audio track") || it.contains("FFmpeg") })

        val thermalErr = CastError.ThermalThrottling("Critical")
        assertTrue(thermalErr.message.contains("Critical"))

        val valErr = CastError.OutputValidationFailed("Audio track missing")
        assertTrue(valErr.message.contains("Audio track missing"))
    }
}
