package com.example.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateCheckResult {
    data class Success(val latestRelease: GitHubReleaseInfo?) : UpdateCheckResult
    data class Failure(val reason: String, val isRateLimitOrNetwork: Boolean = true) : UpdateCheckResult
}

/**
 * Authoritative GitHub Releases repository client.
 * Queries `GET https://api.github.com/repos/OWNER/REPOSITORY/releases/latest`,
 * strictly ignores draft and prerelease entries, resolves `update-manifest.json`
 * when attached, and verifies `.apk` + `.sha256` assets over HTTPS.
 */
class GitHubReleaseRepository(
    private val currentVersionProvider: () -> String,
    private val httpGet: suspend (url: String) -> Pair<Int, String> = { url ->
        executeHttpGet(url, currentVersionProvider())
    }
) {

    suspend fun fetchLatestStableRelease(repoSlug: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val normalized = VersionComparator.normalizeGitHubRepoSlug(repoSlug)
                ?: return@withContext UpdateCheckResult.Failure(
                    reason = "Enter a valid GitHub repository (owner/repo) to check for updates.",
                    isRateLimitOrNetwork = false
                )

            val endpoint = "https://api.github.com/repos/$normalized/releases/latest"
            val (statusCode, body) = try {
                httpGet(endpoint)
            } catch (e: IOException) {
                return@withContext UpdateCheckResult.Failure(
                    reason = "Unable to reach GitHub: ${e.message ?: "check your internet connection"}",
                    isRateLimitOrNetwork = true
                )
            } catch (e: Exception) {
                return@withContext UpdateCheckResult.Failure(
                    reason = "Update check failed: ${e.message ?: "unexpected error"}",
                    isRateLimitOrNetwork = false
                )
            }

            when (statusCode) {
                404 -> {
                    // Repository has no published stable release yet (or only prereleases/drafts exist)
                    return@withContext UpdateCheckResult.Success(null)
                }
                403, 429 -> {
                    return@withContext UpdateCheckResult.Failure(
                        reason = "GitHub API rate limit reached. Try again later.",
                        isRateLimitOrNetwork = true
                    )
                }
                in 500..599 -> {
                    return@withContext UpdateCheckResult.Failure(
                        reason = "GitHub service is temporarily unavailable (HTTP $statusCode).",
                        isRateLimitOrNetwork = true
                    )
                }
                !in 200..299 -> {
                    return@withContext UpdateCheckResult.Failure(
                        reason = "GitHub returned HTTP $statusCode while checking updates.",
                        isRateLimitOrNetwork = true
                    )
                }
            }

            val parsedObj = runCatching { JSONObject(body) }.getOrElse { err ->
                return@withContext UpdateCheckResult.Failure(
                    reason = "Invalid GitHub release JSON: ${err.message.orEmpty()}",
                    isRateLimitOrNetwork = false
                )
            }

            // Strictly ignore drafts and prereleases even if returned unexpectedly
            val release = parseStableReleaseObject(parsedObj)
                ?: return@withContext UpdateCheckResult.Success(null)

            // If update-manifest.json is attached as an asset, fetch & merge authoritative versionCode + SHA-256
            val manifestUrl = extractManifestAssetUrl(parsedObj)
            val enrichedRelease = if (manifestUrl != null) {
                when (val manifestResult = fetchAndParseManifest(manifestUrl)) {
                    is ManifestParseResult.Valid -> {
                        val m = manifestResult.manifest
                        val updatedAsset = release.apkAsset?.copy(
                            downloadUrl = m.apkUrl,
                            sizeBytes = if (m.fileSizeBytes > 0L) m.fileSizeBytes else release.apkAsset.sizeBytes,
                            sha256Expected = m.sha256.takeIf { it.isNotBlank() } ?: release.apkAsset.sha256Expected
                        ) ?: GitHubApkAsset(
                            name = m.apkUrl.substringAfterLast('/'),
                            downloadUrl = m.apkUrl,
                            sizeBytes = m.fileSizeBytes,
                            contentType = "application/vnd.android.package-archive",
                            sha256AssetUrl = null,
                            sha256Expected = m.sha256.takeIf { it.isNotBlank() }
                        )
                        release.copy(
                            tagName = m.releaseTag.ifBlank { release.tagName },
                            versionName = m.versionName,
                            versionCode = m.versionCode,
                            minimumSupportedVersion = m.minimumSupportedVersion,
                            mandatoryUpdate = m.mandatoryUpdate,
                            releaseTitle = m.releaseName.ifBlank { release.releaseTitle },
                            releaseNotes = release.releaseNotes.ifBlank { m.releaseNotes },
                            apkAsset = updatedAsset
                        )
                    }
                    is ManifestParseResult.Invalid -> {
                        return@withContext UpdateCheckResult.Failure(
                            reason = manifestResult.reason,
                            isRateLimitOrNetwork = false
                        )
                    }
                }
            } else {
                release
            }

            // Reject release if it still lacks an HTTPS APK asset
            if (enrichedRelease.apkAsset == null) {
                return@withContext UpdateCheckResult.Failure(
                    reason = "Release ${enrichedRelease.tagName} has no APK download asset.",
                    isRateLimitOrNetwork = false
                )
            }

            UpdateCheckResult.Success(enrichedRelease)
        }

    suspend fun fetchExpectedSha256(release: GitHubReleaseInfo): String? {
        val asset = release.apkAsset ?: return null
        asset.sha256Expected?.takeIf { it.isNotBlank() }?.let { return it }
        val shaUrl = asset.sha256AssetUrl ?: return null
        return runCatching {
            val (code, text) = httpGet(shaUrl)
            if (code in 200..299) {
                SHA256_HEX_REGEX.find(text)?.value?.lowercase()
            } else null
        }.getOrNull()
    }

    private suspend fun fetchAndParseManifest(url: String): ManifestParseResult {
        if (!url.startsWith("https://", ignoreCase = true)) {
            return ManifestParseResult.Invalid("Manifest URL must use HTTPS.")
        }
        return try {
            val (code, text) = httpGet(url)
            if (code !in 200..299) {
                ManifestParseResult.Invalid("Failed to download update manifest (HTTP $code).")
            } else {
                UpdateManifestParser.parse(text)
            }
        } catch (e: Exception) {
            ManifestParseResult.Invalid("Could not load update manifest: ${e.message.orEmpty()}")
        }
    }

    internal fun extractManifestAssetUrl(obj: JSONObject): String? {
        val assetsArray = obj.optJSONArray("assets") ?: return null
        for (i in 0 until assetsArray.length()) {
            val assetObj = assetsArray.optJSONObject(i) ?: continue
            val name = assetObj.optString("name").trim()
            val url = assetObj.optString("browser_download_url").trim()
            if (name.equals(MANIFEST_ASSET_NAME, ignoreCase = true) && url.startsWith("https://")) {
                return url
            }
        }
        return null
    }

    internal fun parseStableReleaseObject(obj: JSONObject): GitHubReleaseInfo? {
        if (obj.optBoolean("draft", false)) return null
        if (obj.optBoolean("prerelease", false)) return null

        val tagName = obj.optString("tag_name").trim()
        if (tagName.isEmpty()) return null

        val versionName = tagName.removePrefix("v").removePrefix("V").ifBlank { tagName }
        val releaseTitle = obj.optString("name").trim().ifBlank { tagName }
        val releaseNotes = obj.optString("body").trim()
        val publishedAt = obj.optString("published_at").trim()
        val htmlUrl = obj.optString("html_url").trim()

        val parsedVersionCode = VERSION_CODE_NOTES_REGEX.find(releaseNotes)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
        val parsedAssets = buildList {
            for (i in 0 until assetsArray.length()) {
                val assetObj = assetsArray.optJSONObject(i) ?: continue
                val name = assetObj.optString("name").trim()
                val downloadUrl = assetObj.optString("browser_download_url").trim()
                val sizeBytes = assetObj.optLong("size", 0L)
                val contentType = assetObj.optString("content_type").trim()
                if (name.isNotEmpty() && downloadUrl.startsWith("https://")) {
                    add(GitHubApkAsset(name, downloadUrl, sizeBytes, contentType))
                }
            }
        }

        val apkAssets = parsedAssets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") && !lower.contains("androidtest") && !lower.contains("unaligned")
        }

        val selectedApk = apkAssets.maxByOrNull { asset ->
            val lower = asset.name.lowercase()
            var score = 0
            if (lower.contains("lumacast")) score += 20
            if (lower.contains("release")) score += 15
            if (!lower.contains("debug")) score += 5
            score
        }

        val companionShaAsset = selectedApk?.let { apk ->
            parsedAssets.firstOrNull { asset ->
                val lower = asset.name.lowercase()
                lower == "${apk.name.lowercase()}.sha256" ||
                    (lower.endsWith(".sha256") && lower.contains("lumacast")) ||
                    lower == "sha256sums.txt"
            }
        }

        val shaFromNotes = SHA256_NOTES_REGEX.find(releaseNotes)?.groupValues?.getOrNull(1)?.lowercase()

        val enrichedApk = selectedApk?.copy(
            sha256AssetUrl = companionShaAsset?.downloadUrl,
            sha256Expected = shaFromNotes
        )

        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            versionCode = parsedVersionCode,
            minimumSupportedVersion = 1,
            mandatoryUpdate = false,
            releaseTitle = releaseTitle,
            releaseNotes = releaseNotes.ifBlank { "Bug fixes and performance improvements." },
            publishedAtIso = publishedAt,
            htmlUrl = htmlUrl,
            isPreRelease = false,
            apkAsset = enrichedApk
        )
    }

    companion object {
        private const val MANIFEST_ASSET_NAME = "update-manifest.json"
        private val SHA256_HEX_REGEX = Regex("""\b[a-fA-F0-9]{64}\b""")
        private val SHA256_NOTES_REGEX = Regex("""(?i)sha-?256[^\da-f]*([a-f0-9]{64})""")
        private val VERSION_CODE_NOTES_REGEX = Regex("""(?i)versionCode\s*[:#]?\s*(\d+)""")

        private suspend fun executeHttpGet(urlString: String, appVersion: String): Pair<Int, String> =
            withContext(Dispatchers.IO) {
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "LumaCast-Android/$appVersion")
                }
                try {
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    code to text
                } finally {
                    conn.disconnect()
                }
            }
    }
}
