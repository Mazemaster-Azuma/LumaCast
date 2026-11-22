package com.example.core.media

import com.example.core.common.SubtitleConversionStrategy
import com.example.core.common.SubtitleCue
import com.example.core.common.SubtitleFormat
import com.example.core.common.SubtitleSourceType
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.TimedBitmapCue
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

data class SubtitleConversionOutcome(
    val webVttContent: String?,
    val bitmapManifestJson: String?,
    val cueCount: Int,
    val detectedEncoding: String,
    val validationWarnings: List<String> = emptyList(),
    val isBitmapOutput: Boolean = false
)

/**
 * Intelligent Subtitle Compatibility & Conversion Engine for Chromecast.
 *
 * Responsibilities:
 * - Evaluates every subtitle format against Chromecast receiver capabilities:
 *   - WebVTT -> Direct serving
 *   - TTML / DFXP -> Direct on Custom Receiver or converted to WebVTT
 *   - SRT -> Converted to clean UTF-8 WebVTT
 *   - ASS / SSA -> Converted to WebVTT preserving styling (italic/bold/underline/alignment) & timing
 *   - PGS (.sup) / VobSub (.idx/.sub) -> NEVER treated as plain text; parses real binary packet
 *     timing tables into a Timed Bitmap Manifest for Custom Web Receiver overlay / OCR review.
 * - Full Unicode & multilingual preservation (Hindi, Bengali, English, Japanese, Korean, Arabic, etc.)
 *   with automatic BOM / UTF-8 / UTF-16 / Windows-1252 decoding.
 * - Millisecond-accurate timing synchronization: delay offset (+/- ms), optional FPS scaling,
 *   negative timestamp clamping, overlapping cue support, and timing validation.
 */
class SubtitleCompatibilityManager {

    fun determineStrategy(
        track: SubtitleTrackInfo,
        isCustomReceiver: Boolean
    ): SubtitleConversionStrategy {
        if (track.format.isBitmap) {
            return if (isCustomReceiver) {
                SubtitleConversionStrategy.BITMAP_TIMED_OVERLAY_OR_OCR
            } else {
                SubtitleConversionStrategy.BITMAP_TIMED_OVERLAY_OR_OCR
            }
        }

        if (track.sourceType == SubtitleSourceType.EMBEDDED) {
            return SubtitleConversionStrategy.EXTRACT_AND_CONVERT_TO_VTT
        }

        return when (track.format) {
            SubtitleFormat.WEBVTT -> SubtitleConversionStrategy.DIRECT_WEBVTT
            SubtitleFormat.TTML -> if (isCustomReceiver) {
                SubtitleConversionStrategy.DIRECT_TTML
            } else {
                SubtitleConversionStrategy.CONVERT_TTML_TO_VTT
            }
            SubtitleFormat.SRT -> SubtitleConversionStrategy.CONVERT_SRT_TO_VTT
            SubtitleFormat.ASS, SubtitleFormat.SSA -> SubtitleConversionStrategy.CONVERT_ASS_TO_VTT
            SubtitleFormat.PGS, SubtitleFormat.VOBSUB -> SubtitleConversionStrategy.BITMAP_TIMED_OVERLAY_OR_OCR
            SubtitleFormat.UNKNOWN -> SubtitleConversionStrategy.UNSUPPORTED
        }
    }

    /**
     * Converts raw subtitle bytes of any supported text format into synchronized UTF-8 WebVTT,
     * or parses PGS / VobSub bitmap headers into a timed bitmap manifest (never garbling binary as text).
     */
    fun convertToCompatibleOutput(
        rawBytes: ByteArray,
        format: SubtitleFormat,
        delayMs: Long = 0L,
        fpsRatio: Double = 1.0
    ): SubtitleConversionOutcome {
        if (format == SubtitleFormat.PGS) {
            val cues = parsePgsBinaryTiming(rawBytes)
            val shifted = shiftBitmapCues(cues, delayMs, fpsRatio)
            return SubtitleConversionOutcome(
                webVttContent = null,
                bitmapManifestJson = serializeBitmapManifest("PGS", shifted),
                cueCount = shifted.size,
                detectedEncoding = "BINARY-PGS",
                validationWarnings = listOf(
                    "Blu-ray PGS is a bitmap subtitle stream (${shifted.size} timed graphic cues parsed). Rendered via Custom Web Receiver overlay or OCR review — never parsed as raw text."
                ),
                isBitmapOutput = true
            )
        }

        if (format == SubtitleFormat.VOBSUB) {
            val (text, encoding) = decodeTextWithBomDetection(rawBytes)
            val cues = parseVobSubIdxTiming(text)
            val shifted = shiftBitmapCues(cues, delayMs, fpsRatio)
            return SubtitleConversionOutcome(
                webVttContent = null,
                bitmapManifestJson = serializeBitmapManifest("VOBSUB", shifted),
                cueCount = shifted.size,
                detectedEncoding = encoding,
                validationWarnings = listOf(
                    "DVD VobSub is a bitmap subtitle stream (${shifted.size} indexed graphic timestamps parsed). Rendered via Custom Web Receiver overlay or OCR review."
                ),
                isBitmapOutput = true
            )
        }

        val (decoded, encoding) = decodeTextWithBomDetection(rawBytes)
        val parsedCues = when (format) {
            SubtitleFormat.SRT -> parseSrt(decoded)
            SubtitleFormat.ASS, SubtitleFormat.SSA -> parseAssSsa(decoded)
            SubtitleFormat.TTML -> parseTtml(decoded)
            SubtitleFormat.WEBVTT -> parseWebVtt(decoded)
            else -> throw IllegalArgumentException("Unsupported subtitle format: ${format.displayLabel}")
        }

        val warnings = mutableListOf<String>()
        val synced = applyTimingSync(parsedCues, delayMs = delayMs, fpsRatio = fpsRatio, warnings = warnings)
        if (synced.isEmpty()) {
            throw IllegalStateException("No valid subtitle cues remained after timing synchronization.")
        }

        return SubtitleConversionOutcome(
            webVttContent = formatAsWebVtt(synced),
            bitmapManifestJson = null,
            cueCount = synced.size,
            detectedEncoding = encoding,
            validationWarnings = warnings,
            isBitmapOutput = false
        )
    }

    /**
     * Decodes UTF-8 (with or without BOM), UTF-16LE, UTF-16BE, or ISO-8859-1/Windows-1252 fallback
     * while strictly preserving Unicode scripts (Hindi, Bengali, Japanese, Korean, Arabic, etc.).
     */
    fun decodeTextWithBomDetection(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8-BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }
        // Validate strict UTF-8 decoding first so multilingual UTF-8 never gets corrupted
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (_: Exception) {
            val cp1252 = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, cp1252) to cp1252.name()
        }
    }

    fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n").trim()
        val blocks = normalized.split(Regex("\n\\s*\n"))

        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue

            val timingLine = lines[timingIdx]
            val parts = timingLine.split("-->")
            if (parts.size < 2) continue

            val startMs = parseTimestampMs(parts[0].trim()) ?: continue
            val endAndSettings = parts[1].trim().split(Regex("\\s+"), limit = 2)
            val endMs = parseTimestampMs(endAndSettings[0]) ?: continue
            val textLines = lines.drop(timingIdx + 1)
            if (textLines.isEmpty()) continue

            val cleanText = textLines.joinToString("\n")
                .replace(Regex("\\{\\\\an[1-9]\\}"), "")
                .trim()
            if (cleanText.isNotEmpty()) {
                cues += SubtitleCue(startMs = startMs, endMs = endMs, text = cleanText)
            }
        }
        return cues
    }

    /**
     * Converts ASS/SSA Dialogue events into timed WebVTT cues while preserving supported styling
     * (italic, bold, underline, alignment) and stripping vector drawing blocks.
     */
    fun parseAssSsa(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").lines()
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var columnCount = 10

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(":").split(",").map { it.trim().lowercase(Locale.US) }
                columnCount = cols.size
                cols.indexOf("start").takeIf { it >= 0 }?.let { startIdx = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { endIdx = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { textIdx = it }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(":").trimStart()
                val fields = payload.split(",", limit = columnCount)
                if (fields.size <= maxOf(startIdx, endIdx, textIdx)) continue

                val startMs = parseAssTimestampMs(fields[startIdx].trim()) ?: continue
                val endMs = parseAssTimestampMs(fields[endIdx].trim()) ?: continue
                val rawText = fields[textIdx]

                // Skip ASS vector drawings ({\p1}...{\p0})
                if (rawText.contains("{\\p1}") || rawText.contains("\\p1")) continue

                val (convertedText, cueSettings) = convertAssTagsToWebVtt(rawText)
                if (convertedText.isNotBlank()) {
                    cues += SubtitleCue(
                        startMs = startMs,
                        endMs = endMs,
                        text = convertedText,
                        cueSettings = cueSettings
                    )
                }
            }
        }
        return cues
    }

    fun parseTtml(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val pRegex = Regex(
            pattern = "<p\\b([^>]*)>(.*?)</p>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val attrRegex = Regex("""(\w+)\s*=\s*"([^"]*)"""")

        for (match in pRegex.findAll(content)) {
            val attrsStr = match.groupValues[1]
            val bodyRaw = match.groupValues[2]
            val attrs = attrRegex.findAll(attrsStr)
                .associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }

            val beginStr = attrs["begin"] ?: continue
            val startMs = parseTtmlTimestampMs(beginStr) ?: continue
            val endMs = when {
                attrs.containsKey("end") -> parseTtmlTimestampMs(attrs["end"]!!)
                attrs.containsKey("dur") -> parseTtmlTimestampMs(attrs["dur"]!!)?.let { startMs + it }
                else -> startMs + 3_000L
            } ?: continue

            val text = bodyRaw
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .lines()
                .joinToString("\n") { it.trim() }
                .trim()

            if (text.isNotEmpty()) {
                cues += SubtitleCue(startMs = startMs, endMs = endMs, text = text)
            }
        }
        return cues
    }

    fun parseWebVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n").trim()
        val blocks = normalized.split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            if (lines.first().startsWith("WEBVTT") || lines.first().startsWith("NOTE") || lines.first().startsWith("STYLE")) {
                continue
            }
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val parts = lines[timingIdx].split("-->")
            if (parts.size < 2) continue

            val startMs = parseTimestampMs(parts[0].trim()) ?: continue
            val rhsParts = parts[1].trim().split(Regex("\\s+"), limit = 2)
            val endMs = parseTimestampMs(rhsParts[0]) ?: continue
            val settings = rhsParts.getOrNull(1).orEmpty()
            val text = lines.drop(timingIdx + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) {
                cues += SubtitleCue(startMs = startMs, endMs = endMs, text = text, cueSettings = settings)
            }
        }
        return cues
    }

    /**
     * Parses Blu-ray PGS (.sup) binary packet headers (`PG` magic 0x50 0x47, 90 kHz PTS, segment type).
     * Extracts real timed presentation intervals without misinterpreting binary bytes as text.
     */
    fun parsePgsBinaryTiming(bytes: ByteArray): List<TimedBitmapCue> {
        val cues = mutableListOf<TimedBitmapCue>()
        var offset = 0
        var openStartMs: Long? = null
        var openFileOffset = 0L
        var cueIdx = 0

        while (offset + 13 <= bytes.size) {
            if (bytes[offset] != 0x50.toByte() || bytes[offset + 1] != 0x47.toByte()) {
                offset++
                continue
            }
            val pts90k = ByteBuffer.wrap(bytes, offset + 2, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val timeMs = pts90k / 90L
            val segType = bytes[offset + 10].toInt() and 0xFF
            val segSize = ByteBuffer.wrap(bytes, offset + 11, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

            if (segType == 0x16) { // Presentation Composition Segment (PCS)
                // Byte 11 of PCS payload holds composition_state / num_composition_objects
                val numObjects = if (segSize >= 11 && offset + 13 + 10 < bytes.size) {
                    bytes[offset + 13 + 10].toInt() and 0xFF
                } else 1

                if (numObjects > 0) {
                    if (openStartMs != null && timeMs > openStartMs) {
                        cues += TimedBitmapCue(
                            index = cueIdx++,
                            startMs = openStartMs,
                            endMs = timeMs,
                            rawFileOffset = openFileOffset
                        )
                    }
                    openStartMs = timeMs
                    openFileOffset = offset.toLong()
                } else if (openStartMs != null) {
                    val end = maxOf(timeMs, openStartMs + 500L)
                    cues += TimedBitmapCue(
                        index = cueIdx++,
                        startMs = openStartMs,
                        endMs = end,
                        rawFileOffset = openFileOffset
                    )
                    openStartMs = null
                }
            }
            offset += 13 + segSize
        }

        if (openStartMs != null) {
            cues += TimedBitmapCue(
                index = cueIdx,
                startMs = openStartMs,
                endMs = openStartMs + 3_000L,
                rawFileOffset = openFileOffset
            )
        }
        return cues
    }

    /**
     * Parses DVD VobSub (.idx) timestamp & filepos lines into timed bitmap cues.
     */
    fun parseVobSubIdxTiming(idxText: String): List<TimedBitmapCue> {
        val entries = mutableListOf<Pair<Long, Long>>()
        val regex = Regex("""timestamp:\s*(\d{2}:\d{2}:\d{2}:\d{3}),\s*filepos:\s*([0-9a-fA-F]+)""")
        for (line in idxText.lines()) {
            val match = regex.find(line) ?: continue
            val tsParts = match.groupValues[1].split(":")
            if (tsParts.size == 4) {
                val h = tsParts[0].toLongOrNull() ?: continue
                val m = tsParts[1].toLongOrNull() ?: continue
                val s = tsParts[2].toLongOrNull() ?: continue
                val ms = tsParts[3].toLongOrNull() ?: continue
                val totalMs = ((h * 3600L + m * 60L + s) * 1000L) + ms
                val filePos = match.groupValues[2].toLongOrNull(16) ?: 0L
                entries += totalMs to filePos
            }
        }
        return entries.mapIndexed { idx, (startMs, filePos) ->
            val nextStart = entries.getOrNull(idx + 1)?.first
            val endMs = if (nextStart != null && nextStart > startMs) {
                minOf(nextStart - 50L, startMs + 4_500L).coerceAtLeast(startMs + 500L)
            } else {
                startMs + 3_500L
            }
            TimedBitmapCue(
                index = idx,
                startMs = startMs,
                endMs = endMs,
                rawFileOffset = filePos
            )
        }
    }

    /**
     * Applies millisecond delay offset, optional FPS scaling, negative timestamp clamping,
     * and validation while preserving overlapping cues cleanly.
     */
    fun applyTimingSync(
        cues: List<SubtitleCue>,
        delayMs: Long = 0L,
        fpsRatio: Double = 1.0,
        warnings: MutableList<String> = mutableListOf()
    ): List<SubtitleCue> {
        val safeRatio = if (fpsRatio > 0.1 && fpsRatio < 10.0) fpsRatio else 1.0
        var clampedNegativeCount = 0
        var droppedNegativeCount = 0

        val adjusted = cues.mapNotNull { cue ->
            val scaledStart = (cue.startMs * safeRatio).toLong() + delayMs
            val scaledEnd = (cue.endMs * safeRatio).toLong() + delayMs
            if (scaledEnd <= 0L) {
                droppedNegativeCount++
                return@mapNotNull null
            }
            val safeStart = if (scaledStart < 0L) {
                clampedNegativeCount++
                0L
            } else {
                scaledStart
            }
            val safeEnd = if (scaledEnd <= safeStart) safeStart + 250L else scaledEnd
            cue.copy(startMs = safeStart, endMs = safeEnd)
        }.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })

        if (clampedNegativeCount > 0) {
            warnings += "$clampedNegativeCount cue(s) started before 0:00 after delay adjustment and were clamped to 00:00:00.000."
        }
        if (droppedNegativeCount > 0) {
            warnings += "$droppedNegativeCount cue(s) ended before 0:00 after negative delay and were omitted."
        }
        return adjusted
    }

    fun formatAsWebVtt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        for (cue in cues) {
            sb.append(formatVttTimestamp(cue.startMs))
                .append(" --> ")
                .append(formatVttTimestamp(cue.endMs))
            if (cue.cueSettings.isNotBlank()) {
                sb.append(" ").append(cue.cueSettings.trim())
            }
            sb.append("\n")
            sb.append(cue.text.trim())
            sb.append("\n\n")
        }
        return sb.toString()
    }

    private fun shiftBitmapCues(
        cues: List<TimedBitmapCue>,
        delayMs: Long,
        fpsRatio: Double
    ): List<TimedBitmapCue> {
        val safeRatio = if (fpsRatio > 0.1 && fpsRatio < 10.0) fpsRatio else 1.0
        return cues.mapNotNull { cue ->
            val s = (cue.startMs * safeRatio).toLong() + delayMs
            val e = (cue.endMs * safeRatio).toLong() + delayMs
            if (e <= 0L) null else cue.copy(startMs = s.coerceAtLeast(0L), endMs = maxOf(e, s.coerceAtLeast(0L) + 250L))
        }.sortedBy { it.startMs }
    }

    private fun serializeBitmapManifest(format: String, cues: List<TimedBitmapCue>): String {
        val root = JSONObject()
        root.put("type", "lumacast_bitmap_subtitles")
        root.put("format", format)
        root.put("cueCount", cues.size)
        val arr = JSONArray()
        for (cue in cues) {
            arr.put(
                JSONObject().apply {
                    put("index", cue.index)
                    put("startMs", cue.startMs)
                    put("endMs", cue.endMs)
                    put("rawFileOffset", cue.rawFileOffset)
                    put("uncertainOcr", cue.uncertainOcr)
                    if (cue.ocrText != null) put("ocrText", cue.ocrText)
                }
            )
        }
        root.put("cues", arr)
        return root.toString()
    }

    private fun convertAssTagsToWebVtt(raw: String): Pair<String, String> {
        var cueSettings = ""
        val alignMatch = Regex("""\\an([1-9])""").find(raw)
        if (alignMatch != null) {
            cueSettings = when (alignMatch.groupValues[1]) {
                "1", "4", "7" -> "align:start"
                "3", "6", "9" -> "align:end"
                "8" -> "line:10% align:center"
                else -> "align:center"
            }
        }

        var text = raw
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")

        // Convert common ASS inline styling tags to valid WebVTT tags
        text = text.replace(Regex("""\{\\i1[^}]*\}"""), "<i>")
            .replace(Regex("""\{\\i0[^}]*\}"""), "</i>")
            .replace(Regex("""\{\\b1[^}]*\}"""), "<b>")
            .replace(Regex("""\{\\b0[^}]*\}"""), "</b>")
            .replace(Regex("""\{\\u1[^}]*\}"""), "<u>")
            .replace(Regex("""\{\\u0[^}]*\}"""), "</u>")
            // Strip remaining override blocks cleanly
            .replace(Regex("""\{[^}]*\}"""), "")
            .trim()

        // Balance unclosed inline tags so WebVTT remains strictly valid
        if (text.countOccurrences("<i>") > text.countOccurrences("</i>")) text += "</i>"
        if (text.countOccurrences("<b>") > text.countOccurrences("</b>")) text += "</b>"
        if (text.countOccurrences("<u>") > text.countOccurrences("</u>")) text += "</u>"

        return text to cueSettings
    }

    private fun String.countOccurrences(sub: String): Int {
        var count = 0
        var idx = indexOf(sub)
        while (idx >= 0) {
            count++
            idx = indexOf(sub, idx + sub.length)
        }
        return count
    }

    private fun parseTimestampMs(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        val parts = cleaned.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val secParts = parts[2].split(".")
                val s = secParts[0].toLongOrNull() ?: return null
                val msStr = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3) ?: "0"
                val ms = msStr.toLongOrNull() ?: return null
                ((h * 3600L + m * 60L + s) * 1000L) + ms
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val secParts = parts[1].split(".")
                val s = secParts[0].toLongOrNull() ?: return null
                val msStr = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3) ?: "0"
                val ms = msStr.toLongOrNull() ?: return null
                ((m * 60L + s) * 1000L) + ms
            }
            else -> null
        }
    }

    private fun parseAssTimestampMs(raw: String): Long? {
        // Format: H:MM:SS.cc (centiseconds)
        val parts = raw.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split(".")
        val s = secParts[0].toLongOrNull() ?: return null
        val csStr = secParts.getOrNull(1)?.padEnd(2, '0')?.take(2) ?: "0"
        val cs = csStr.toLongOrNull() ?: return null
        return ((h * 3600L + m * 60L + s) * 1000L) + (cs * 10L)
    }

    private fun parseTtmlTimestampMs(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.endsWith("s", ignoreCase = true) && !trimmed.endsWith("ms", ignoreCase = true)) {
            val sec = trimmed.dropLast(1).toDoubleOrNull() ?: return null
            return (sec * 1000.0).toLong()
        }
        if (trimmed.endsWith("ms", ignoreCase = true)) {
            return trimmed.dropLast(2).toDoubleOrNull()?.toLong()
        }
        return parseTimestampMs(trimmed)
    }

    companion object {
        fun formatVttTimestamp(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            val hours = clamped / 3_600_000L
            val minutes = (clamped % 3_600_000L) / 60_000L
            val seconds = (clamped % 60_000L) / 1_000L
            val millis = clamped % 1_000L
            return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
        }
    }
}
