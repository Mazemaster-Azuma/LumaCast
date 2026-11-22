package com.example.core.common

import java.util.Locale

object Formatters {

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds <= 0L) return "Calculating…"
        val mins = seconds / 60L
        val secs = seconds % 60L
        return when {
            mins >= 60L -> String.format(Locale.US, "~%dh %dm left", mins / 60L, mins % 60L)
            mins > 0L -> String.format(Locale.US, "~%dm %02ds left", mins, secs)
            else -> String.format(Locale.US, "~%ds left", secs)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatBitrate(bps: Long?): String {
        if (bps == null || bps <= 0L) return "Unknown"
        return when {
            bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
            bps >= 1_000L -> String.format(Locale.US, "%d kbps", bps / 1_000L)
            else -> "$bps bps"
        }
    }

    fun resolutionTag(width: Int, height: Int): String {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return when {
            longEdge >= 3800 || shortEdge >= 2100 -> "4K UHD"
            longEdge >= 2500 || shortEdge >= 1400 -> "1440p"
            longEdge >= 1900 || shortEdge >= 1060 -> "1080p"
            longEdge >= 1260 || shortEdge >= 700 -> "720p"
            shortEdge > 0 -> "${shortEdge}p"
            else -> "SD"
        }
    }

    /**
     * Formats original video dimensions as "1920 × 1080", "3840 × 2160", or "1080 × 1920",
     * respecting rotation/orientation metadata so portrait and landscape videos are never confused.
     */
    fun formatExactResolution(width: Int, height: Int, orientationDegrees: Int = 0): String {
        if (width <= 0 || height <= 0) return "Resolution unavailable"
        val normalizedRotation = ((orientationDegrees % 360) + 360) % 360
        val displayWidth = if (normalizedRotation == 90 || normalizedRotation == 270) height else width
        val displayHeight = if (normalizedRotation == 90 || normalizedRotation == 270) width else height
        return "$displayWidth × $displayHeight"
    }

    /**
     * Maps container file extension / MIME type to a crisp, human-readable video format label.
     */
    fun humanReadableContainerFormat(extension: String, mimeType: String): String {
        val ext = extension.lowercase(Locale.US).trim()
        val mime = mimeType.lowercase(Locale.US).trim()
        return when {
            ext == "mp4" || ext == "m4v" || mime == "video/mp4" -> "MP4"
            ext == "mkv" || mime == "video/x-matroska" -> "MKV (Matroska)"
            ext == "webm" || mime == "video/webm" -> "WebM"
            ext == "mov" || mime == "video/quicktime" -> "MOV (QuickTime)"
            ext == "avi" || mime == "video/x-msvideo" -> "AVI"
            ext == "ts" || ext == "m2ts" || mime == "video/mp2t" -> "MPEG-TS"
            ext == "3gp" || ext == "3gpp" || mime == "video/3gpp" -> "3GP"
            ext == "flv" || mime == "video/x-flv" -> "FLV"
            ext == "wmv" || mime == "video/x-ms-wmv" -> "WMV"
            ext == "mpg" || ext == "mpeg" || mime == "video/mpeg" -> "MPEG"
            ext.isNotBlank() -> ext.uppercase(Locale.US)
            mime.startsWith("video/") -> mime.removePrefix("video/").uppercase(Locale.US)
            else -> "Video"
        }
    }

    /**
     * Maps image file extension / MIME type to a crisp, human-readable image format label.
     */
    fun humanReadableImageFormat(extension: String, mimeType: String): String {
        val ext = extension.lowercase(Locale.US).trim()
        val mime = mimeType.lowercase(Locale.US).trim()
        return when {
            ext == "jpg" || ext == "jpeg" || mime == "image/jpeg" -> "JPEG"
            ext == "png" || mime == "image/png" -> "PNG"
            ext == "webp" || mime == "image/webp" -> "WebP"
            ext == "heic" || ext == "heif" || mime == "image/heic" || mime == "image/heif" -> "HEIF"
            ext == "gif" || mime == "image/gif" -> "GIF"
            ext == "avif" || mime == "image/avif" -> "AVIF"
            ext == "bmp" || mime == "image/bmp" -> "BMP"
            ext == "dng" || mime == "image/x-adobe-dng" -> "RAW (DNG)"
            ext.isNotBlank() -> ext.uppercase(Locale.US)
            mime.startsWith("image/") -> mime.removePrefix("image/").uppercase(Locale.US)
            else -> "Image"
        }
    }

    fun aspectRatioLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "Unknown"
        val ratio = width.toFloat() / height.toFloat()
        return when {
            kotlin.math.abs(ratio - 16f / 9f) < 0.05f -> "16:9"
            kotlin.math.abs(ratio - 4f / 3f) < 0.05f -> "4:3"
            kotlin.math.abs(ratio - 21f / 9f) < 0.08f -> "21:9"
            kotlin.math.abs(ratio - 2.39f) < 0.06f -> "2.39:1"
            kotlin.math.abs(ratio - 1.85f) < 0.05f -> "1.85:1"
            else -> String.format(Locale.US, "%.2f:1", ratio)
        }
    }

    fun channelLayoutLabel(channels: Int?): String = when (channels) {
        1 -> "Mono (1.0)"
        2 -> "Stereo (2.0)"
        3 -> "2.1"
        4 -> "Quad (4.0)"
        6 -> "5.1 Surround"
        8 -> "7.1 Surround"
        null -> "Unknown"
        else -> "${channels}ch"
    }

    fun normalizeLanguageCode(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val cleaned = code.lowercase(Locale.US).trim().substringBefore("-").substringBefore("_")
        if (cleaned == "und" || cleaned == "unknown") return null
        return when (cleaned) {
            "en", "eng", "english" -> "en"
            "hi", "hin", "hindi" -> "hi"
            "bn", "ben", "bengali", "bangla" -> "bn"
            "es", "spa", "spanish" -> "es"
            "fr", "fra", "fre", "french" -> "fr"
            "de", "deu", "ger", "german" -> "de"
            "it", "ita", "italian" -> "it"
            "pt", "por", "portuguese" -> "pt"
            "ja", "jpn", "japanese" -> "ja"
            "ko", "kor", "korean" -> "ko"
            "zh", "zho", "chi", "chinese" -> "zh"
            "ar", "ara", "arabic" -> "ar"
            "ru", "rus", "russian" -> "ru"
            "ta", "tam", "tamil" -> "ta"
            "te", "tel", "telugu" -> "te"
            "ml", "mal", "malayalam" -> "ml"
            "mr", "mar", "marathi" -> "mr"
            "ur", "urd", "urdu" -> "ur"
            "tr", "tur", "turkish" -> "tr"
            "pl", "pol", "polish" -> "pl"
            "nl", "nld", "dut", "dutch" -> "nl"
            else -> if (cleaned.length in 2..3 && cleaned.all { it in 'a'..'z' }) cleaned else null
        }
    }

    fun languageDisplayName(code: String?): String {
        val norm = normalizeLanguageCode(code) ?: return "Default"
        val quick = when (norm) {
            "en" -> "English"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "ru" -> "Russian"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "ml" -> "Malayalam"
            "mr" -> "Marathi"
            "ur" -> "Urdu"
            "tr" -> "Turkish"
            "pl" -> "Polish"
            "nl" -> "Dutch"
            else -> null
        }
        return quick ?: runCatching {
            Locale.forLanguageTag(norm).displayLanguage.takeIf { it.isNotBlank() }
        }.getOrNull() ?: norm.uppercase(Locale.US)
    }

    fun formatDelayMs(delayMs: Long): String {
        return when {
            delayMs == 0L -> "0 ms (Synced)"
            delayMs > 0L -> "+${delayMs} ms (Later)"
            else -> "${delayMs} ms (Earlier)"
        }
    }

    fun friendlyVideoCodec(mime: String?): String = when (mime?.lowercase(Locale.US)) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/x-xvid", "video/xvid" -> "Xvid"
        "video/divx", "video/x-divx" -> "DivX"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        null -> "Unknown"
        else -> mime.substringAfter("/").uppercase(Locale.US)
    }

    fun friendlyAudioCodec(mime: String?): String = when (mime?.lowercase(Locale.US)) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/ac3" -> "AC-3 (Dolby Digital)"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3 (Dolby Digital Plus)"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd" -> "DTS-HD"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/raw", "audio/wav", "audio/x-wav" -> "PCM / WAV"
        null -> "None"
        else -> mime.substringAfter("/").uppercase(Locale.US)
    }
}
