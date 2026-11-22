package com.example.core.update

import org.json.JSONObject

sealed interface ManifestParseResult {
    data class Valid(val manifest: UpdateManifest) : ManifestParseResult
    data class Invalid(val reason: String) : ManifestParseResult
}

/**
 * Strictly parses and validates `update-manifest.json` published alongside each GitHub Release.
 */
object UpdateManifestParser {

    private val SHA256_REGEX = Regex("""^[a-fA-F0-9]{64}$""")

    fun parse(jsonText: String): ManifestParseResult {
        if (jsonText.isBlank()) {
            return ManifestParseResult.Invalid("Empty update manifest.")
        }

        return runCatching {
            val obj = JSONObject(jsonText)
            val versionName = obj.optString("versionName").trim()
            val versionCode = obj.optInt("versionCode", -1)
            val releaseTag = obj.optString("releaseTag").trim()
            val releaseName = obj.optString("releaseName").trim()
            val releaseNotes = obj.optString("releaseNotes").trim()
            val minimumSupportedVersion = obj.optInt("minimumSupportedVersion", 1).coerceAtLeast(1)
            val apkUrl = obj.optString("apkUrl").trim()
            val sha256 = obj.optString("sha256").trim().lowercase()
            val fileSizeBytes = obj.optLong("fileSizeBytes", 0L).coerceAtLeast(0L)
            val mandatoryUpdate = obj.optBoolean("mandatoryUpdate", false)

            if (versionName.isBlank()) {
                return ManifestParseResult.Invalid("Manifest missing versionName.")
            }
            if (versionCode <= 0) {
                return ManifestParseResult.Invalid("Manifest has invalid versionCode ($versionCode).")
            }
            if (!apkUrl.startsWith("https://", ignoreCase = true)) {
                return ManifestParseResult.Invalid("Manifest apkUrl must use HTTPS.")
            }
            if (sha256.isNotEmpty() && !SHA256_REGEX.matches(sha256)) {
                return ManifestParseResult.Invalid("Manifest contains malformed SHA-256 digest.")
            }

            ManifestParseResult.Valid(
                UpdateManifest(
                    versionName = versionName.removePrefix("v").removePrefix("V"),
                    versionCode = versionCode,
                    releaseTag = releaseTag.ifBlank { "v$versionName" },
                    releaseName = releaseName.ifBlank { "Version $versionName" },
                    releaseNotes = releaseNotes,
                    minimumSupportedVersion = minimumSupportedVersion,
                    apkUrl = apkUrl,
                    sha256 = sha256,
                    fileSizeBytes = fileSizeBytes,
                    mandatoryUpdate = mandatoryUpdate
                )
            )
        }.getOrElse { err ->
            ManifestParseResult.Invalid("Malformed update manifest JSON: ${err.message.orEmpty()}")
        }
    }
}
