package com.example

import android.net.Uri
import com.example.core.common.SubtitleConversionStrategy
import com.example.core.common.SubtitleFormat
import com.example.core.common.SubtitleSourceType
import com.example.core.common.SubtitleTrackInfo
import com.example.core.media.SubtitleCompatibilityManager
import com.example.core.media.SubtitleManager
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the Intelligent Subtitle Compatibility & Conversion Engine:
 * - SRT, ASS/SSA, WebVTT, TTML conversion to valid UTF-8 WebVTT
 * - Multilingual Unicode preservation (English, Hindi, Bengali, Japanese, Korean)
 * - BOM & Windows-1252 encoding detection
 * - Millisecond delay synchronization (+ / -), negative timestamp clamping, overlapping cues
 * - External filename metadata parsing (language, Forced, SDH, .idx/.sup)
 * - Real binary PGS (.sup) & VobSub (.idx) timing parsing (never treating bitmap streams as text)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitleEngineTest {

    private val engine = SubtitleCompatibilityManager()

    @Test
    fun srtToWebVtt_preservesUnicodeForEnglishHindiBengaliJapaneseKorean() {
        val srt = """
            1
            00:00:01,250 --> 00:00:04,500
            Welcome to LumaCast Cinema!

            2
            00:00:05,000 --> 00:00:08,750
            नमस्ते दुनिया — हिंदी सबटाइटल

            3
            00:00:09,100 --> 00:00:12,400
            বাংলা সাবটাইটেল সঠিকভাবে দেখা যাচ্ছে

            4
            00:00:13,000 --> 00:00:16,200
            日本語の字幕テスト

            5
            00:00:17,000 --> 00:00:20,000
            한국어 자막 테스트
        """.trimIndent()

        // Prefix UTF-8 BOM to test BOM stripping
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(Charsets.UTF_8)
        val outcome = engine.convertToCompatibleOutput(bomBytes, SubtitleFormat.SRT, delayMs = 0L)

        assertFalse(outcome.isBitmapOutput)
        assertEquals("UTF-8-BOM", outcome.detectedEncoding)
        assertEquals(5, outcome.cueCount)
        val vtt = outcome.webVttContent!!
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.250 --> 00:00:04.500"))
        assertTrue(vtt.contains("Welcome to LumaCast Cinema!"))
        assertTrue(vtt.contains("नमस्ते दुनिया — हिंदी सबटाइटल"))
        assertTrue(vtt.contains("বাংলা সাবটাইটেল সঠিকভাবে দেখা যাচ্ছে"))
        assertTrue(vtt.contains("日本語の字幕テスト"))
        assertTrue(vtt.contains("한국어 자막 테스트"))
    }

    @Test
    fun assToWebVtt_preservesStylingLineBreaksAlignmentAndStripsVectorDrawings() {
        val ass = """
            [Script Info]
            Title: Multilingual Anime Sample
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.15,0:00:05.80,Default,,0,0,0,,{\an8}{\i1}Whispered line{\i0}\N{\b1}Second line{\b0}
            Dialogue: 0,0:00:06.00,0:00:09.25,Default,,0,0,0,,{\u1}日本語 & বাংলা{\u0}
            Dialogue: 0,0:00:10.00,0:00:12.00,Signs,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
        """.trimIndent()

        val outcome = engine.convertToCompatibleOutput(ass.toByteArray(Charsets.UTF_8), SubtitleFormat.ASS)

        assertFalse(outcome.isBitmapOutput)
        // Vector drawing line must be skipped, leaving 2 real dialogue cues
        assertEquals(2, outcome.cueCount)
        val vtt = outcome.webVttContent!!
        assertTrue(vtt.contains("00:00:02.150 --> 00:00:05.800 line:10% align:center"))
        assertTrue(vtt.contains("<i>Whispered line</i>\n<b>Second line</b>"))
        assertTrue(vtt.contains("<u>日本語 & বাংলা</u>"))
        assertFalse(vtt.contains("m 0 0 l 100"))
    }

    @Test
    fun ttmlToWebVtt_parsesBeginEndDurAndHtmlEntities() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xml:lang="hi">
              <body>
                <div>
                  <p begin="00:00:01.500" end="00:00:04.000">Tom &amp; Jerry<br/>नमस्ते!</p>
                  <p begin="10.5s" dur="2.5s">Duration based cue</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val outcome = engine.convertToCompatibleOutput(ttml.toByteArray(Charsets.UTF_8), SubtitleFormat.TTML)

        assertEquals(2, outcome.cueCount)
        val vtt = outcome.webVttContent!!
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:04.000"))
        assertTrue(vtt.contains("Tom & Jerry\nनमस्ते!"))
        assertTrue(vtt.contains("00:00:10.500 --> 00:00:13.000"))
        assertTrue(vtt.contains("Duration based cue"))
    }

    @Test
    fun delayAdjustment_shiftsPositiveNegativeClampsZeroAndPreservesOverlappingCues() {
        val vttInput = """
            WEBVTT

            00:00:00.500 --> 00:00:01.500
            Early cue dropped when shifted -2000ms

            00:00:01.000 --> 00:00:04.000
            Starts negative, clamped to 00:00:00.000

            00:00:02.500 --> 00:00:05.500
            Overlapping speaker B
        """.trimIndent()

        // Negative shift of -2000ms
        val negativeOutcome = engine.convertToCompatibleOutput(
            rawBytes = vttInput.toByteArray(Charsets.UTF_8),
            format = SubtitleFormat.WEBVTT,
            delayMs = -2000L
        )
        assertEquals(2, negativeOutcome.cueCount)
        val negVtt = negativeOutcome.webVttContent!!
        assertFalse(negVtt.contains("Early cue dropped"))
        assertTrue(negVtt.contains("00:00:00.000 --> 00:00:02.000"))
        assertTrue(negVtt.contains("00:00:00.500 --> 00:00:03.500"))
        assertTrue(negativeOutcome.validationWarnings.any { it.contains("clamped") })
        assertTrue(negativeOutcome.validationWarnings.any { it.contains("omitted") })

        // Positive shift of +1500ms
        val positiveOutcome = engine.convertToCompatibleOutput(
            rawBytes = vttInput.toByteArray(Charsets.UTF_8),
            format = SubtitleFormat.WEBVTT,
            delayMs = 1500L
        )
        assertEquals(3, positiveOutcome.cueCount)
        assertTrue(positiveOutcome.webVttContent!!.contains("00:00:02.000 --> 00:00:03.000"))
    }

    @Test
    fun pgsBinarySubtitle_neverTreatedAsPlainTextAndProducesTimedBitmapManifest() {
        // Construct two valid Blu-ray PGS Presentation Composition Segments (PCS 0x16):
        // Cue 1 starts at 2.0s (PTS = 180_000 @ 90kHz) with 1 composition object, clears at 5.0s (PTS = 450_000) with 0 objects
        val out = ByteArrayOutputStream()
        fun writePcs(pts90k: Int, numObjects: Int) {
            val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            header.put(0x50.toByte()) // 'P'
            header.put(0x47.toByte()) // 'G'
            header.putInt(pts90k)     // PTS
            header.putInt(0)          // DTS
            header.put(0x16.toByte()) // Segment type = PCS
            header.putShort(11)       // Payload size
            out.write(header.array())
            val payload = ByteArray(11)
            payload[10] = numObjects.toByte()
            out.write(payload)
        }

        writePcs(pts90k = 180_000, numObjects = 1) // 2000ms start
        writePcs(pts90k = 450_000, numObjects = 0) // 5000ms clear
        writePcs(pts90k = 900_000, numObjects = 1) // 10000ms start
        writePcs(pts90k = 1_260_000, numObjects = 0) // 14000ms clear

        val outcome = engine.convertToCompatibleOutput(out.toByteArray(), SubtitleFormat.PGS, delayMs = 500L)

        assertTrue(outcome.isBitmapOutput)
        assertNull(outcome.webVttContent)
        assertNotNull(outcome.bitmapManifestJson)
        assertEquals("BINARY-PGS", outcome.detectedEncoding)
        assertEquals(2, outcome.cueCount)

        val json = JSONObject(outcome.bitmapManifestJson!!)
        assertEquals("lumacast_bitmap_subtitles", json.getString("type"))
        assertEquals("PGS", json.getString("format"))
        val cues = json.getJSONArray("cues")
        assertEquals(2, cues.length())
        assertEquals(2500L, cues.getJSONObject(0).getLong("startMs"))
        assertEquals(5500L, cues.getJSONObject(0).getLong("endMs"))
        assertEquals(10500L, cues.getJSONObject(1).getLong("startMs"))
        assertEquals(14500L, cues.getJSONObject(1).getLong("endMs"))
    }

    @Test
    fun vobSubIdxSubtitle_parsesTimestampsIntoBitmapManifest() {
        val idx = """
            # VobSub index file, v7
            size: 720x480
            id: en, index: 0
            timestamp: 00:00:03:200, filepos: 000000800
            timestamp: 00:00:07:500, filepos: 000001a00
        """.trimIndent()

        val outcome = engine.convertToCompatibleOutput(idx.toByteArray(Charsets.UTF_8), SubtitleFormat.VOBSUB)

        assertTrue(outcome.isBitmapOutput)
        assertNull(outcome.webVttContent)
        assertEquals(2, outcome.cueCount)
        val json = JSONObject(outcome.bitmapManifestJson!!)
        assertEquals("VOBSUB", json.getString("format"))
        val firstCue = json.getJSONArray("cues").getJSONObject(0)
        assertEquals(3200L, firstCue.getLong("startMs"))
        assertEquals(0x800L, firstCue.getLong("rawFileOffset"))
    }

    @Test
    fun externalFilenameParser_detectsLanguagesForcedSdhAndBitmapFormats() {
        val base = "Interstellar_4K"

        assertTrue(SubtitleManager.isMatchingSubtitleFilename("Interstellar_4K.srt", base))
        assertTrue(SubtitleManager.isMatchingSubtitleFilename("Interstellar_4K.hi.forced.srt", base))
        assertTrue(SubtitleManager.isMatchingSubtitleFilename("Interstellar_4K.bn.sdh.vtt", base))
        assertFalse(SubtitleManager.isMatchingSubtitleFilename("AnotherMovie.en.srt", base))

        val hiForced = SubtitleManager.parseExternalSubtitleMetadata(
            uri = Uri.parse("content://example/Interstellar_4K.hi.forced.srt"),
            fileName = "Interstellar_4K.hi.forced.srt",
            videoBaseName = base,
            sourceType = SubtitleSourceType.EXTERNAL_SAF,
            castTrackId = 2001L
        )!!
        assertEquals("hi", hiForced.languageCode)
        assertEquals("Hindi", hiForced.languageDisplay)
        assertTrue(hiForced.isForced)
        assertFalse(hiForced.isSdh)
        assertEquals(SubtitleFormat.SRT, hiForced.format)

        val bnSdh = SubtitleManager.parseExternalSubtitleMetadata(
            uri = Uri.parse("content://example/Interstellar_4K.bn.sdh.vtt"),
            fileName = "Interstellar_4K.bn.sdh.vtt",
            videoBaseName = base,
            sourceType = SubtitleSourceType.EXTERNAL_SAF,
            castTrackId = 2002L
        )!!
        assertEquals("bn", bnSdh.languageCode)
        assertEquals("Bengali", bnSdh.languageDisplay)
        assertTrue(bnSdh.isSdh)
        assertEquals(SubtitleFormat.WEBVTT, bnSdh.format)

        val pgsTrack = SubtitleManager.parseExternalSubtitleMetadata(
            uri = Uri.parse("content://example/Interstellar_4K.en.sup"),
            fileName = "Interstellar_4K.en.sup",
            videoBaseName = base,
            sourceType = SubtitleSourceType.EXTERNAL_PICKED,
            castTrackId = 2003L
        )!!
        assertEquals(SubtitleFormat.PGS, pgsTrack.format)
        assertTrue(pgsTrack.format.isBitmap)
    }

    @Test
    fun determineStrategy_handlesDefaultVsCustomReceiverProperly() {
        val ttmlTrack = SubtitleTrackInfo(
            trackId = "sub_ttml",
            castTrackId = 2010L,
            sourceType = SubtitleSourceType.EXTERNAL_SAF,
            languageCode = "en",
            languageDisplay = "English",
            title = "English · TTML",
            format = SubtitleFormat.TTML
        )
        assertEquals(
            SubtitleConversionStrategy.DIRECT_TTML,
            engine.determineStrategy(ttmlTrack, isCustomReceiver = true)
        )
        assertEquals(
            SubtitleConversionStrategy.CONVERT_TTML_TO_VTT,
            engine.determineStrategy(ttmlTrack, isCustomReceiver = false)
        )

        val vttTrack = ttmlTrack.copy(format = SubtitleFormat.WEBVTT)
        assertEquals(
            SubtitleConversionStrategy.DIRECT_WEBVTT,
            engine.determineStrategy(vttTrack, isCustomReceiver = false)
        )

        val pgsTrack = ttmlTrack.copy(format = SubtitleFormat.PGS)
        assertEquals(
            SubtitleConversionStrategy.BITMAP_TIMED_OVERLAY_OR_OCR,
            engine.determineStrategy(pgsTrack, isCustomReceiver = true)
        )
    }
}
