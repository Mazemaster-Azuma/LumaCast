package com.example.core.common

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class VideoSourceType {
    MEDIA_STORE,
    SAF_FOLDER,
    PHOTO_PICKER
}

enum class LibraryAccessState {
    ACCESSIBLE,
    PARTIAL_ACCESS,
    MEDIA_STORE_UNAVAILABLE,
    EMPTY_NO_SOURCES
}

enum class MediaPermissionStatus {
    FULL_ACCESS,
    PARTIAL_ACCESS,
    DENIED
}

enum class MediaLibraryTab(val label: String) {
    VIDEOS("Videos"),
    IMAGES("Images")
}

@Immutable
data class VideoItem(
    val id: String,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val extension: String,
    val dateAddedSec: Long,
    val folderName: String,
    val sourceType: VideoSourceType,
    val dateModifiedSec: Long = dateAddedSec,
    val relativePath: String? = null,
    val bucketDisplayName: String? = null,
    val parentTreeUri: Uri? = null,
    val orientationDegrees: Int = 0,
    val canRename: Boolean = false,
    val canDelete: Boolean = false
) {
    val effectiveWidth: Int
        get() = if (orientationDegrees == 90 || orientationDegrees == 270) height else width

    val effectiveHeight: Int
        get() = if (orientationDegrees == 90 || orientationDegrees == 270) width else height

    val isPortrait: Boolean
        get() = effectiveWidth > 0 && effectiveHeight > 0 && effectiveHeight > effectiveWidth

    val pixelCount: Long
        get() = effectiveWidth.toLong() * effectiveHeight.toLong()

    val resolutionLabel: String
        get() = Formatters.resolutionTag(effectiveWidth, effectiveHeight)

    /**
     * Exact original resolution preserving portrait vs landscape orientation,
     * e.g., "1920 × 1080", "3840 × 2160", or "1080 × 1920".
     */
    val exactResolutionLabel: String
        get() = Formatters.formatExactResolution(width, height, orientationDegrees)

    val containerLabel: String
        get() = extension.uppercase().ifBlank {
            mimeType.substringAfter("/", "VIDEO").uppercase()
        }

    val humanReadableFormat: String
        get() = Formatters.humanReadableContainerFormat(extension, mimeType)
}

@Immutable
data class ImageItem(
    val id: String,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val extension: String,
    val dateAddedSec: Long,
    val folderName: String,
    val sourceType: VideoSourceType = VideoSourceType.MEDIA_STORE,
    val dateModifiedSec: Long = dateAddedSec,
    val relativePath: String? = null,
    val bucketDisplayName: String? = null,
    val orientationDegrees: Int = 0
) {
    val effectiveWidth: Int
        get() = if (orientationDegrees == 90 || orientationDegrees == 270) height else width

    val effectiveHeight: Int
        get() = if (orientationDegrees == 90 || orientationDegrees == 270) width else height

    val pixelCount: Long
        get() = effectiveWidth.toLong() * effectiveHeight.toLong()

    val exactResolutionLabel: String
        get() = Formatters.formatExactResolution(width, height, orientationDegrees)

    val formatLabel: String
        get() = Formatters.humanReadableImageFormat(extension, mimeType)
}

@Immutable
data class IndexedFolder(
    val treeUri: Uri,
    val displayName: String,
    val videoCount: Int
)

enum class SortOption(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    NAME_ASC("A–Z"),
    DURATION_DESC("Duration"),
    SIZE_DESC("File Size"),
    RESOLUTION_DESC("Resolution")
}

enum class ViewMode {
    LIST,
    GRID
}

enum class ReceiverProfile(
    val displayName: String,
    val summary: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val supportsHevc: Boolean,
    val supportsHdr10: Boolean,
    val supportsDolbyVision: Boolean,
    val supportsVp9: Boolean,
    val supportsAc3Passthrough: Boolean,
    val supportsMultichannelAac51: Boolean
) {
    STANDARD_1080P(
        displayName = "Chromecast HD / Standard TV",
        summary = "Up to 1080p60 · H.264, VP8 · AAC Stereo, MP3, Opus",
        maxWidth = 1920,
        maxHeight = 1080,
        supportsHevc = false,
        supportsHdr10 = false,
        supportsDolbyVision = false,
        supportsVp9 = false,
        supportsAc3Passthrough = false,
        supportsMultichannelAac51 = false
    ),
    ULTRA_4K_HDR(
        displayName = "Chromecast 4K / Google TV",
        summary = "Up to 4K60 · H.264, HEVC, VP9 · HDR10 · AAC 5.1, AC-3, E-AC-3, Opus",
        maxWidth = 3840,
        maxHeight = 2160,
        supportsHevc = true,
        supportsHdr10 = true,
        supportsDolbyVision = true,
        supportsVp9 = true,
        supportsAc3Passthrough = true,
        supportsMultichannelAac51 = true
    )
}

enum class HdrFormat(val label: String) {
    SDR("SDR (8-bit)"),
    HDR10("HDR10 (PQ)"),
    HLG("HLG"),
    DOLBY_VISION("Dolby Vision")
}

enum class ProcessingBackendType(val label: String) {
    DIRECT("Direct LAN Stream"),
    NATIVE_MUXER("Android MediaMuxer"),
    MEDIA3_TRANSFORMER("Android Media3 Transformer (HW)"),
    FFMPEG("FFmpeg Engine")
}

enum class AudioTrackRole(val label: String) {
    MAIN("Main"),
    COMMENTARY("Commentary"),
    DESCRIPTIVE("Audio Description"),
    DUB("Dub")
}

enum class TrackOrigin(val label: String) {
    ORIGINAL("Original"),
    CONVERTED("Converted"),
    EXTERNAL("External")
}

@Immutable
data class VideoTrackInfo(
    val trackIndex: Int,
    val mimeType: String,
    val codecLabel: String,
    val profile: String?,
    val level: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val bitrateBps: Long?,
    val pixelFormat: String?,
    val colorSpace: String?,
    val hdrFormat: HdrFormat,
    val aspectRatio: String,
    val isInterlaced: Boolean,
    val hardwareDecoderAvailable: Boolean,
    val firstPresentationTimeUs: Long = 0L
)

@Immutable
data class AudioTrackInfo(
    val trackIndex: Int,
    val audioStreamOrdinal: Int,
    val mimeType: String,
    val codecLabel: String,
    val sampleRateHz: Int?,
    val bitrateBps: Long?,
    val channelCount: Int?,
    val channelLayout: String,
    val bitDepth: Int?,
    val audioProfile: String?,
    val languageCode: String?,
    val languageDisplay: String,
    val title: String?,
    val isDefault: Boolean,
    val audioDelayMs: Long = 0L,
    val hardwareDecoderAvailable: Boolean = true,
    val uniqueId: String = "audio_${trackIndex}_${audioStreamOrdinal}",
    val role: AudioTrackRole = AudioTrackRole.MAIN,
    val origin: TrackOrigin = TrackOrigin.ORIGINAL,
    val isCompatibleWithReceiver: Boolean = true,
    val externalUri: Uri? = null,
    val convertedCachePath: String? = null
) {
    val formattedSummaryLine: String
        get() {
            val ch = when (channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                null -> channelLayout
                else -> "${channelCount}ch"
            }
            return "$languageDisplay — $codecLabel — $ch"
        }
}

@Immutable
data class AudioConversionPlan(
    val sourceTrack: AudioTrackInfo,
    val preserveOriginal: Boolean,
    val targetCodecMime: String,
    val targetCodecLabel: String,
    val targetSampleRateHz: Int,
    val targetChannels: Int,
    val targetChannelLayout: String,
    val targetBitrateBps: Int,
    val downmixRequired: Boolean,
    val downmixDescription: String?
)

// ==================== SUBTITLE DOMAIN MODELS ====================

enum class SubtitleFormat(
    val displayLabel: String,
    val extension: String,
    val mimeType: String,
    val isBitmap: Boolean
) {
    WEBVTT("WebVTT", "vtt", "text/vtt", false),
    SRT("SubRip (SRT)", "srt", "application/x-subrip", false),
    ASS("Advanced SubStation (ASS)", "ass", "text/x-ssa", false),
    SSA("SubStation Alpha (SSA)", "ssa", "text/x-ssa", false),
    TTML("TTML / DFXP", "ttml", "application/ttml+xml", false),
    PGS("Blu-ray PGS (Bitmap)", "sup", "application/pgs", true),
    VOBSUB("DVD VobSub (Bitmap)", "idx", "application/vobsub", true),
    UNKNOWN("Unknown", "", "text/plain", false)
}

enum class SubtitleSourceType(val label: String) {
    EMBEDDED("Embedded"),
    EXTERNAL_SAF("External (Folder)"),
    EXTERNAL_PICKED("External (Picked)")
}

enum class SubtitleConversionStrategy(val label: String) {
    DIRECT_WEBVTT("Direct WebVTT"),
    DIRECT_TTML("Direct TTML"),
    CONVERT_SRT_TO_VTT("Convert SRT → WebVTT"),
    CONVERT_ASS_TO_VTT("Convert ASS/SSA → WebVTT"),
    CONVERT_TTML_TO_VTT("Convert TTML → WebVTT"),
    EXTRACT_AND_CONVERT_TO_VTT("Extract Embedded → WebVTT"),
    BITMAP_TIMED_OVERLAY_OR_OCR("Bitmap Timed Overlay / OCR"),
    BURN_IN_LAST_RESORT("Burn-In Fallback (Last Resort)"),
    UNSUPPORTED("Unsupported")
}

enum class SubtitleConversionStatus(val label: String) {
    IDLE("Not Converted"),
    CONVERTING("Converting…"),
    READY("Ready"),
    REQUIRES_OCR_REVIEW("Bitmap / OCR Review"),
    FAILED("Conversion Failed")
}

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val cueSettings: String = "",
    val uncertainOcr: Boolean = false
)

data class TimedBitmapCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val rawFileOffset: Long = 0L,
    val ocrText: String? = null,
    val uncertainOcr: Boolean = true
)

@Immutable
data class SubtitleTrackInfo(
    val trackId: String,
    val castTrackId: Long,
    val sourceType: SubtitleSourceType,
    val embeddedTrackIndex: Int? = null,
    val subtitleStreamOrdinal: Int? = null,
    val uri: Uri? = null,
    val companionSubUri: Uri? = null, // .sub companion for VobSub .idx
    val fileName: String? = null,
    val languageCode: String?,
    val languageDisplay: String,
    val title: String,
    val format: SubtitleFormat,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isSdh: Boolean = false,
    val characterEncoding: String = "UTF-8",
    val conversionStrategy: SubtitleConversionStrategy = SubtitleConversionStrategy.DIRECT_WEBVTT,
    val conversionStatus: SubtitleConversionStatus = SubtitleConversionStatus.IDLE,
    val preparedFilePath: String? = null,
    val servedUrl: String? = null,
    val ocrReviewNotes: String? = null,
    val cueCount: Int = 0
)

enum class SubtitleFontFamily(val label: String, val castGenericFamily: Int, val cssValue: String) {
    SANS_SERIF("Sans-Serif", 0, "sans-serif"),
    MONOSPACED_SANS("Mono Sans", 1, "monospace"),
    SERIF("Serif", 2, "serif"),
    CASUAL("Casual", 4, "cursive")
}

enum class SubtitleEdgeType(val label: String, val castEdgeType: Int, val cssValue: String) {
    NONE("None", 0, "none"),
    OUTLINE("Outline", 1, "outline"),
    DROP_SHADOW("Drop Shadow", 2, "drop-shadow"),
    RAISED("Raised", 3, "raised"),
    DEPRESSED("Depressed", 4, "depressed")
}

enum class SubtitleTextAlign(val label: String, val cssValue: String) {
    LEFT("Left", "left"),
    CENTER("Center", "center"),
    RIGHT("Right", "right")
}

@Immutable
data class SubtitleAppearanceSettings(
    val fontScale: Float = 1.0f,
    val fontFamily: SubtitleFontFamily = SubtitleFontFamily.SANS_SERIF,
    val foregroundColorArgb: Long = 0xFFFFFFFFL,
    val backgroundColorArgb: Long = 0x00000000L,
    val backgroundOpacity: Float = 0.72f,
    val edgeType: SubtitleEdgeType = SubtitleEdgeType.DROP_SHADOW,
    val edgeColorArgb: Long = 0xFF000000L,
    val textAlign: SubtitleTextAlign = SubtitleTextAlign.CENTER,
    val bottomOffsetPercent: Int = 8
)

@Immutable
data class LiveTrackConversionState(
    val trackTypeLabel: String, // "Audio Track" or "Subtitle Track"
    val trackTitle: String,
    val sourceFormat: String,
    val targetFormat: String,
    val progressPercent: Int,
    val statusText: String,
    val estimatedSecondsRemaining: Long? = null
)

@Immutable
data class MediaTechnicalInfo(
    val containerFormat: String,
    val mimeType: String,
    val videoCodecMime: String?,
    val videoCodecLabel: String,
    val videoProfile: String?,
    val videoLevel: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val bitrateBps: Long?,
    val audioCodecMime: String?,
    val audioCodecLabel: String,
    val audioChannels: Int?,
    val audioSampleRateHz: Int?,
    val hdrFormat: HdrFormat,
    val subtitleTrackCount: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val videoTracks: List<VideoTrackInfo> = emptyList(),
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList()
) {
    val primaryVideoTrack: VideoTrackInfo?
        get() = videoTracks.firstOrNull()
}

enum class PlaybackStrategy(
    val label: String,
    val shortBadge: String,
    val preservesVideoBitstream: Boolean,
    val preservesAudioBitstream: Boolean
) {
    DIRECT_CAST("Direct Cast (Original Quality)", "DIRECT", true, true),
    REMUX("Container Remux to MP4 (Lossless)", "REMUX", true, true),
    AUDIO_ONLY_TRANSCODE("Audio-Only Transcode (Video Preserved)", "AUDIO FIX", true, false),
    VIDEO_ONLY_TRANSCODE("Video-Only Transcode (Audio Preserved)", "VIDEO FIX", false, true),
    FULL_TRANSCODE("Full Video & Audio Transcode", "FULL CONVERT", false, false),
    CONSERVATIVE_FALLBACK("Conservative Compatibility Fallback", "SAFE FALLBACK", false, false),
    UNSUPPORTED("Unsupported Media Format", "UNSUPPORTED", false, false)
}

@Immutable
data class CompatibilityIssue(
    val component: String,
    val detail: String,
    val severity: IssueSeverity
)

enum class IssueSeverity {
    INFO,
    WARNING,
    BLOCKING
}

@Immutable
data class CompatibilityReport(
    val videoId: String,
    val receiverProfile: ReceiverProfile,
    val technicalInfo: MediaTechnicalInfo,
    val strategy: PlaybackStrategy,
    val userHeadline: String,
    val summary: String,
    val preservesOriginalQuality: Boolean,
    val videoCompatible: Boolean,
    val audioCompatible: Boolean,
    val containerCompatible: Boolean,
    val selectedAudioTrack: AudioTrackInfo?,
    val audioConversionPlan: AudioConversionPlan?,
    val outputVideoCodec: String,
    val outputAudioCodec: String,
    val outputHdrFormat: HdrFormat,
    val recommendedBackend: ProcessingBackendType,
    val issues: List<CompatibilityIssue>,
    val outputMimeType: String,
    val estimatedTargetResolution: String,
    val estimatedOutputBytes: Long
)

data class OutputValidationResult(
    val isValid: Boolean,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val durationMs: Long,
    val avSyncDeltaMs: Long,
    val reason: String? = null
)

@Immutable
data class ResourceHealthStatus(
    val availableDiskBytes: Long,
    val thermalStatusLabel: String,
    val isThermallyThrottled: Boolean,
    val ffmpegAvailable: Boolean
)

@Immutable
data class CastDeviceInfo(
    val routeId: String,
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isConnecting: Boolean,
    val deviceTypeLabel: String
)

enum class CastConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    UNAVAILABLE
}

enum class RemotePlayerStatus {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

@Immutable
data class RemotePlaybackState(
    val connectionState: CastConnectionState = CastConnectionState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val currentVideo: VideoItem? = null,
    val queue: List<VideoItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val playerStatus: RemotePlayerStatus = RemotePlayerStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 0.8f,
    val isMuted: Boolean = false,
    val activeStrategy: PlaybackStrategy? = null,
    val activeStreamUrl: String? = null,
    val activeAudioLabel: String? = null,
    val playbackQualityLabel: String? = null,
    val availableAudioTracks: List<AudioTrackInfo> = emptyList(),
    val activeAudioTrackId: String? = null, // Updated ONLY upon receiver confirmation
    val pendingAudioTrackId: String? = null,
    val availableSubtitles: List<SubtitleTrackInfo> = emptyList(),
    val activeSubtitleTrackId: String? = null, // null = Off; updated ONLY upon receiver confirmation
    val pendingSubtitleTrackId: String? = null,
    val subtitleDelayMs: Long = 0L,
    val subtitleAppearance: SubtitleAppearanceSettings = SubtitleAppearanceSettings(),
    val isCustomReceiverConnected: Boolean = false,
    val receiverTrackSwitchingSupported: Boolean = false,
    val lastError: CastError? = null
) {
    val isCastingActive: Boolean
        get() = connectionState == CastConnectionState.CONNECTED &&
            (currentVideo != null || playerStatus == RemotePlayerStatus.PLAYING ||
                playerStatus == RemotePlayerStatus.PAUSED || playerStatus == RemotePlayerStatus.BUFFERING)
}

@Immutable
data class ConversionTelemetry(
    val filename: String,
    val originalVideoCodec: String,
    val originalAudioCodec: String,
    val outputVideoCodec: String,
    val outputAudioCodec: String,
    val originalResolution: String,
    val outputResolution: String,
    val detectedProblem: String,
    val headlineMessage: String,
    val strategy: PlaybackStrategy,
    val backend: ProcessingBackendType,
    val progressPercent: Int,
    val estimatedSecondsRemaining: Long?
)

@Immutable
sealed interface CastPipelineState {
    data object Idle : CastPipelineState
    data class Inspecting(val video: VideoItem) : CastPipelineState
    data class PreparingStream(
        val video: VideoItem,
        val strategy: PlaybackStrategy,
        val progressPercent: Int,
        val stageLabel: String,
        val telemetry: ConversionTelemetry? = null
    ) : CastPipelineState

    data class ValidatingOutput(
        val video: VideoItem,
        val strategy: PlaybackStrategy,
        val telemetry: ConversionTelemetry? = null
    ) : CastPipelineState

    data class StartingServer(
        val video: VideoItem,
        val strategy: PlaybackStrategy,
        val telemetry: ConversionTelemetry? = null
    ) : CastPipelineState

    data class LoadingOnTv(
        val video: VideoItem,
        val deviceName: String,
        val strategy: PlaybackStrategy,
        val streamUrl: String,
        val telemetry: ConversionTelemetry? = null
    ) : CastPipelineState

    data class RetryingFallback(
        val video: VideoItem,
        val reason: String
    ) : CastPipelineState

    data class Ready(val video: VideoItem, val deviceName: String) : CastPipelineState
    data class Failed(val video: VideoItem?, val error: CastError) : CastPipelineState
}

enum class NetworkTransportMode(val label: String, val shortBadge: String) {
    CONVENTIONAL_WIFI("Conventional Wi-Fi", "WI-FI"),
    MOBILE_HOTSPOT("Mobile Hotspot", "HOTSPOT"),
    BOTH_WIFI_AND_HOTSPOT("Wi-Fi + Mobile Hotspot", "DUAL WI-FI"),
    OFFLINE("Offline / Cellular Only", "OFFLINE")
}

enum class MulticastHealthStatus(val label: String) {
    HEALTHY("Multicast Active"),
    RESTRICTED_OR_BLOCKED("Multicast Restricted / Blocked"),
    UNVERIFIED("Not Yet Verified"),
    INACTIVE("Idle (Released)")
}

enum class HotspotClientStatus(val label: String) {
    CLIENT_OBSERVED("Hotspot Client / TV Detected"),
    NO_CLIENT_OBSERVED("Waiting for TV to Join Hotspot"),
    UNKNOWN_RESTRICTED_BY_OS("Managed by Android OS")
}

@Immutable
data class NetworkInterfaceDescriptor(
    val name: String,
    val ipv4Address: String,
    val supportsMulticast: Boolean,
    val isHotspotLikely: Boolean,
    val isWifiStaLikely: Boolean
)

@Immutable
data class NetworkModeSnapshot(
    val mode: NetworkTransportMode = NetworkTransportMode.OFFLINE,
    val isWifiEnabled: Boolean = false,
    val isConventionalWifiConnected: Boolean = false,
    val isHotspotActive: Boolean = false,
    val isMobileDataEnabled: Boolean = false,
    val activeNetworkLabel: String? = null,
    val wifiIpv4Address: String? = null,
    val wifiInterfaceName: String? = null,
    val hotspotIpv4Address: String? = null,
    val hotspotInterfaceName: String? = null,
    val preferredBindIpv4: String? = null,
    val preferredBindInterface: String? = null,
    val localCommunicationAllowed: Boolean = false,
    val isVpnActive: Boolean = false,
    val multicastLockHeld: Boolean = false,
    val multicastStatus: MulticastHealthStatus = MulticastHealthStatus.INACTIVE,
    val multicastDiagnosticDetail: String = "MulticastLock is acquired only during active Chromecast discovery.",
    val hotspotClientStatus: HotspotClientStatus = HotspotClientStatus.UNKNOWN_RESTRICTED_BY_OS,
    val observableHotspotClientCount: Int? = null,
    val availableLocalInterfaces: List<NetworkInterfaceDescriptor> = emptyList(),
    val statusHeadline: String = "Connect to Wi-Fi or enable Mobile Hotspot to cast.",
    val troubleshootingHint: String? = null
) {
    val isLocalCastNetworkReady: Boolean
        get() = localCommunicationAllowed && preferredBindIpv4 != null
}

@Immutable
data class NetworkDiagnosticReport(
    val timestampMs: Long = 0L,
    val modeSnapshot: NetworkModeSnapshot = NetworkModeSnapshot(),
    val localNetworkReachable: Boolean = false,
    val httpServerReachable: Boolean = false,
    val httpServerLatencyMs: Long? = null,
    val httpServerEndpoint: String? = null,
    val multicastSocketJoinable: Boolean = false,
    val multicastProbeDetail: String = "",
    val castSdkAvailable: Boolean = false,
    val castDiscoveryActive: Boolean = false,
    val discoveredCastCount: Int = 0,
    val connectedDeviceName: String? = null,
    val oemCompatibilityNote: String = "",
    val headlineMessage: String = "",
    val actionableRecommendations: List<String> = emptyList()
)

@Immutable
data class NetworkHealthState(
    val isWifiConnected: Boolean = false,
    val localIpv4Address: String? = null,
    val interfaceName: String? = null,
    val networkMode: NetworkTransportMode = NetworkTransportMode.OFFLINE,
    val isWifiEnabled: Boolean = false,
    val isConventionalWifiConnected: Boolean = false,
    val isHotspotActive: Boolean = false,
    val isMobileDataEnabled: Boolean = false,
    val activeNetworkLabel: String? = null,
    val wifiIpv4Address: String? = null,
    val wifiInterfaceName: String? = null,
    val hotspotIpv4Address: String? = null,
    val hotspotInterfaceName: String? = null,
    val isVpnActive: Boolean = false,
    val multicastLockHeld: Boolean = false,
    val multicastStatus: MulticastHealthStatus = MulticastHealthStatus.INACTIVE,
    val multicastDetail: String = "",
    val hotspotClientStatus: HotspotClientStatus = HotspotClientStatus.UNKNOWN_RESTRICTED_BY_OS,
    val observableHotspotClientCount: Int? = null,
    val availableInterfaces: List<NetworkInterfaceDescriptor> = emptyList(),
    val diagnosticHeadline: String = "Connect to Wi-Fi or enable Mobile Hotspot.",
    val troubleshootingHint: String? = null,
    val serverRunning: Boolean = false,
    val serverPort: Int? = null,
    val activeStreamsCount: Int = 0,
    val lastHealthCheckPassed: Boolean? = null,
    val lastHealthCheckLatencyMs: Long? = null,
    val lastDiagnosticReport: NetworkDiagnosticReport? = null
)
