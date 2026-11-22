package com.example.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.core.common.Formatters
import com.example.core.common.MediaTechnicalInfo
import com.example.core.common.SubtitleConversionStatus
import com.example.core.common.SubtitleConversionStrategy
import com.example.core.common.SubtitleCue
import com.example.core.common.SubtitleFormat
import com.example.core.common.SubtitleSourceType
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class PreparedSubtitleAsset(
    val track: SubtitleTrackInfo,
    val file: File,
    val mimeType: String,
    val cueCount: Int,
    val isBitmapManifest: Boolean
)

/**
 * Manages embedded and external subtitle discovery (strictly via authorized SAF folders
 * and user-picked URIs — never scanning unauthorized directories), filename language/forced/SDH
 * parsing, caching of converted WebVTT/bitmap manifests, and active-stream deletion protection.
 */
class SubtitleManager(
    private val context: Context,
    private val compatibilityManager: SubtitleCompatibilityManager = SubtitleCompatibilityManager(),
    private val ffmpegBackend: FfmpegTranscodeBackend = FfmpegTranscodeBackend(context)
) {

    private val inUseSubtitlePaths = ConcurrentHashMap.newKeySet<String>()

    private val subtitleCacheDir: File
        get() = File(context.cacheDir, "lumacast_subtitles").apply { mkdirs() }

    fun markInUse(file: File) {
        inUseSubtitlePaths.add(file.absolutePath)
    }

    fun releaseAllInUse() {
        inUseSubtitlePaths.clear()
    }

    fun clearUnusedCache(): Long {
        var cleared = 0L
        subtitleCacheDir.listFiles()?.forEach { file ->
            if (!inUseSubtitlePaths.contains(file.absolutePath)) {
                val len = file.length()
                if (file.delete()) cleared += len
            }
        }
        return cleared
    }

    /**
     * Discovers all embedded subtitles from [technicalInfo] plus associated external subtitle files
     * inside user-authorized SAF folders ([authorizedSafFolderUris]) and manually picked URIs.
     */
    suspend fun discoverSubtitles(
        video: VideoItem,
        technicalInfo: MediaTechnicalInfo?,
        authorizedSafFolderUris: Set<String>,
        manualSubtitleUris: List<Uri>,
        isCustomReceiver: Boolean
    ): List<SubtitleTrackInfo> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<SubtitleTrackInfo>()
        var nextCastTrackId = 2000L

        // 1. Embedded subtitles from container inspection
        technicalInfo?.subtitleTracks?.forEach { emb ->
            val strategy = compatibilityManager.determineStrategy(emb, isCustomReceiver)
            discovered += emb.copy(
                castTrackId = nextCastTrackId++,
                conversionStrategy = strategy
            )
        }

        val videoBaseName = video.displayName.substringBeforeLast(".").lowercase(Locale.US)
        val seenUris = mutableSetOf<String>()

        // 2. Associated external subtitle files in user-authorized SAF folders ONLY
        for (folderStr in authorizedSafFolderUris) {
            val treeUri = runCatching { Uri.parse(folderStr) }.getOrNull() ?: continue
            val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: continue
            if (!root.canRead()) continue

            val files = collectMatchingSubtitleDocuments(root, videoBaseName, maxDepth = 3)
            val subCompanions = files.filter {
                it.name?.substringAfterLast('.', "")?.lowercase(Locale.US) == "sub"
            }.associateBy {
                it.name?.substringBeforeLast('.')?.lowercase(Locale.US).orEmpty()
            }

            for (doc in files) {
                val fileName = doc.name ?: continue
                val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext == "sub" && subCompanions.containsKey(fileName.substringBeforeLast('.').lowercase(Locale.US))) {
                    // Pair .sub with its .idx track instead of duplicating
                    val hasIdx = files.any {
                        it.name.equals("${fileName.substringBeforeLast('.')}.idx", ignoreCase = true)
                    }
                    if (hasIdx) continue
                }
                if (!seenUris.add(doc.uri.toString())) continue

                val companionSub = if (ext == "idx") {
                    subCompanions[fileName.substringBeforeLast('.').lowercase(Locale.US)]?.uri
                } else null

                val parsed = parseExternalSubtitleMetadata(
                    uri = doc.uri,
                    fileName = fileName,
                    videoBaseName = videoBaseName,
                    sourceType = SubtitleSourceType.EXTERNAL_SAF,
                    castTrackId = nextCastTrackId++,
                    companionSubUri = companionSub
                ) ?: continue

                discovered += parsed.copy(
                    conversionStrategy = compatibilityManager.determineStrategy(parsed, isCustomReceiver)
                )
            }
        }

        // 3. Manually picked external subtitle files via SAF OpenDocument
        for (uri in manualSubtitleUris) {
            if (!seenUris.add(uri.toString())) continue
            val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.srt"
            val parsed = parseExternalSubtitleMetadata(
                uri = uri,
                fileName = name,
                videoBaseName = videoBaseName,
                sourceType = SubtitleSourceType.EXTERNAL_PICKED,
                castTrackId = nextCastTrackId++,
                companionSubUri = null
            ) ?: continue
            discovered += parsed.copy(
                conversionStrategy = compatibilityManager.determineStrategy(parsed, isCustomReceiver)
            )
        }

        discovered
    }

    /**
     * Converts (or loads from cache) the requested [track] into a Chromecast-ready `.vtt`, `.ttml`,
     * or `.json` timed bitmap manifest file with [delayMs] synchronization applied.
     */
    suspend fun prepareSubtitleAsset(
        video: VideoItem,
        track: SubtitleTrackInfo,
        delayMs: Long,
        onProgress: (Int) -> Unit = {}
    ): PreparedSubtitleAsset = withContext(Dispatchers.IO) {
        onProgress(10)
        val cacheKey = "${track.trackId.hashCode()}_${delayMs}_${track.format.name.lowercase(Locale.US)}"
        val ext = if (track.format.isBitmap) "json" else "vtt"
        val cachedFile = File(subtitleCacheDir, "sub_$cacheKey.$ext")
        val mime = if (track.format.isBitmap) "application/json; charset=utf-8" else "text/vtt; charset=utf-8"

        // Reuse cached converted subtitle to avoid repeated conversion of identical tracks
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            markInUse(cachedFile)
            onProgress(100)
            return@withContext PreparedSubtitleAsset(
                track = track.copy(
                    conversionStatus = if (track.format.isBitmap) {
                        SubtitleConversionStatus.REQUIRES_OCR_REVIEW
                    } else {
                        SubtitleConversionStatus.READY
                    },
                    preparedFilePath = cachedFile.absolutePath
                ),
                file = cachedFile,
                mimeType = mime,
                cueCount = track.cueCount.coerceAtLeast(1),
                isBitmapManifest = track.format.isBitmap
            )
        }

        onProgress(35)
        val outcome = when (track.sourceType) {
            SubtitleSourceType.EMBEDDED -> extractAndConvertEmbeddedSubtitle(video, track, delayMs)
            SubtitleSourceType.EXTERNAL_SAF,
            SubtitleSourceType.EXTERNAL_PICKED -> {
                val uri = track.uri ?: throw IllegalStateException("External subtitle URI missing.")
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read external subtitle file: ${track.fileName}")
                compatibilityManager.convertToCompatibleOutput(
                    rawBytes = rawBytes,
                    format = track.format,
                    delayMs = delayMs
                )
            }
        }

        onProgress(80)
        val partFile = File(subtitleCacheDir, "sub_$cacheKey.$ext.part")
        val payload = if (outcome.isBitmapOutput) {
            outcome.bitmapManifestJson ?: throw IllegalStateException("Bitmap subtitle manifest empty.")
        } else {
            outcome.webVttContent ?: throw IllegalStateException("Converted WebVTT content empty.")
        }
        partFile.writeText(payload, Charsets.UTF_8)
        if (!partFile.renameTo(cachedFile)) {
            partFile.copyTo(cachedFile, overwrite = true)
            partFile.delete()
        }
        markInUse(cachedFile)
        onProgress(100)

        PreparedSubtitleAsset(
            track = track.copy(
                characterEncoding = outcome.detectedEncoding,
                conversionStatus = if (outcome.isBitmapOutput) {
                    SubtitleConversionStatus.REQUIRES_OCR_REVIEW
                } else {
                    SubtitleConversionStatus.READY
                },
                preparedFilePath = cachedFile.absolutePath,
                cueCount = outcome.cueCount,
                ocrReviewNotes = outcome.validationWarnings.firstOrNull()
            ),
            file = cachedFile,
            mimeType = mime,
            cueCount = outcome.cueCount,
            isBitmapManifest = outcome.isBitmapOutput
        )
    }

    /**
     * Selects the best automatic subtitle track based on [preferredLanguage] ("off", "auto", or ISO code)
     * prioritizing forced tracks when language matches audio, or explicit preferred language tracks.
     */
    fun selectDefaultSubtitle(
        tracks: List<SubtitleTrackInfo>,
        preferredLanguage: String
    ): SubtitleTrackInfo? {
        if (tracks.isEmpty()) return null
        val pref = preferredLanguage.trim().lowercase(Locale.US)
        if (pref == "off" || pref.isBlank()) return null

        if (pref != "auto") {
            val normPref = Formatters.normalizeLanguageCode(pref) ?: pref
            val langMatch = tracks.firstOrNull {
                Formatters.normalizeLanguageCode(it.languageCode) == normPref && !it.format.isBitmap
            } ?: tracks.firstOrNull {
                Formatters.normalizeLanguageCode(it.languageCode) == normPref
            }
            if (langMatch != null) return langMatch
        }

        return tracks.firstOrNull { it.isDefault && !it.format.isBitmap }
            ?: tracks.firstOrNull { it.isForced && !it.format.isBitmap }
    }

    private fun extractAndConvertEmbeddedSubtitle(
        video: VideoItem,
        track: SubtitleTrackInfo,
        delayMs: Long
    ): SubtitleConversionOutcome {
        val trackIdx = track.embeddedTrackIndex
            ?: throw IllegalStateException("Embedded subtitle track index missing.")

        if (track.format.isBitmap) {
            // Read bitmap packets via MediaExtractor for PGS timing extraction
            val extractor = MediaExtractor()
            val cues = mutableListOf<com.example.core.common.TimedBitmapCue>()
            try {
                extractor.setDataSource(context, video.uri, null)
                extractor.selectTrack(trackIdx)
                val buf = ByteBuffer.allocate(256 * 1024)
                var idx = 0
                while (true) {
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) break
                    val ptsMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L) + delayMs
                    if (ptsMs >= 0L) {
                        cues += com.example.core.common.TimedBitmapCue(
                            index = idx++,
                            startMs = ptsMs,
                            endMs = ptsMs + 3_000L
                        )
                    }
                    extractor.advance()
                    if (idx >= 5000) break
                }
            } finally {
                runCatching { extractor.release() }
            }
            return SubtitleConversionOutcome(
                webVttContent = null,
                bitmapManifestJson = org.json.JSONObject().apply {
                    put("type", "lumacast_bitmap_subtitles")
                    put("format", track.format.name)
                    put("cueCount", cues.size)
                }.toString(),
                cueCount = cues.size,
                detectedEncoding = "BINARY-${track.format.name}",
                validationWarnings = listOf("Embedded ${track.format.displayLabel} bitmap cues extracted for timed overlay."),
                isBitmapOutput = true
            )
        }

        // Extract embedded text subtitle samples via MediaExtractor
        val extractor = MediaExtractor()
        val cues = mutableListOf<SubtitleCue>()
        try {
            extractor.setDataSource(context, video.uri, null)
            extractor.selectTrack(trackIdx)
            val buffer = ByteBuffer.allocate(128 * 1024)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val startMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L)
                val bytes = ByteArray(size)
                buffer.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                    .replace("\\N", "\n")
                    .replace(Regex("\\{[^}]*\\}"), "")
                    .trim()
                if (text.isNotEmpty()) {
                    cues += SubtitleCue(startMs = startMs, endMs = startMs + 3_500L, text = text)
                }
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
        }

        if (cues.isEmpty()) {
            throw IllegalStateException(
                "Could not extract embedded ${track.format.displayLabel} cues directly from ${video.containerLabel}. Load an external .srt/.vtt file or bundle FFmpeg Backend B."
            )
        }

        // Clamp overlapping auto-estimated end timestamps to next cue start when clean
        val refined = cues.mapIndexed { idx, cue ->
            val nextStart = cues.getOrNull(idx + 1)?.startMs
            if (nextStart != null && nextStart > cue.startMs && nextStart < cue.endMs) {
                cue.copy(endMs = nextStart)
            } else cue
        }
        val warnings = mutableListOf<String>()
        val synced = compatibilityManager.applyTimingSync(refined, delayMs = delayMs, warnings = warnings)
        return SubtitleConversionOutcome(
            webVttContent = compatibilityManager.formatAsWebVtt(synced),
            bitmapManifestJson = null,
            cueCount = synced.size,
            detectedEncoding = "UTF-8",
            validationWarnings = warnings,
            isBitmapOutput = false
        )
    }

    private fun collectMatchingSubtitleDocuments(
        dir: DocumentFile,
        videoBaseName: String,
        maxDepth: Int
    ): List<DocumentFile> {
        if (maxDepth < 0) return emptyList()
        val matches = mutableListOf<DocumentFile>()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        for (child in children) {
            if (child.isDirectory) {
                val dirName = child.name?.lowercase(Locale.US).orEmpty()
                if (dirName.contains("sub") || maxDepth > 1) {
                    matches += collectMatchingSubtitleDocuments(child, videoBaseName, maxDepth - 1)
                }
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in SUPPORTED_SUBTITLE_EXTENSIONS && isMatchingSubtitleFilename(name, videoBaseName)) {
                    matches += child
                }
            }
        }
        return matches
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    companion object {
        val SUPPORTED_SUBTITLE_EXTENSIONS = setOf(
            "srt", "ass", "ssa", "vtt", "ttml", "dfxp", "idx", "sub", "sup"
        )

        fun isMatchingSubtitleFilename(subtitleFileName: String, videoBaseName: String): Boolean {
            val lowerSub = subtitleFileName.substringBeforeLast('.').lowercase(Locale.US)
            val lowerVid = videoBaseName.lowercase(Locale.US)
            if (lowerSub == lowerVid) return true
            if (lowerSub.startsWith("$lowerVid.") || lowerSub.startsWith("${lowerVid}_") || lowerSub.startsWith("$lowerVid-")) {
                return true
            }
            return false
        }

        /**
         * Parses external subtitle filename conventions such as:
         * - Movie.srt
         * - Movie.en.srt
         * - Movie.hi.forced.srt
         * - Movie.bn.sdh.vtt
         * - Movie.ja.ass
         */
        fun parseExternalSubtitleMetadata(
            uri: Uri?,
            fileName: String,
            videoBaseName: String,
            sourceType: SubtitleSourceType,
            castTrackId: Long,
            companionSubUri: Uri? = null
        ): SubtitleTrackInfo? {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            val format = formatFromExtension(ext)
            if (format == SubtitleFormat.UNKNOWN) return null

            val stem = fileName.substringBeforeLast('.')
            val suffixRaw = if (stem.lowercase(Locale.US).startsWith(videoBaseName.lowercase(Locale.US))) {
                stem.drop(videoBaseName.length).trimStart('.', '_', '-', ' ')
            } else {
                stem
            }

            val tokens = suffixRaw.split('.', '_', '-', ' ').map { it.trim() }.filter { it.isNotBlank() }
            var langCode: String? = null
            var isForced = false
            var isSdh = false

            for (token in tokens) {
                val lower = token.lowercase(Locale.US)
                when {
                    lower == "forced" || lower == "foreign" -> isForced = true
                    lower == "sdh" || lower == "cc" || lower == "hearing" || lower == "hi-sdh" -> isSdh = true
                    langCode == null -> {
                        val candidate = Formatters.normalizeLanguageCode(lower)
                        if (candidate != null) langCode = candidate
                    }
                }
            }

            val langDisplay = Formatters.languageDisplayName(langCode)
            val flags = buildList {
                if (isForced) add("Forced")
                if (isSdh) add("SDH")
            }
            val title = buildString {
                append(langDisplay)
                if (flags.isNotEmpty()) append(" (${flags.joinToString(", ")})")
                append(" · ${format.extension.uppercase(Locale.US)}")
            }

            return SubtitleTrackInfo(
                trackId = "sub_ext_${fileName.hashCode()}_${uri?.toString()?.hashCode() ?: 0}",
                castTrackId = castTrackId,
                sourceType = sourceType,
                uri = uri,
                companionSubUri = companionSubUri,
                fileName = fileName,
                languageCode = langCode,
                languageDisplay = langDisplay,
                title = title,
                format = format,
                isDefault = false,
                isForced = isForced,
                isSdh = isSdh
            )
        }

        fun formatFromExtension(ext: String): SubtitleFormat = when (ext.lowercase(Locale.US)) {
            "srt" -> SubtitleFormat.SRT
            "ass" -> SubtitleFormat.ASS
            "ssa" -> SubtitleFormat.SSA
            "vtt" -> SubtitleFormat.WEBVTT
            "ttml", "dfxp" -> SubtitleFormat.TTML
            "sup" -> SubtitleFormat.PGS
            "idx", "sub" -> SubtitleFormat.VOBSUB
            else -> SubtitleFormat.UNKNOWN
        }
    }
}
