package com.example.core.update

import com.example.BuildConfig
import com.example.core.common.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped orchestrator for controlled GitHub Releases updating.
 *
 * Enforces:
 * - Strictly ONE automatic check per cold process launch (in-memory [AtomicBoolean] guard).
 * - Zero background polling, periodic workers, network-change listeners, or navigation checks.
 * - Zero automatic checks while offline or while Chromecast playback is active.
 * - Manual Settings checks always bypass the cold-start guard while rejecting simultaneous checks.
 * - Mandatory SHA-256 + package ID + versionCode + signing certificate validation prior to install.
 */
class UpdateManager(
    private val releaseRepository: GitHubReleaseRepository,
    private val downloadManager: UpdateDownloadManager,
    private val verificationManager: ApkVerificationManager,
    val installationManager: ApkInstallationManager,
    private val connectivityChecker: NetworkConnectivityChecker,
    private val preferencesRepository: AppPreferencesRepository,
    private val isCastingActive: () -> Boolean,
    private val installedVersionCodeProvider: () -> Int = { BuildConfig.VERSION_CODE },
    private val installedVersionNameProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val defaultRepoSlugProvider: () -> String = { BuildConfig.GITHUB_REPO },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    private val hasExecutedColdStartCheck = AtomicBoolean(false)
    private val checkMutex = Mutex()
    private var activeDownloadJob: Job? = null

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _installApkRequests = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val installApkRequests: SharedFlow<File> = _installApkRequests.asSharedFlow()

    /**
     * Invoked once when the Android Application starts (after a brief startup yield).
     * Subsequent calls in the same OS process (Activity recreation, Compose recomposition,
     * tab changes) are guaranteed no-ops via [hasExecutedColdStartCheck].
     */
    fun triggerStartupCheckIfEligible(startupDelayMs: Long = 1_500L) {
        if (!hasExecutedColdStartCheck.compareAndSet(false, true)) return

        scope.launch {
            if (startupDelayMs > 0L) delay(startupDelayMs)
            val currentSettings = preferencesRepository.settingsFlow.first()
            val repoSlug = currentSettings.githubUpdateRepo.ifBlank { defaultRepoSlugProvider() }
            if (!currentSettings.autoCheckUpdates || repoSlug.isBlank()) return@launch
            if (isCastingActive()) return@launch
            if (!connectivityChecker.isInternetAvailable()) return@launch

            performCheckInternal(
                repoSlug = repoSlug,
                userInitiated = false,
                skippedTag = currentSettings.skippedUpdateTag
            )
        }
    }

    /**
     * Manual update check triggered from Settings ("Check for Updates") or the drawer.
     * Bypasses the cold-launch session guard, verifies connectivity, and prevents duplicate concurrent requests.
     */
    fun checkManually() {
        scope.launch {
            if (!connectivityChecker.isInternetAvailable()) {
                _updateState.value = AppUpdateState.Error(
                    message = "No internet connection. Connect to the internet and try again.",
                    isNetworkOrRateLimit = true
                )
                return@launch
            }

            val currentSettings = preferencesRepository.settingsFlow.first()
            val repoSlug = currentSettings.githubUpdateRepo.ifBlank { defaultRepoSlugProvider() }
            if (repoSlug.isBlank()) {
                _updateState.value = AppUpdateState.Error(
                    message = "Configure your GitHub repository (owner/repo) under App Updates first.",
                    isNetworkOrRateLimit = false
                )
                return@launch
            }

            performCheckInternal(
                repoSlug = repoSlug,
                userInitiated = true,
                skippedTag = currentSettings.skippedUpdateTag
            )
        }
    }

    internal suspend fun performCheckInternal(
        repoSlug: String,
        userInitiated: Boolean,
        skippedTag: String
    ) {
        if (!checkMutex.tryLock()) return
        try {
            // Avoid disrupting active download or verified ready-to-install state
            val currentState = _updateState.value
            if (currentState is AppUpdateState.Downloading || currentState is AppUpdateState.Verifying) {
                return
            }

            _updateState.value = AppUpdateState.Checking

            when (val result = releaseRepository.fetchLatestStableRelease(repoSlug)) {
                is UpdateCheckResult.Failure -> {
                    if (userInitiated) {
                        _updateState.value = AppUpdateState.Error(
                            message = result.reason,
                            isNetworkOrRateLimit = result.isRateLimitOrNetwork
                        )
                    } else {
                        // Fail silently on startup automatic check so user experience is never interrupted
                        _updateState.value = AppUpdateState.Idle
                    }
                }

                is UpdateCheckResult.Success -> {
                    val checkedAt = nowMsProvider()
                    preferencesRepository.setLastUpdateCheckMs(checkedAt)

                    val release = result.latestRelease
                    val installedCode = installedVersionCodeProvider()
                    val installedName = installedVersionNameProvider()

                    val isNewer = release != null && VersionComparator.isUpdateAvailable(
                        installedVersionCode = installedCode,
                        installedVersionName = installedName,
                        release = release
                    )

                    if (isNewer && release != null) {
                        _updateState.value = AppUpdateState.UpdateAvailable(
                            release = release,
                            currentVersion = installedName,
                            currentVersionCode = installedCode
                        )
                        val isSkipped = skippedTag.equals(release.tagName, ignoreCase = true)
                        if (userInitiated || !isSkipped) {
                            _showUpdateDialog.value = true
                        }
                    } else {
                        _updateState.value = AppUpdateState.UpToDate(
                            checkedAtMs = checkedAt,
                            currentVersion = installedName,
                            currentVersionCode = installedCode
                        )
                        // Never interrupt user at startup if already up to date
                        if (!userInitiated) {
                            _showUpdateDialog.value = false
                        }
                    }
                }
            }
        } finally {
            checkMutex.unlock()
        }
    }

    fun startDownloadAndVerify(release: GitHubReleaseInfo) {
        val asset = release.apkAsset ?: run {
            _updateState.value = AppUpdateState.Error(
                message = "No APK download asset found for ${release.tagName}.",
                isNetworkOrRateLimit = false
            )
            return
        }

        if (!connectivityChecker.isInternetAvailable()) {
            _updateState.value = AppUpdateState.Error(
                message = "No internet connection. Connect to the internet and try again.",
                isNetworkOrRateLimit = true
            )
            return
        }

        if (activeDownloadJob?.isActive == true) return

        activeDownloadJob = scope.launch {
            _showUpdateDialog.value = true
            _updateState.value = AppUpdateState.Downloading(
                release = release,
                bytesDownloaded = 0L,
                totalBytes = asset.sizeBytes,
                progressPercent = 0
            )

            val expectedSha = releaseRepository.fetchExpectedSha256(release)
            val downloadResult = downloadManager.downloadApk(
                asset = asset,
                onProgress = { downloaded, total, percent ->
                    _updateState.value = AppUpdateState.Downloading(
                        release = release,
                        bytesDownloaded = downloaded,
                        totalBytes = total,
                        progressPercent = percent
                    )
                }
            )

            when (downloadResult) {
                is ApkDownloadResult.Failure -> {
                    _updateState.value = AppUpdateState.Error(
                        message = downloadResult.reason,
                        isNetworkOrRateLimit = true
                    )
                }

                is ApkDownloadResult.Success -> {
                    _updateState.value = AppUpdateState.Verifying(release)
                    val verifyResult = verificationManager.verifyDownloadedApk(
                        apkFile = downloadResult.apkFile,
                        installedVersionCode = installedVersionCodeProvider(),
                        expectedSha256 = expectedSha,
                        requireChecksumMatch = false
                    )

                    when (verifyResult) {
                        is ApkVerificationResult.Rejected -> {
                            downloadResult.apkFile.delete()
                            _updateState.value = AppUpdateState.Error(
                                message = verifyResult.reason,
                                isNetworkOrRateLimit = false
                            )
                        }

                        is ApkVerificationResult.Verified -> {
                            if (installationManager.canRequestPackageInstalls()) {
                                _updateState.value = AppUpdateState.ReadyToInstall(
                                    release = release,
                                    apkFile = downloadResult.apkFile,
                                    sha256Verified = verifyResult.sha256Verified
                                )
                                _installApkRequests.tryEmit(downloadResult.apkFile)
                            } else {
                                _updateState.value = AppUpdateState.NeedsInstallPermission(
                                    release = release,
                                    apkFile = downloadResult.apkFile,
                                    sha256Verified = verifyResult.sha256Verified
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        val current = _updateState.value
        if (current is AppUpdateState.Downloading || current is AppUpdateState.Verifying) {
            val release = when (current) {
                is AppUpdateState.Downloading -> current.release
                is AppUpdateState.Verifying -> current.release
                else -> return
            }
            _updateState.value = AppUpdateState.UpdateAvailable(
                release = release,
                currentVersion = installedVersionNameProvider(),
                currentVersionCode = installedVersionCodeProvider()
            )
        }
    }

    fun onResumeFromUnknownSourcesSettings() {
        val current = _updateState.value
        if (current is AppUpdateState.NeedsInstallPermission &&
            installationManager.canRequestPackageInstalls()
        ) {
            _updateState.value = AppUpdateState.ReadyToInstall(
                release = current.release,
                apkFile = current.apkFile,
                sha256Verified = current.sha256Verified
            )
            _installApkRequests.tryEmit(current.apkFile)
        }
    }

    fun requestInstallIfReady(): Boolean {
        val current = _updateState.value
        return when (current) {
            is AppUpdateState.ReadyToInstall -> {
                if (!current.apkFile.exists()) {
                    _updateState.value = AppUpdateState.Error("Update file is no longer cached. Download again.", false)
                    false
                } else if (!installationManager.canRequestPackageInstalls()) {
                    _updateState.value = AppUpdateState.NeedsInstallPermission(
                        release = current.release,
                        apkFile = current.apkFile,
                        sha256Verified = current.sha256Verified
                    )
                    false
                } else {
                    _installApkRequests.tryEmit(current.apkFile)
                    true
                }
            }
            is AppUpdateState.NeedsInstallPermission -> {
                if (installationManager.canRequestPackageInstalls()) {
                    _updateState.value = AppUpdateState.ReadyToInstall(
                        release = current.release,
                        apkFile = current.apkFile,
                        sha256Verified = current.sha256Verified
                    )
                    _installApkRequests.tryEmit(current.apkFile)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun onInstallerResult(resultCode: Int) {
        val msg = installationManager.formatInstallerResultMessage(resultCode)
        val current = _updateState.value
        if (msg == null) {
            // Succeeded
            downloadManager.clearUpdatesCache()
            _showUpdateDialog.value = false
        } else if (current is AppUpdateState.ReadyToInstall) {
            _updateState.value = current.copy(lastInstallFeedback = msg)
        }
    }

    fun openPrompt() {
        when (_updateState.value) {
            is AppUpdateState.UpdateAvailable,
            is AppUpdateState.Downloading,
            is AppUpdateState.Verifying,
            is AppUpdateState.NeedsInstallPermission,
            is AppUpdateState.ReadyToInstall -> _showUpdateDialog.value = true
            else -> {
                _showUpdateDialog.value = true
                checkManually()
            }
        }
    }

    fun dismissPrompt() {
        val current = _updateState.value
        if (current !is AppUpdateState.Downloading && current !is AppUpdateState.Verifying) {
            _showUpdateDialog.value = false
        }
    }

    fun skipVersion(tagName: String) {
        scope.launch {
            preferencesRepository.setSkippedUpdateTag(tagName)
            _showUpdateDialog.value = false
        }
    }
}
