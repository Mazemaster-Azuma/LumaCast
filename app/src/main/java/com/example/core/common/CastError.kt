package com.example.core.common

/**
 * Exhaustive domain error hierarchy covering local media, LAN HTTP streaming,
 * codec compatibility, resource constraints, and Google Cast session failures
 * with actionable recovery guidance.
 */
sealed class CastError(
    val code: String,
    val title: String,
    val message: String,
    val recoverySteps: List<String>
) {
    data object NoCastDeviceFound : CastError(
        code = "CAST_NO_DEVICES",
        title = "No Chromecast Device Found",
        message = "No Google Cast-enabled TVs or dongles were discovered on your current Wi-Fi network.",
        recoverySteps = listOf(
            "Verify your phone and Smart TV are connected to the same Wi-Fi SSID (avoid Guest Wi-Fi).",
            "Confirm the TV is powered on and Chromecast built-in is enabled in TV settings.",
            "Disable AP/Client Isolation in your Wi-Fi router settings if devices cannot discover each other."
        )
    )

    data object CastSdkUnavailable : CastError(
        code = "CAST_SDK_UNAVAILABLE",
        title = "Google Play Services Required",
        message = "Google Cast discovery requires Google Play Services on this Android device.",
        recoverySteps = listOf(
            "Update Google Play Services from the Google Play Store.",
            "Restart LumaCast after Play Services finishes updating."
        )
    )

    data object WifiNotConnected : CastError(
        code = "NET_LOCAL_NETWORK_OFF",
        title = "Wi-Fi or Mobile Hotspot Required",
        message = "Local video casting requires either a shared Wi-Fi network or an active Android Mobile Hotspot with your TV connected to it.",
        recoverySteps = listOf(
            "Connect your phone and Chromecast TV to the same Wi-Fi network, OR enable your phone's Mobile Hotspot and connect the TV to it.",
            "Disable VPNs that block local RFC1918 LAN routing."
        )
    )

    data class HotspotMulticastBlocked(val detail: String) : CastError(
        code = "NET_HOTSPOT_MULTICAST_BLOCKED",
        title = "Hotspot Blocking Chromecast Discovery",
        message = detail.ifBlank {
            "TV connected, but Chromecast discovery is unavailable. Your hotspot may be blocking multicast traffic."
        },
        recoverySteps = listOf(
            "Disable 'AP Isolation' or 'Restrict hotspot clients' in Android Hotspot settings if your manufacturer provides it.",
            "Verify your Chromecast TV is connected to this phone's hotspot and has internet access (if required by Cast receiver firmware).",
            "Some Android OEM hotspot firmwares block mDNS forwarding; connect both phone and TV to a conventional Wi-Fi router or travel router as a workaround."
        )
    )

    data object HotspotDisconnected : CastError(
        code = "NET_HOTSPOT_DISCONNECTED",
        title = "Mobile Hotspot Disconnected",
        message = "The phone's Mobile Hotspot was turned off or lost its local interface during casting. Your playback position has been saved.",
        recoverySteps = listOf(
            "Re-enable Mobile Hotspot in Android Settings and wait for the TV to reconnect.",
            "Tap Retry once the TV reconnects to resume playback from your saved position."
        )
    )

    data class HotspotLocalStreamingBlocked(val endpoint: String) : CastError(
        code = "NET_HOTSPOT_STREAM_BLOCKED",
        title = "Local Media Streaming Blocked on Hotspot",
        message = "TV connected, but local media streaming is blocked ($endpoint unreachable from the hotspot client).",
        recoverySteps = listOf(
            "Disable any active VPN or private firewall app on your phone that blocks local hotspot traffic.",
            "Verify your phone's Mobile Hotspot does not isolate connected clients from the host gateway.",
            "Run Network Diagnostics to verify local HTTP server reachability."
        )
    )

    data class ClientIsolationSuspected(val streamHost: String) : CastError(
        code = "NET_TV_UNREACHABLE",
        title = "TV Cannot Reach Local Stream",
        message = "Your TV accepted the Cast command but could not fetch the video stream from $streamHost.",
        recoverySteps = listOf(
            "Disable Wi-Fi Client/AP Isolation on your router (common on hotel or guest networks).",
            "Ensure any active VPN on your phone allows LAN connections.",
            "Run the HTTP Server Self-Test in Diagnostics & Help."
        )
    )

    data class CastSessionLaunchFailed(val detail: String) : CastError(
        code = "CAST_LAUNCH_FAILED",
        title = "Cast Session Failed to Launch",
        message = detail.ifBlank { "Unable to start a Chromecast receiver session on the selected TV." },
        recoverySteps = listOf(
            "Restart the TV or reboot the Chromecast dongle.",
            "Disconnect and reconnect from the Devices tab."
        )
    )

    data class UnsupportedContainer(val container: String) : CastError(
        code = "MEDIA_UNSUPPORTED_CONTAINER",
        title = "Unsupported Container ($container)",
        message = "The Chromecast Default Media Receiver does not natively play $container files directly.",
        recoverySteps = listOf(
            "Enable 'Auto-Remux Incompatible Containers' in Settings to repackage without quality loss.",
            "Or convert the file to MP4/WebM."
        )
    )

    data class UnsupportedVideoCodec(val codec: String) : CastError(
        code = "MEDIA_UNSUPPORTED_VIDEO",
        title = "Unsupported Video Codec ($codec)",
        message = "The selected receiver profile does not support $codec video streams.",
        recoverySteps = listOf(
            "If casting to a 4K Chromecast / Google TV, switch Receiver Profile to 4K HDR in Settings.",
            "Enable Hardware Transcode Fallback so video is converted to H.264 while preserving compatible audio."
        )
    )

    data class UnsupportedAudioCodec(val codec: String) : CastError(
        code = "MEDIA_UNSUPPORTED_AUDIO",
        title = "Unsupported Audio Codec ($codec)",
        message = "Chromecast Default Media Receiver cannot decode $codec audio tracks directly.",
        recoverySteps = listOf(
            "Enable Automatic Audio/Video Transcoding in Settings so only the audio track is converted to AAC-LC while keeping original video quality.",
            "If this file has multiple audio tracks, select a compatible AAC or AC-3 language track in Video Details."
        )
    )

    data class DecoderUnavailable(val codec: String) : CastError(
        code = "CODEC_DECODER_MISSING",
        title = "No Device Decoder for $codec",
        message = "Android MediaCodec on this phone cannot decode $codec and no custom FFmpeg native binary (libffmpeg.so) is bundled in this build.",
        recoverySteps = listOf(
            "Select an alternate AAC/AC-3 audio track on the Video Details screen if available.",
            "Or bundle an LGPL-compliant Android FFmpeg build (see README.md FFmpeg integration guide) to decode $codec tracks on-device."
        )
    )

    data class UnsupportedHdr(val hdr: String) : CastError(
        code = "MEDIA_UNSUPPORTED_HDR",
        title = "Unsupported HDR Format ($hdr)",
        message = "This video uses $hdr, which is not supported by a Standard 1080p Chromecast profile.",
        recoverySteps = listOf(
            "Switch Receiver Profile to Chromecast 4K / Google TV if your TV supports HDR10/Dolby Vision.",
            "Or enable Hardware Transcoding to tone-map HDR to SDR cleanly."
        )
    )

    data class InsufficientDiskSpace(val requiredLabel: String, val availableLabel: String) : CastError(
        code = "STORAGE_LOW_DISK",
        title = "Insufficient Temporary Storage",
        message = "Preparing this media stream needs roughly $requiredLabel of free disk space, but only $availableLabel is available.",
        recoverySteps = listOf(
            "Tap Clear Prepared Media Cache in Settings to reclaim temporary conversion space.",
            "Free additional internal storage on your device and retry."
        )
    )

    data class ThermalThrottling(val thermalState: String) : CastError(
        code = "THERMAL_LIMIT",
        title = "Device Too Warm for Transcoding",
        message = "Android reported $thermalState thermal state. Heavy video conversion was paused to protect battery and prevent thermal shutdown.",
        recoverySteps = listOf(
            "Let your phone cool for a few minutes before retrying.",
            "Choose a lower Max Transcode Resolution in Settings or play an MP4/H.264 file for zero-CPU Direct Cast."
        )
    )

    data class OutputValidationFailed(val reason: String) : CastError(
        code = "MEDIA_VALIDATION_FAILED",
        title = "Converted Stream Validation Failed",
        message = reason.ifBlank { "The converted MP4 output failed track or A/V synchronization validation." },
        recoverySteps = listOf(
            "Retry casting to run the Conservative Compatibility Fallback profile.",
            "Verify the original video file is not corrupted."
        )
    )

    data object StorageAccessRevoked : CastError(
        code = "STORAGE_ACCESS_REVOKED",
        title = "Folder Access Permission Missing",
        message = "Android revoked access to this video or folder, or permission was declined.",
        recoverySteps = listOf(
            "Tap 'Add Folder' or 'Add Videos' to re-select the media using Android's system picker."
        )
    )

    data class FileMissingOrMoved(val fileName: String) : CastError(
        code = "FILE_NOT_FOUND",
        title = "Video File Not Found",
        message = "\"$fileName\" was moved, renamed, or deleted from device storage.",
        recoverySteps = listOf(
            "Refresh your library to remove missing entries.",
            "Re-add the folder if the file was moved to a new directory."
        )
    )

    data class HttpServerFailure(val reason: String) : CastError(
        code = "HTTP_SERVER_ERROR",
        title = "Local Streaming Server Error",
        message = reason,
        recoverySteps = listOf(
            "Change the HTTP server port to 'Auto' in Settings.",
            "Verify Wi-Fi is active and retry casting."
        )
    )

    data class SeekingFailed(val detail: String) : CastError(
        code = "CAST_SEEK_FAILED",
        title = "Seeking Interrupted",
        message = detail.ifBlank { "The Chromecast receiver could not jump to the requested timestamp." },
        recoverySteps = listOf(
            "Wait a moment for buffering to settle, then seek again.",
            "Ensure your phone did not enter aggressive battery restrictions while streaming."
        )
    )

    data class TranscodingFailed(val detail: String) : CastError(
        code = "TRANSCODE_FAILED",
        title = "Media Conversion Failed",
        message = detail.ifBlank { "This device's encoder could not convert the selected video or audio track." },
        recoverySteps = listOf(
            "Free up internal cache storage and retry.",
            "Lower the Max Transcode Resolution in Settings or select an alternate compatible audio track."
        )
    )

    data object NetworkInterrupted : CastError(
        code = "NET_INTERRUPTED",
        title = "Wi-Fi Connection Interrupted",
        message = "Your phone lost Wi-Fi connectivity or changed IP address during streaming.",
        recoverySteps = listOf(
            "Reconnect to your home Wi-Fi network.",
            "Tap Retry to restart the local HTTP server on the new IP and resume playback."
        )
    )

    data class AudioTrackDiscoveryFailed(val detail: String) : CastError(
        code = "AUDIO_DISCOVERY_FAILED",
        title = "Audio Track Discovery Failed",
        message = detail.ifBlank { "Unable to enumerate all embedded audio streams from this container." },
        recoverySteps = listOf(
            "Verify the video file is intact and re-inspect in Video Details.",
            "Try remuxing the file to MP4 or select the primary audio stream."
        )
    )

    data class ReceiverAudioSwitchFailed(val detail: String) : CastError(
        code = "RECEIVER_AUDIO_SWITCH_FAILED",
        title = "Audio Track Switch Failed",
        message = detail.ifBlank { "The Chromecast receiver did not confirm the requested audio track switch." },
        recoverySteps = listOf(
            "Verify your registered Custom Web Receiver App ID is configured in Settings.",
            "Retry selecting the audio track — LumaCast will use position-preserving stream handoff if needed."
        )
    )

    data class SubtitleDiscoveryFailed(val detail: String) : CastError(
        code = "SUB_DISCOVERY_FAILED",
        title = "Subtitle Discovery Failed",
        message = detail.ifBlank { "Could not scan neighboring subtitle files in the video folder." },
        recoverySteps = listOf(
            "Grant folder access via Authorize Folder (SAF) in the Subtitle panel to auto-discover .srt/.ass/.vtt files.",
            "Or tap Load External Subtitle File to select a subtitle directly."
        )
    )

    data class UnsupportedSubtitleFormat(val formatLabel: String) : CastError(
        code = "SUB_UNSUPPORTED_FORMAT",
        title = "Unsupported Subtitle Format ($formatLabel)",
        message = "$formatLabel subtitles cannot be directly parsed as plain text by the receiver.",
        recoverySteps = listOf(
            "Select an alternate text-based subtitle track (SRT, ASS, WebVTT, or TTML).",
            "For PGS/VobSub bitmap subtitles, connect to the LumaCast Custom Web Receiver or load an external .srt/.vtt file."
        )
    )

    data class SubtitleOcrFailed(val detail: String) : CastError(
        code = "SUB_OCR_FAILED",
        title = "Bitmap Subtitle OCR Uncertain",
        message = detail.ifBlank { "Bitmap subtitle packets (PGS/VobSub) could not be converted to verified text." },
        recoverySteps = listOf(
            "Use Timed Image Subtitle Overlay on the Custom Web Receiver.",
            "Or load an external .srt / .vtt subtitle file for crisp Unicode rendering."
        )
    )

    data class SubtitleConversionFailed(val detail: String) : CastError(
        code = "SUB_CONVERSION_FAILED",
        title = "Subtitle Conversion Failed",
        message = detail.ifBlank { "Unable to convert the selected subtitle file into Chromecast-compatible WebVTT." },
        recoverySteps = listOf(
            "Ensure the subtitle file is UTF-8 encoded and contains valid cue timestamps.",
            "Try selecting a different subtitle track or reloading the file."
        )
    )

    data class SubtitleLoadFailed(val detail: String) : CastError(
        code = "SUB_LOAD_FAILED",
        title = "TV Could Not Load Subtitles",
        message = detail.ifBlank { "The Chromecast receiver failed to fetch the WebVTT subtitle stream over Wi-Fi." },
        recoverySteps = listOf(
            "Confirm the local HTTP server is running and CORS/LAN access is not blocked by router isolation.",
            "Disable and re-enable the subtitle track in the Subtitle panel."
        )
    )

    data class ExternalSubtitleMissing(val fileName: String) : CastError(
        code = "SUB_FILE_MISSING",
        title = "External Subtitle File Missing",
        message = "\"$fileName\" could no longer be read from device storage.",
        recoverySteps = listOf(
            "Tap Load External Subtitle File in the Subtitle panel to re-select it."
        )
    )

    data class SubtitleTimingInvalid(val detail: String) : CastError(
        code = "SUB_TIMING_INVALID",
        title = "Subtitle Timing Issue",
        message = detail.ifBlank { "Subtitle timestamps were invalid or shifted completely outside the video range." },
        recoverySteps = listOf(
            "Reset Subtitle Delay to 0 ms in the Subtitle panel.",
            "Verify the subtitle file matches this video's frame rate and cut."
        )
    )

    data class CustomReceiverError(val detail: String) : CastError(
        code = "CUSTOM_RECEIVER_ERROR",
        title = "Custom Web Receiver Error",
        message = detail.ifBlank { "The Custom Web Receiver reported a playback or track management error." },
        recoverySteps = listOf(
            "Verify your Custom Web Receiver URL is hosted over HTTPS and registered in the Cast Console.",
            "You can switch back to Default Media Receiver in Settings for baseline progressive playback."
        )
    )

    data class Generic(val detail: String) : CastError(
        code = "CAST_UNKNOWN",
        title = "Playback Error",
        message = detail.ifBlank { "An unexpected error occurred during casting." },
        recoverySteps = listOf(
            "Check Diagnostics & Help to inspect Wi-Fi, HTTP server, and Cast SDK health.",
            "Disconnect and start casting the video again."
        )
    )
}
