package com.example.di

import android.content.Context
import com.example.core.cast.CastSessionController
import com.example.core.cast.CastStreamingService
import com.example.core.common.AppPreferencesRepository
import com.example.core.media.AdaptiveHlsManifestGenerator
import com.example.core.media.FfmpegTranscodeBackend
import com.example.core.media.LocalVideoRepository
import com.example.core.media.MediaCompatibilityManager
import com.example.core.media.MediaInspector
import com.example.core.media.MediaPreparationEngine
import com.example.core.media.MediaResourceGuard
import com.example.core.media.SubtitleCompatibilityManager
import com.example.core.media.SubtitleManager
import com.example.core.media.VideoThumbnailLoader
import com.example.BuildConfig
import com.example.core.network.HotspotNetworkDiagnostics
import com.example.core.network.LocalMediaHttpServer
import com.example.core.network.NetworkModeManager
import com.example.core.update.ApkInstallationManager
import com.example.core.update.ApkVerificationManager
import com.example.core.update.GitHubReleaseRepository
import com.example.core.update.NetworkConnectivityChecker
import com.example.core.update.UpdateDownloadManager
import com.example.core.update.UpdateManager

/**
 * Clean constructor-injected application dependency graph.
 */
class LumaCastContainer(appContext: Context) {

    val preferencesRepository = AppPreferencesRepository(appContext)
    val videoRepository = LocalVideoRepository(appContext)
    val thumbnailLoader = VideoThumbnailLoader(appContext)
    val mediaInspector = MediaInspector(appContext)
    val compatibilityManager = MediaCompatibilityManager()
    val ffmpegBackend = FfmpegTranscodeBackend(appContext)
    val resourceGuard = MediaResourceGuard(appContext)
    val preparationEngine = MediaPreparationEngine(appContext, ffmpegBackend)
    val subtitleCompatibilityManager = SubtitleCompatibilityManager()
    val subtitleManager = SubtitleManager(appContext, subtitleCompatibilityManager, ffmpegBackend)
    val hlsManifestGenerator = AdaptiveHlsManifestGenerator()
    val networkModeManager = NetworkModeManager(appContext)
    val httpServer = LocalMediaHttpServer()
    val networkDiagnostics = HotspotNetworkDiagnostics(appContext, networkModeManager, httpServer)
    val gitHubReleaseRepository = GitHubReleaseRepository(currentVersionProvider = { BuildConfig.VERSION_NAME })
    val updateDownloadManager = UpdateDownloadManager(appContext)
    val apkVerificationManager = ApkVerificationManager(appContext)
    val apkInstallationManager = ApkInstallationManager(appContext)
    val networkConnectivityChecker = NetworkConnectivityChecker(appContext)

    val castController: CastSessionController = CastSessionController(
        context = appContext,
        onSessionTerminated = {
            httpServer.stop()
            preparationEngine.releaseAllInUseFiles()
            subtitleManager.releaseAllInUse()
            networkModeManager.releaseMulticastLock()
            CastStreamingService.stop(appContext)
        },
        onDiscoveryActiveChanged = { active ->
            if (active) {
                networkModeManager.acquireMulticastLock()
            } else {
                networkModeManager.releaseMulticastLock()
            }
        },
        onDiscoveredDeviceCountChanged = { count ->
            networkModeManager.updateDiscoveredCastCount(count)
        }
    )

    val updateManager: UpdateManager = UpdateManager(
        releaseRepository = gitHubReleaseRepository,
        downloadManager = updateDownloadManager,
        verificationManager = apkVerificationManager,
        installationManager = apkInstallationManager,
        connectivityChecker = networkConnectivityChecker,
        preferencesRepository = preferencesRepository,
        isCastingActive = { castController.playbackState.value.isCastingActive }
    )

    init {
        networkModeManager.startMonitoring()
        castController.initialize()
    }
}
