package com.example.core.update

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val releaseTag: String,
    val releaseName: String,
    val releaseNotes: String,
    val minimumSupportedVersion: Int = 1,
    val apkUrl: String,
    val sha256: String,
    val fileSizeBytes: Long = 0L,
    val mandatoryUpdate: Boolean = false
)

@Immutable
data class GitHubApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String,
    val sha256AssetUrl: String? = null,
    val sha256Expected: String? = null
)

@Immutable
data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int? = null,
    val minimumSupportedVersion: Int = 1,
    val mandatoryUpdate: Boolean = false,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAtIso: String,
    val htmlUrl: String,
    val isPreRelease: Boolean,
    val apkAsset: GitHubApkAsset?
)

@Immutable
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState

    data class UpToDate(
        val checkedAtMs: Long,
        val currentVersion: String,
        val currentVersionCode: Int
    ) : AppUpdateState

    data class UpdateAvailable(
        val release: GitHubReleaseInfo,
        val currentVersion: String,
        val currentVersionCode: Int
    ) : AppUpdateState

    data class Downloading(
        val release: GitHubReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : AppUpdateState

    data class Verifying(
        val release: GitHubReleaseInfo
    ) : AppUpdateState

    data class NeedsInstallPermission(
        val release: GitHubReleaseInfo,
        val apkFile: File,
        val sha256Verified: Boolean
    ) : AppUpdateState

    data class ReadyToInstall(
        val release: GitHubReleaseInfo,
        val apkFile: File,
        val sha256Verified: Boolean,
        val lastInstallFeedback: String? = null
    ) : AppUpdateState

    data class Error(
        val message: String,
        val isNetworkOrRateLimit: Boolean = true
    ) : AppUpdateState
}
