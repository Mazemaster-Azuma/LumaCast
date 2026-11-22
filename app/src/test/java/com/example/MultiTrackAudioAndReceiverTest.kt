package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.cast.CastSessionController
import com.example.core.cast.CastTrackProtocol
import com.example.core.cast.ReceiverTrackEvent
import com.example.core.common.AudioTrackInfo
import com.example.core.common.AudioTrackRole
import com.example.core.common.CastError
import com.example.core.common.HdrFormat
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ReceiverProfile
import com.example.core.common.SubtitleAppearanceSettings
import com.example.core.common.SubtitleConversionStrategy
import com.example.core.common.SubtitleEdgeType
import com.example.core.common.SubtitleFontFamily
import com.example.core.common.SubtitleFormat
import com.example.core.common.SubtitleSourceType
import com.example.core.common.SubtitleTextAlign
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.VideoTrackInfo
import com.example.core.media.AdaptiveHlsManifestGenerator
import com.example.core.media.HlsAudioRenditionSpec
import com.example.core.media.HlsSubtitleRenditionSpec
import com.example.core.media.MediaCompatibilityManager
import com.example.core.media.SubtitleCompatibilityManager
import com.example.core.media.SubtitleManager
import com.example.core.network.InMemoryTextSource
import com.example.core.network.LocalMediaHttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

/**
 * Integration tests covering Multi-Language Audio Track Switching, Selective Per-Track Compatibility,
 * Custom Web Receiver Protocol & Receiver Confirmation State Machine, Adaptive HLS Manifest Generation,
 * Extended HTTP Server Endpoints (/subtitle/, /hls/, OPTIONS CORS, Path Traversal Protection),
 * and Active Subtitle Cache Protection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiTrackAudioAndReceiverTest {

    private val mediaCompat = MediaCompatibilityManager()
    private val subCompat = SubtitleCompatibilityManager()
    private val hlsGenerator = AdaptiveHlsManifestGenerator()

    private fun buildVideoTrack(
        mime: String = "video/hevc",
        label: String = "H.265 / HEVC",
        width: Int = 3840,
        height: Int = 2160,
        hdr: HdrFormat = HdrFormat.HDR10
    ) = VideoTrackInfo(
        trackIndex = 0,
        mimeType = mime,
        codecLabel = label,
        profile = "Main 10",
        level = "L5.1",
        width = width,
        height = height,
        frameRate = 24f,
        bitrateBps = 18_000_000L,
        pixelFormat = "yuv420p10le",
        colorSpace = "BT.2020",
        hdrFormat = hdr,
        aspectRatio = "16:9",
        isInterlaced = false,
        hardwareDecoderAvailable = true
    )

    private fun buildAudioTrack(
        trackIndex: Int,
        ordinal: Int,
        mime: String,
        label: String,
        channels: Int,
        layout: String,
        langCode: String,
        langDisplay: String,
        isDefault: Boolean = false,
        role: AudioTrackRole = AudioTrackRole.MAIN
    ) = AudioTrackInfo(
        trackIndex = trackIndex,
        audioStreamOrdinal = ordinal,
        mimeType = mime,
        codecLabel = label,
        sampleRateHz = 48_000,
        bitrateBps = if (channels >= 6) 1_536_000L else 192_000L,
        channelCount = channels,
        channelLayout = layout,
        bitDepth = 24,
        audioProfile = null,
        languageCode = langCode,
        languageDisplay = langDisplay,
        title = "$langDisplay $label",
        isDefault = isDefault,
        role = role,
        hardwareDecoderAvailable = mime == "audio/mp4a-latm" || mime == "audio/ac3" || mime == "audio/flac"
    )

    @Test
    fun integrationCase1_4kHevc_dtsEnglish_aacHindi_srtSubtitle_selectiveConversion() {
        val dtsEnglish = buildAudioTrack(1, 0, "audio/vnd.dts", "DTS", 6, "5.1 Surround", "en", "English", isDefault = true)
        val aacHindi = buildAudioTrack(2, 1, "audio/mp4a-latm", "AAC", 2, "Stereo (2.0)", "hi", "Hindi", isDefault = false)
        val info = MediaTechnicalInfo(
            containerFormat = "MKV",
            mimeType = "video/x-matroska",
            videoCodecMime = "video/hevc",
            videoCodecLabel = "H.265 / HEVC",
            videoProfile = "Main 10",
            videoLevel = "L5.1",
            width = 3840,
            height = 2160,
            frameRate = 24f,
            bitrateBps = 20_000_000L,
            audioCodecMime = "audio/vnd.dts",
            audioCodecLabel = "DTS",
            audioChannels = 6,
            audioSampleRateHz = 48_000,
            hdrFormat = HdrFormat.HDR10,
            subtitleTrackCount = 1,
            durationMs = 7_200_000L,
            fileSizeBytes = 8_000_000_000L,
            videoTracks = listOf(buildVideoTrack()),
            audioTracks = listOf(dtsEnglish, aacHindi)
        )

        // Annotate tracks independently: Hindi AAC is compatible; English DTS requires on-demand conversion
        val annotated = mediaCompat.annotateAudioTrackCompatibility(info.audioTracks, ReceiverProfile.ULTRA_4K_HDR)
        assertFalse(annotated[0].isCompatibleWithReceiver)
        assertTrue(annotated[1].isCompatibleWithReceiver)

        // Selecting DTS English -> AUDIO_ONLY_TRANSCODE (preserves 4K HEVC untouched!)
        val reportEn = mediaCompat.evaluate(
            videoId = "case1",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            selectedAudioTrackIndex = dtsEnglish.trackIndex,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, reportEn.strategy)
        assertTrue(reportEn.strategy.preservesVideoBitstream)
        assertFalse(reportEn.strategy.preservesAudioBitstream)

        // Switching to AAC Hindi -> Lossless REMUX (preserves BOTH 4K HEVC & AAC Hindi!)
        val reportHi = mediaCompat.evaluate(
            videoId = "case1",
            info = info,
            profile = ReceiverProfile.ULTRA_4K_HDR,
            selectedAudioTrackIndex = aacHindi.trackIndex,
            ffmpegAvailable = true
        )
        assertEquals(PlaybackStrategy.REMUX, reportHi.strategy)
        assertTrue(reportHi.strategy.preservesVideoBitstream)
        assertTrue(reportHi.strategy.preservesAudioBitstream)
    }

    @Test
    fun integrationCase2_h264_flac_assSubtitle_preservesVideoAndConvertsOnlyAudioAndSubtitle() {
        val flacAudio = buildAudioTrack(1, 0, "audio/flac", "FLAC", 6, "5.1 Surround", "ja", "Japanese", isDefault = true)
        val info = MediaTechnicalInfo(
            containerFormat = "MKV",
            mimeType = "video/x-matroska",
            videoCodecMime = "video/avc",
            videoCodecLabel = "H.264 / AVC",
            videoProfile = "High",
            videoLevel = "L4.1",
            width = 1920,
            height = 1080,
            frameRate = 23.976f,
            bitrateBps = 8_000_000L,
            audioCodecMime = "audio/flac",
            audioCodecLabel = "FLAC",
            audioChannels = 6,
            audioSampleRateHz = 48_000,
            hdrFormat = HdrFormat.SDR,
            subtitleTrackCount = 1,
            durationMs = 1_440_000L,
            fileSizeBytes = 1_200_000_000L,
            videoTracks = listOf(buildVideoTrack("video/avc", "H.264 / AVC", 1920, 1080, HdrFormat.SDR)),
            audioTracks = listOf(flacAudio)
        )

        val report = mediaCompat.evaluate("case2", info, ReceiverProfile.STANDARD_1080P)
        assertEquals(PlaybackStrategy.AUDIO_ONLY_TRANSCODE, report.strategy)
        assertTrue(report.strategy.preservesVideoBitstream)
        assertFalse(report.strategy.preservesAudioBitstream)

        val assSub = SubtitleTrackInfo(
            trackId = "sub_ass",
            castTrackId = 2001L,
            sourceType = SubtitleSourceType.EXTERNAL_SAF,
            languageCode = "en",
            languageDisplay = "English",
            title = "English · ASS",
            format = SubtitleFormat.ASS
        )
        assertEquals(
            SubtitleConversionStrategy.CONVERT_ASS_TO_VTT,
            subCompat.determineStrategy(assSub, isCustomReceiver = true)
        )
    }

    @Test
    fun integrationCase3_h264_aac_pgsSubtitle_directCastVideoAudioAndBitmapPgsStrategy() {
        val aacAudio = buildAudioTrack(1, 0, "audio/mp4a-latm", "AAC", 2, "Stereo (2.0)", "en", "English", isDefault = true)
        val info = MediaTechnicalInfo(
            containerFormat = "MP4",
            mimeType = "video/mp4",
            videoCodecMime = "video/avc",
            videoCodecLabel = "H.264 / AVC",
            videoProfile = "High",
            videoLevel = "L4.1",
            width = 1920,
            height = 1080,
            frameRate = 24f,
            bitrateBps = 6_000_000L,
            audioCodecMime = "audio/mp4a-latm",
            audioCodecLabel = "AAC",
            audioChannels = 2,
            audioSampleRateHz = 48_000,
            hdrFormat = HdrFormat.SDR,
            subtitleTrackCount = 1,
            durationMs = 3_600_000L,
            fileSizeBytes = 2_000_000_000L,
            videoTracks = listOf(buildVideoTrack("video/avc", "H.264 / AVC", 1920, 1080, HdrFormat.SDR)),
            audioTracks = listOf(aacAudio)
        )

        val report = mediaCompat.evaluate("case3", info, ReceiverProfile.STANDARD_1080P)
        assertEquals(PlaybackStrategy.DIRECT_CAST, report.strategy)

        val pgsTrack = SubtitleTrackInfo(
            trackId = "sub_pgs",
            castTrackId = 2005L,
            sourceType = SubtitleSourceType.EMBEDDED,
            languageCode = "en",
            languageDisplay = "English",
            title = "English · Blu-ray PGS",
            format = SubtitleFormat.PGS
        )
        assertEquals(
            SubtitleConversionStrategy.BITMAP_TIMED_OVERLAY_OR_OCR,
            subCompat.determineStrategy(pgsTrack, isCustomReceiver = true)
        )
    }

    @Test
    fun integrationCase4_threeLanguageAudioAndSubtitles_generatesValidHlsMasterPlaylist() {
        val enDts = buildAudioTrack(1, 0, "audio/vnd.dts", "DTS", 6, "5.1 Surround", "en", "English", isDefault = true)
        val hiAc3 = buildAudioTrack(2, 1, "audio/ac3", "AC-3", 6, "5.1 Surround", "hi", "Hindi")
        val bnAac = buildAudioTrack(3, 2, "audio/mp4a-latm", "AAC", 2, "Stereo (2.0)", "bn", "Bengali")

        // Verify summary display strings match required format: "Language — Codec — Channels"
        assertEquals("English — DTS — 5.1", enDts.formattedSummaryLine)
        assertEquals("Hindi — AC-3 — 5.1", hiAc3.formattedSummaryLine)
        assertEquals("Bengali — AAC — Stereo", bnAac.formattedSummaryLine)

        val annotated4k = mediaCompat.annotateAudioTrackCompatibility(
            listOf(enDts, hiAc3, bnAac),
            ReceiverProfile.ULTRA_4K_HDR,
            preferAc3Passthrough = true
        )
        assertFalse(annotated4k[0].isCompatibleWithReceiver) // DTS needs conversion
        assertTrue(annotated4k[1].isCompatibleWithReceiver)  // AC-3 5.1 passthrough on 4K TV
        assertTrue(annotated4k[2].isCompatibleWithReceiver)  // AAC Stereo compatible

        val subEn = SubtitleTrackInfo("s_en", 2001L, SubtitleSourceType.EXTERNAL_SAF, languageCode = "en", languageDisplay = "English", title = "English", format = SubtitleFormat.WEBVTT)
        val subHi = SubtitleTrackInfo("s_hi", 2002L, SubtitleSourceType.EXTERNAL_SAF, languageCode = "hi", languageDisplay = "Hindi", title = "Hindi", format = SubtitleFormat.WEBVTT)
        val subBn = SubtitleTrackInfo("s_bn", 2003L, SubtitleSourceType.EXTERNAL_SAF, languageCode = "bn", languageDisplay = "Bengali", title = "Bengali", format = SubtitleFormat.WEBVTT, isForced = true)

        val master = hlsGenerator.buildMasterPlaylist(
            videoPlaylistRelativeUri = "video/index.m3u8",
            bandwidthBps = 12_000_000L,
            width = 3840,
            height = 2160,
            videoCodecTag = "hvc1.1.6.L120.90",
            audioRenditions = listOf(
                HlsAudioRenditionSpec(enDts, "audio_en/index.m3u8", isDefault = true),
                HlsAudioRenditionSpec(hiAc3, "audio_hi/index.m3u8", isDefault = false),
                HlsAudioRenditionSpec(bnAac, "audio_bn/index.m3u8", isDefault = false)
            ),
            subtitleRenditions = listOf(
                HlsSubtitleRenditionSpec(subEn, "subs_en/index.m3u8", isDefault = false),
                HlsSubtitleRenditionSpec(subHi, "subs_hi/index.m3u8", isDefault = false),
                HlsSubtitleRenditionSpec(subBn, "subs_bn/index.m3u8", isDefault = true)
            )
        )

        assertTrue(master.contains("#EXTM3U"))
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\""))
        assertTrue(master.contains("LANGUAGE=\"en\",DEFAULT=YES"))
        assertTrue(master.contains("LANGUAGE=\"hi\",DEFAULT=NO"))
        assertTrue(master.contains("LANGUAGE=\"bn\",DEFAULT=NO"))
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\""))
        assertTrue(master.contains("FORCED=YES,URI=\"subs_bn/index.m3u8\""))
        assertTrue(master.contains("AUDIO=\"audio\",SUBTITLES=\"subs\""))

        val subPlaylist = hlsGenerator.buildSubtitleMediaPlaylist("subs_bn.vtt", durationSeconds = 5400L)
        assertTrue(subPlaylist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(subPlaylist.contains("subs_bn.vtt"))
        assertTrue(subPlaylist.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun castTrackProtocol_roundTripsCommandsAndReceiverConfirmations() {
        val style = SubtitleAppearanceSettings(
            fontScale = 1.25f,
            fontFamily = SubtitleFontFamily.SERIF,
            foregroundColorArgb = 0xFFFFEB3BL,
            backgroundOpacity = 0.6f,
            edgeType = SubtitleEdgeType.OUTLINE,
            textAlign = SubtitleTextAlign.CENTER,
            bottomOffsetPercent = 12
        )
        val styleJson = JSONObject(CastTrackProtocol.buildSetSubtitleStyle(style))
        assertEquals("SET_SUBTITLE_STYLE", styleJson.getString("type"))
        assertEquals(1.25, styleJson.getDouble("fontScale"), 0.01)
        assertEquals("OUTLINE", styleJson.getString("edgeType"))
        assertEquals("SERIF", styleJson.getString("fontGenericFamily"))
        assertEquals(12, styleJson.getInt("bottomOffsetPercent"))

        val syncEvent = CastTrackProtocol.parseReceiverEvent(
            """{"type":"TRACK_STATE_SYNC","supportsAudioTrackSwitching":true,"activeAudioTrackId":101,"activeTextTrackIds":[2002],"subtitleDelayMs":750}"""
        ) as ReceiverTrackEvent.TrackStateSync
        assertTrue(syncEvent.supportsAudioTrackSwitching)
        assertEquals(101L, syncEvent.activeAudioCastTrackId)
        assertEquals(listOf(2002L), syncEvent.activeTextCastTrackIds)
        assertEquals(750L, syncEvent.subtitleDelayMs)

        val audioConfirmed = CastTrackProtocol.parseReceiverEvent(
            """{"type":"AUDIO_TRACK_CONFIRMED","trackUniqueId":"audio_2_1","castTrackId":102,"language":"hi","name":"Hindi — AC-3 — 5.1"}"""
        ) as ReceiverTrackEvent.AudioTrackConfirmed
        assertEquals("audio_2_1", audioConfirmed.trackUniqueId)
        assertEquals("hi", audioConfirmed.language)

        val errEvent = CastTrackProtocol.parseReceiverEvent(
            """{"type":"TRACK_ERROR","code":"AUDIO_SWITCH_FAILED","message":"No matching audio track"}"""
        ) as ReceiverTrackEvent.TrackError
        assertEquals("AUDIO_SWITCH_FAILED", errEvent.code)
    }

    @Test
    fun castSessionController_updatesActiveTrackOnlyAfterReceiverConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = CastSessionController(context, onSessionTerminated = {})

        val enTrack = buildAudioTrack(1, 0, "audio/mp4a-latm", "AAC", 2, "Stereo (2.0)", "en", "English", isDefault = true)
        val bnTrack = buildAudioTrack(2, 1, "audio/mp4a-latm", "AAC", 2, "Stereo (2.0)", "bn", "Bengali")
        val hiSub = SubtitleTrackInfo(
            trackId = "sub_hi",
            castTrackId = 2002L,
            sourceType = SubtitleSourceType.EXTERNAL_SAF,
            languageCode = "hi",
            languageDisplay = "Hindi",
            title = "Hindi · VTT",
            format = SubtitleFormat.WEBVTT
        )

        controller.updateTrackCatalog(listOf(enTrack, bnTrack), listOf(hiSub))

        // Before receiver confirms, active IDs are null
        assertNull(controller.playbackState.value.activeAudioTrackId)
        assertNull(controller.playbackState.value.activeSubtitleTrackId)

        // Receiver confirms Bengali audio track
        controller.handleReceiverTrackEvent(
            ReceiverTrackEvent.AudioTrackConfirmed(
                trackUniqueId = bnTrack.uniqueId,
                castTrackId = 2L,
                language = "bn",
                name = bnTrack.formattedSummaryLine
            )
        )
        assertEquals(bnTrack.uniqueId, controller.playbackState.value.activeAudioTrackId)
        assertEquals("Bengali — AAC — Stereo", controller.playbackState.value.activeAudioLabel)
        assertNull(controller.playbackState.value.pendingAudioTrackId)

        // Receiver confirms Hindi subtitle enabled
        controller.handleReceiverTrackEvent(
            ReceiverTrackEvent.SubtitleTrackConfirmed(
                subtitleTrackId = hiSub.trackId,
                castTrackId = hiSub.castTrackId,
                enabled = true,
                isBitmap = false
            )
        )
        assertEquals(hiSub.trackId, controller.playbackState.value.activeSubtitleTrackId)

        // Receiver confirms delay
        controller.handleReceiverTrackEvent(ReceiverTrackEvent.SubtitleDelayConfirmed(-1250L))
        assertEquals(-1250L, controller.playbackState.value.subtitleDelayMs)

        // Receiver reports AUDIO error -> clears pending and surfaces CastError.ReceiverAudioSwitchFailed
        controller.handleReceiverTrackEvent(
            ReceiverTrackEvent.TrackError("AUDIO_SWITCH_FAILED", "Track missing on receiver")
        )
        assertTrue(controller.playbackState.value.lastError is CastError.ReceiverAudioSwitchFailed)
        controller.release()
    }

    @Test
    fun httpServer_servesSubtitlesHlsCorsOptionsAndBlocksPathTraversal() {
        val server = LocalMediaHttpServer()
        val port = server.start("127.0.0.1", 0)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "lumacast_hls_test_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            // 1. Register WebVTT subtitle with Unicode (Hindi + Bengali)
            val vttPayload = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nनमस्ते · বাংলা\n"
            val subReg = server.registerSubtitle(
                InMemoryTextSource(vttPayload, "text/vtt; charset=utf-8", "multi.vtt"),
                extension = "vtt"
            )
            assertTrue(subReg.url.contains("/subtitle/") && subReg.url.endsWith(".vtt"))

            // OPTIONS preflight -> 204 No Content + CORS headers
            val optionsResp = sendRawHttp("127.0.0.1", port, "OPTIONS /subtitle/${subReg.token} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertTrue(optionsResp.first.contains("204 No Content"))
            assertTrue(optionsResp.second.contains("Access-Control-Allow-Origin: *"))
            assertTrue(optionsResp.second.contains("Access-Control-Allow-Methods: GET, HEAD, OPTIONS"))

            // GET /subtitle/<token>.vtt -> 200 OK with UTF-8 WebVTT payload
            val subGet = sendRawHttp("127.0.0.1", port, "GET /subtitle/${subReg.token} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertTrue(subGet.first.contains("200 OK"))
            assertTrue(subGet.second.contains("Content-Type: text/vtt; charset=utf-8"))
            assertTrue(subGet.third.contains("नमस्ते · বাংলা"))

            // 2. Register HLS directory and verify master.m3u8 + segment serving
            File(tempDir, "master.m3u8").writeText("#EXTM3U\n#EXT-X-VERSION:6\n", Charsets.UTF_8)
            val hlsReg = server.registerHlsDirectory(tempDir, "master.m3u8")
            val hlsGet = sendRawHttp("127.0.0.1", port, "GET /hls/${hlsReg.token}/master.m3u8 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertTrue(hlsGet.first.contains("200 OK"))
            assertTrue(hlsGet.second.contains("application/vnd.apple.mpegurl"))
            assertTrue(hlsGet.third.contains("#EXTM3U"))

            // 3. Path traversal attack on /hls/ must be blocked with 403 Forbidden
            val traversal = sendRawHttp("127.0.0.1", port, "GET /hls/${hlsReg.token}/../etc/passwd HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertTrue(traversal.first.contains("403 Forbidden"))
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun subtitleManager_protectsActiveSubtitleFileFromCacheDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SubtitleManager(context)
        val cacheDir = File(context.cacheDir, "lumacast_subtitles").apply { mkdirs() }
        val activeFile = File(cacheDir, "sub_active.vtt").apply { writeText("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nActive\n") }
        val staleFile = File(cacheDir, "sub_stale.vtt").apply { writeText("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nStale\n") }

        manager.markInUse(activeFile)
        val cleared = manager.clearUnusedCache()

        assertTrue(cleared > 0L)
        assertTrue("Active subtitle must NOT be deleted while in use", activeFile.exists())
        assertFalse("Stale cached subtitle should be cleaned up", staleFile.exists())

        manager.releaseAllInUse()
        manager.clearUnusedCache()
        assertFalse(activeFile.exists())
    }

    private fun sendRawHttp(host: String, port: Int, request: String): Triple<String, String, String> {
        Socket(host, port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine().orEmpty()
            val headerBuilder = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headerBuilder.appendLine(line)
            }
            val bodyBuilder = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                bodyBuilder.appendLine(line)
            }
            return Triple(statusLine, headerBuilder.toString(), bodyBuilder.toString())
        }
    }
}
