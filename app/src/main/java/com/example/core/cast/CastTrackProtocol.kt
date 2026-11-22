package com.example.core.cast

import com.example.core.common.SubtitleAppearanceSettings
import org.json.JSONObject

sealed interface ReceiverTrackEvent {
    data class TrackStateSync(
        val supportsAudioTrackSwitching: Boolean,
        val activeAudioCastTrackId: Long?,
        val activeTextCastTrackIds: List<Long>,
        val subtitleDelayMs: Long
    ) : ReceiverTrackEvent

    data class AudioTrackConfirmed(
        val trackUniqueId: String?,
        val castTrackId: Long?,
        val language: String?,
        val name: String?
    ) : ReceiverTrackEvent

    data class SubtitleTrackConfirmed(
        val subtitleTrackId: String?,
        val castTrackId: Long?,
        val enabled: Boolean,
        val isBitmap: Boolean
    ) : ReceiverTrackEvent

    data class SubtitleDelayConfirmed(val delayMs: Long) : ReceiverTrackEvent
    data class SubtitleStyleConfirmed(val applied: Boolean) : ReceiverTrackEvent
    data class TrackError(val code: String, val message: String) : ReceiverTrackEvent
}

/**
 * Strongly typed JSON protocol codec for the bidirectional LumaCast Custom Web Receiver
 * namespace (`urn:x-cast:com.lumacast.tracks`).
 */
object CastTrackProtocol {
    const val NAMESPACE = "urn:x-cast:com.lumacast.tracks"

    fun buildRequestTrackState(): String = JSONObject().apply {
        put("type", "REQUEST_TRACK_STATE")
    }.toString()

    fun buildSelectAudioTrack(
        trackUniqueId: String,
        castTrackId: Long?,
        language: String?
    ): String = JSONObject().apply {
        put("type", "SELECT_AUDIO_TRACK")
        put("trackUniqueId", trackUniqueId)
        if (castTrackId != null) put("castTrackId", castTrackId)
        if (!language.isNullOrBlank()) put("language", language)
    }.toString()

    fun buildAddExternalSubtitle(
        subtitleTrackId: String,
        url: String,
        mimeType: String,
        language: String,
        name: String
    ): String = JSONObject().apply {
        put("type", "ADD_EXTERNAL_SUBTITLE")
        put("subtitleTrackId", subtitleTrackId)
        put("url", url)
        put("mimeType", mimeType)
        put("language", language)
        put("name", name)
    }.toString()

    fun buildSelectSubtitleTrack(
        subtitleTrackId: String?,
        castTrackId: Long?,
        disable: Boolean
    ): String = JSONObject().apply {
        put("type", "SELECT_SUBTITLE_TRACK")
        put("disable", disable)
        if (subtitleTrackId != null) put("subtitleTrackId", subtitleTrackId)
        if (castTrackId != null) put("castTrackId", castTrackId)
    }.toString()

    fun buildLoadBitmapSubtitles(
        subtitleTrackId: String,
        manifestUrl: String
    ): String = JSONObject().apply {
        put("type", "LOAD_BITMAP_SUBTITLES")
        put("subtitleTrackId", subtitleTrackId)
        put("manifestUrl", manifestUrl)
    }.toString()

    fun buildSetSubtitleDelay(delayMs: Long): String = JSONObject().apply {
        put("type", "SET_SUBTITLE_DELAY")
        put("delayMs", delayMs)
    }.toString()

    fun buildSetSubtitleStyle(style: SubtitleAppearanceSettings): String {
        val fgHex = argbToRgbaHex(style.foregroundColorArgb, alphaOverride = 1.0f)
        val bgHex = argbToRgbaHex(style.backgroundColorArgb, alphaOverride = style.backgroundOpacity)
        val edgeHex = argbToRgbaHex(style.edgeColorArgb, alphaOverride = 1.0f)
        val r = (style.backgroundColorArgb shr 16) and 0xFF
        val g = (style.backgroundColorArgb shr 8) and 0xFF
        val b = style.backgroundColorArgb and 0xFF
        val bgCss = "rgba($r, $g, $b, ${"%.2f".format(java.util.Locale.US, style.backgroundOpacity)})"

        return JSONObject().apply {
            put("type", "SET_SUBTITLE_STYLE")
            put("fontScale", style.fontScale.toDouble())
            put("foregroundColor", fgHex)
            put("backgroundColor", bgHex)
            put("backgroundCss", bgCss)
            put("edgeType", style.edgeType.name)
            put("edgeColor", edgeHex)
            put("fontGenericFamily", style.fontFamily.name)
            put("textAlign", style.textAlign.cssValue)
            put("bottomOffsetPercent", style.bottomOffsetPercent)
        }.toString()
    }

    fun parseReceiverEvent(rawJson: String): ReceiverTrackEvent? {
        return runCatching {
            val obj = JSONObject(rawJson)
            when (obj.optString("type")) {
                "TRACK_STATE_SYNC" -> {
                    val activeTextIds = mutableListOf<Long>()
                    val arr = obj.optJSONArray("activeTextTrackIds")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            activeTextIds += arr.optLong(i)
                        }
                    }
                    ReceiverTrackEvent.TrackStateSync(
                        supportsAudioTrackSwitching = obj.optBoolean("supportsAudioTrackSwitching", false),
                        activeAudioCastTrackId = if (obj.isNull("activeAudioTrackId")) null else obj.optLong("activeAudioTrackId"),
                        activeTextCastTrackIds = activeTextIds,
                        subtitleDelayMs = obj.optLong("subtitleDelayMs", 0L)
                    )
                }

                "AUDIO_TRACK_CONFIRMED" -> ReceiverTrackEvent.AudioTrackConfirmed(
                    trackUniqueId = obj.optString("trackUniqueId").takeIf { it.isNotBlank() && it != "null" },
                    castTrackId = if (obj.isNull("castTrackId")) null else obj.optLong("castTrackId"),
                    language = obj.optString("language").takeIf { it.isNotBlank() },
                    name = obj.optString("name").takeIf { it.isNotBlank() }
                )

                "SUBTITLE_TRACK_CONFIRMED" -> ReceiverTrackEvent.SubtitleTrackConfirmed(
                    subtitleTrackId = obj.optString("subtitleTrackId").takeIf { it.isNotBlank() && it != "null" },
                    castTrackId = if (obj.isNull("castTrackId")) null else obj.optLong("castTrackId"),
                    enabled = obj.optBoolean("enabled", false),
                    isBitmap = obj.optBoolean("isBitmap", false)
                )

                "SUBTITLE_DELAY_CONFIRMED" -> ReceiverTrackEvent.SubtitleDelayConfirmed(
                    delayMs = obj.optLong("delayMs", 0L)
                )

                "SUBTITLE_STYLE_CONFIRMED" -> ReceiverTrackEvent.SubtitleStyleConfirmed(
                    applied = obj.optBoolean("applied", true)
                )

                "TRACK_ERROR" -> ReceiverTrackEvent.TrackError(
                    code = obj.optString("code", "UNKNOWN"),
                    message = obj.optString("message", "Receiver track operation failed.")
                )

                else -> null
            }
        }.getOrNull()
    }

    private fun argbToRgbaHex(argb: Long, alphaOverride: Float): String {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val a = (alphaOverride.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return String.format(java.util.Locale.US, "#%02X%02X%02X%02X", r, g, b, a)
    }
}
