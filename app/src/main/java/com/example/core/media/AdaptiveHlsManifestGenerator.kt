package com.example.core.media

import com.example.core.common.AudioTrackInfo
import com.example.core.common.SubtitleTrackInfo

data class HlsAudioRenditionSpec(
    val track: AudioTrackInfo,
    val playlistRelativeUri: String,
    val isDefault: Boolean
)

data class HlsSubtitleRenditionSpec(
    val track: SubtitleTrackInfo,
    val playlistRelativeUri: String,
    val isDefault: Boolean
)

/**
 * Generates RFC 8216 compliant HLS Master Playlists (.m3u8) with distinct
 * #EXT-X-MEDIA TYPE=AUDIO renditions and #EXT-X-MEDIA TYPE=SUBTITLES renditions
 * so the Google Cast Custom Web Receiver's AudioTracksManager and TextTracksManager
 * can switch audio languages and subtitles in real time without restarting playback.
 */
class AdaptiveHlsManifestGenerator {

    fun buildMasterPlaylist(
        videoPlaylistRelativeUri: String,
        bandwidthBps: Long,
        width: Int,
        height: Int,
        videoCodecTag: String,
        audioRenditions: List<HlsAudioRenditionSpec>,
        subtitleRenditions: List<HlsSubtitleRenditionSpec> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:6\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n\n")

        val hasAudioGroup = audioRenditions.isNotEmpty()
        val hasSubGroup = subtitleRenditions.isNotEmpty()

        for (rendition in audioRenditions) {
            val t = rendition.track
            val lang = t.languageCode ?: "und"
            val name = escapeQuoted(t.title?.takeIf { it.isNotBlank() } ?: t.formattedSummaryLine)
            val channels = (t.channelCount ?: 2).coerceAtLeast(1)
            val def = if (rendition.isDefault) "YES" else "NO"
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"$name\",")
                .append("LANGUAGE=\"$lang\",DEFAULT=$def,AUTOSELECT=YES,")
                .append("CHANNELS=\"$channels\",URI=\"${rendition.playlistRelativeUri}\"\n")
        }

        if (hasSubGroup) {
            sb.append("\n")
            for (sub in subtitleRenditions) {
                val s = sub.track
                val lang = s.languageCode ?: "und"
                val name = escapeQuoted(s.title)
                val def = if (sub.isDefault) "YES" else "NO"
                val forced = if (s.isForced) "YES" else "NO"
                sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"$name\",")
                    .append("LANGUAGE=\"$lang\",DEFAULT=$def,AUTOSELECT=YES,FORCED=$forced,")
                    .append("URI=\"${sub.playlistRelativeUri}\"\n")
            }
        }

        sb.append("\n")
        val safeBandwidth = bandwidthBps.coerceAtLeast(1_500_000L)
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$safeBandwidth")
        if (width > 0 && height > 0) {
            sb.append(",RESOLUTION=${width}x${height}")
        }
        sb.append(",CODECS=\"$videoCodecTag,mp4a.40.2\"")
        if (hasAudioGroup) sb.append(",AUDIO=\"audio\"")
        if (hasSubGroup) sb.append(",SUBTITLES=\"subs\"")
        sb.append("\n")
        sb.append(videoPlaylistRelativeUri).append("\n")

        return sb.toString()
    }

    /**
     * Generates a single-segment WebVTT subtitle media playlist pointing to a converted `.vtt` file.
     */
    fun buildSubtitleMediaPlaylist(vttRelativeUri: String, durationSeconds: Long): String {
        val safeDur = durationSeconds.coerceAtLeast(1L)
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-TARGETDURATION:$safeDur\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXTINF:${safeDur}.000,\n")
            append("$vttRelativeUri\n")
            append("#EXT-X-ENDLIST\n")
        }
    }

    private fun escapeQuoted(value: String): String = value.replace("\"", "'")
}
