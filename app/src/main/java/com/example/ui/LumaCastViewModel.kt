package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.cast.CastStreamingService
import com.example.core.common.AudioTrackInfo
import com.example.core.common.CastConnectionState
import com.example.core.common.CastDeviceInfo
import com.example.core.common.CastError
import com.example.core.common.CastPipelineState
import com.example.core.common.CompatibilityReport
import com.example.core.common.ConversionTelemetry
import com.example.core.common.Formatters
import com.example.core.common.IndexedFolder
import com.example.core.common.LibraryAccessState
import com.example.core.common.LiveTrackConversionState
import com.example.core.common.NetworkDiagnosticReport
import com.example.core.common.NetworkHealthState
import com.example.core.common.NetworkTransportMode
import com.example.core.common.PlaybackStrategy
import com.example.core.common.ProcessingBackendType
import com.example.core.common.ReceiverProfile
import com.example.core.common.RemotePlaybackState
import com.example.core.common.RemotePlayerStatus
import com.example.core.common.ResourceHealthStatus
import com.example.core.common.SortOption
import com.example.core.common.SubtitleAppearanceSettings
import com.example.core.common.SubtitleConversionStatus
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.TrackOrigin
import com.example.core.common.UserSettings
import com.example.core.common.VideoItem
import com.example.core.common.ViewMode
import com.example.core.media.MediaMutationResult
import com.example.core.media.PreparedMediaFile
import com.example.core.media.VideoThumbnailLoader
import com.example.core.network.ContentUriMediaSource
import com.example.core.network.FileMediaSource
import com.example.core.network.NetworkModeManager
import com.example.di.LumaCastContainer
import com.example.BuildConfig
import com.example.core.update.AppUpdateState
import com.example.core.update.GitHubReleaseInfo
import com.example.core.update.VersionComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PendingMediaConsentRequest(
    val intentSender: IntentSender,
    val video: VideoItem,
    val pendingRenameTarget: String? = null,
    val isDeleteAction: Boolean = false
)

@Immutable
data class CastHeaderStatus(
    val connectionState: CastConnectionState = CastConnectionState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val isCastingActive: Boolean = false,
    val discoveredDeviceCount: Int = 0
)

@Immutable
data class LibraryUiState(
    val isLoading: Boolean = true,
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val recentVideos: List<VideoItem> = emptyList(),
    val folders: List<IndexedFolder> = emptyList(),
    val availableFolderNames: List<String> = emptyList(),
    val accessState: LibraryAccessState = LibraryAccessState.ACCESSIBLE,
    val mediaStoreVideoCount: Int = 0,
    val searchQuery: String = "",
    val selectedFolderName: String? = null,
    val sortOption: SortOption = SortOption.DATE_DESC,
    val viewMode: ViewMode = ViewMode.LIST,
    val bannerMessage: String? = null
)

class LumaCastViewModel(
    private val appContext: Context,
    private val container: LumaCastContainer
) : ViewModel() {

    val thumbnailLoader: VideoThumbnailLoader
        get() = container.thumbnailLoader

    private val _rawVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    private val _folders = MutableStateFlow<List<IndexedFolder>>(emptyList())
    private val _libraryAccessState = MutableStateFlow(LibraryAccessState.ACCESSIBLE)
    private val _mediaStoreCount = MutableStateFlow(0)
    private val _isScanningLibrary = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _selectedFolder = MutableStateFlow<String?>(null)
    private val _bannerMessage = MutableStateFlow<String?>(null)
    private val _pendingConsentRequest = MutableStateFlow<PendingMediaConsentRequest?>(null)
    val pendingConsentRequest: StateFlow<PendingMediaConsentRequest?> = _pendingConsentRequest.asStateFlow()

    private val _compatibilityReports = MutableStateFlow<Map<String, CompatibilityReport>>(emptyMap())
    val compatibilityReports: StateFlow<Map<String, CompatibilityReport>> = _compatibilityReports.asStateFlow()

    private val _selectedAudioTrackByVideo = MutableStateFlow<Map<String, Int>>(emptyMap())
    val selectedAudioTrackByVideo: StateFlow<Map<String, Int>> = _selectedAudioTrackByVideo.asStateFlow()

    private val _subtitlesByVideo = MutableStateFlow<Map<String, List<SubtitleTrackInfo>>>(emptyMap())
    val subtitlesByVideo: StateFlow<Map<String, List<SubtitleTrackInfo>>> = _subtitlesByVideo.asStateFlow()

    private val _manualSubtitleUrisByVideo = MutableStateFlow<Map<String, List<Uri>>>(emptyMap())

    private val _inspectingVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val inspectingVideoIds: StateFlow<Set<String>> = _inspectingVideoIds.asStateFlow()

    private val _pipelineState = MutableStateFlow<CastPipelineState>(CastPipelineState.Idle)
    val pipelineState: StateFlow<CastPipelineState> = _pipelineState.asStateFlow()

    private val _liveTrackConversion = MutableStateFlow<LiveTrackConversionState?>(null)
    val liveTrackConversion: StateFlow<LiveTrackConversionState?> = _liveTrackConversion.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _resourceHealth = MutableStateFlow(
        ResourceHealthStatus(
            availableDiskBytes = 0L,
            thermalStatusLabel = "Nominal",
            isThermallyThrottled = false,
            ffmpegAvailable = false
        )
    )
    val resourceHealth: StateFlow<ResourceHealthStatus> = _resourceHealth.asStateFlow()

    private val _lastHealthCheck = MutableStateFlow<Pair<Boolean, Long>?>(null)
    private val _lastDiagnosticReport = MutableStateFlow<NetworkDiagnosticReport?>(null)
    private val _isRunningNetworkDiagnostics = MutableStateFlow(false)
    val isRunningNetworkDiagnostics: StateFlow<Boolean> = _isRunningNetworkDiagnostics.asStateFlow()

    val appUpdateState: StateFlow<AppUpdateState> = container.updateManager.updateState
    val showUpdateDialog: StateFlow<Boolean> = container.updateManager.showUpdateDialog
    val installApkRequests = container.updateManager.installApkRequests

    // Cache prepared audio track variants keyed by "videoId|trackIndex|profile" so identical tracks are never re-converted
    private val preparedVariantCache = ConcurrentHashMap<String, PreparedMediaFile>()

    private var activeCastJob: Job? = null
    private var activeLiveTrackJob: Job? = null
    private var lastKnownBindIp: String? = null
    private var lastKnownNetworkMode: NetworkTransportMode = NetworkTransportMode.OFFLINE
    private var pendingRecoveryVideo: VideoItem? = null
    private var pendingRecoveryPositionMs: Long = 0L
    private var pendingRecoveryAutoplay: Boolean = true
    private var previousCastConnectionState: CastConnectionState = CastConnectionState.DISCONNECTED

    val settings: StateFlow<UserSettings> = container.preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val isCastSdkAvailable: StateFlow<Boolean> = container.castController.isSdkAvailable
    val isScanningDevices: StateFlow<Boolean> = container.castController.isScanning
    val discoveryTimedOut: StateFlow<Boolean> = container.castController.discoveryTimedOut
    val discoveredDevices: StateFlow<List<CastDeviceInfo>> = container.castController.discoveredDevices
    val playbackState: StateFlow<RemotePlaybackState> = container.castController.playbackState

    val networkHealth: StateFlow<NetworkHealthState> = combine(
        container.networkModeManager.snapshot,
        _lastHealthCheck,
        _lastDiagnosticReport
    ) { snap, health, report ->
        NetworkHealthState(
            isWifiConnected = snap.isLocalCastNetworkReady,
            localIpv4Address = snap.preferredBindIpv4,
            interfaceName = snap.preferredBindInterface,
            networkMode = snap.mode,
            isWifiEnabled = snap.isWifiEnabled,
            isConventionalWifiConnected = snap.isConventionalWifiConnected,
            isHotspotActive = snap.isHotspotActive,
            isMobileDataEnabled = snap.isMobileDataEnabled,
            activeNetworkLabel = snap.activeNetworkLabel,
            wifiIpv4Address = snap.wifiIpv4Address,
            wifiInterfaceName = snap.wifiInterfaceName,
            hotspotIpv4Address = snap.hotspotIpv4Address,
            hotspotInterfaceName = snap.hotspotInterfaceName,
            isVpnActive = snap.isVpnActive,
            multicastLockHeld = snap.multicastLockHeld,
            multicastStatus = snap.multicastStatus,
            multicastDetail = snap.multicastDiagnosticDetail,
            hotspotClientStatus = snap.hotspotClientStatus,
            observableHotspotClientCount = snap.observableHotspotClientCount,
            availableInterfaces = snap.availableLocalInterfaces,
            diagnosticHeadline = report?.headlineMessage?.takeIf { it.isNotBlank() } ?: snap.statusHeadline,
            troubleshootingHint = snap.troubleshootingHint,
            serverRunning = container.httpServer.isRunning,
            serverPort = container.httpServer.listeningPort,
            activeStreamsCount = container.httpServer.activeStreamCount,
            lastHealthCheckPassed = health?.first ?: report?.httpServerReachable,
            lastHealthCheckLatencyMs = health?.second ?: report?.httpServerLatencyMs,
            lastDiagnosticReport = report
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NetworkHealthState())

    /**
     * Lightweight, distinct header/shell cast status that NEVER emits on 1-second
     * playback position ticks, keeping navigation chrome and browsing screens completely lag-free.
     */
    val castHeaderStatus: StateFlow<CastHeaderStatus> = combine(
        playbackState.map {
            Triple(it.connectionState, it.connectedDeviceName, it.isCastingActive)
        }.distinctUntilChanged(),
        discoveredDevices.map { it.size }.distinctUntilChanged()
    ) { (connState, devName, isActive), deviceCount ->
        CastHeaderStatus(
            connectionState = connState,
            connectedDeviceName = devName,
            isCastingActive = isActive,
            discoveredDeviceCount = deviceCount
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CastHeaderStatus())

    /**
     * Lightweight network status label for the navigation drawer header.
     */
    val drawerNetworkLabel: StateFlow<String> = container.networkModeManager.snapshot
        .map { snap -> snap.activeNetworkLabel ?: snap.mode.label }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NetworkTransportMode.OFFLINE.label)

    // Precompute recent videos & sorted folder names ONLY when raw video inventory changes (off main thread)
    private val derivedLibraryMeta = _rawVideos
        .map { allVideos ->
            val recent = allVideos
                .sortedByDescending { maxOf(it.dateAddedSec, it.dateModifiedSec) }
                .take(10)
            val folderNames = allVideos
                .map { it.folderName }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase() }
            recent to folderNames
        }
        .flowOn(Dispatchers.Default)

    // Compute filtered/sorted video list off main thread ONLY when inputs affecting filtering/sorting change
    private val filteredVideosFlow = combine(
        _rawVideos,
        _searchQuery,
        _selectedFolder,
        settings.map { it.sortOption }.distinctUntilChanged()
    ) { allVideos, query, folder, sortOption ->
        container.videoRepository.filterAndSort(
            videos = allVideos,
            query = query,
            sortOption = sortOption,
            folderFilter = folder
        )
    }.flowOn(Dispatchers.Default)

    val libraryState: StateFlow<LibraryUiState> = combine(
        _isScanningLibrary,
        _rawVideos,
        filteredVideosFlow,
        derivedLibraryMeta,
        _folders,
        _searchQuery,
        _selectedFolder,
        settings.map { it.sortOption to it.viewMode }.distinctUntilChanged(),
        _bannerMessage,
        _libraryAccessState,
        _mediaStoreCount
    ) { values ->
        val isLoading = values[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val allVideos = values[1] as List<VideoItem>
        @Suppress("UNCHECKED_CAST")
        val filtered = values[2] as List<VideoItem>
        @Suppress("UNCHECKED_CAST")
        val (recent, folderNames) = values[3] as Pair<List<VideoItem>, List<String>>
        @Suppress("UNCHECKED_CAST")
        val folders = values[4] as List<IndexedFolder>
        val query = values[5] as String
        val folder = values[6] as String?
        @Suppress("UNCHECKED_CAST")
        val (sortOption, viewMode) = values[7] as Pair<SortOption, ViewMode>
        val banner = values[8] as String?
        val accessState = values[9] as LibraryAccessState
        val msCount = values[10] as Int

        LibraryUiState(
            isLoading = isLoading,
            allVideos = allVideos,
            filteredVideos = filtered,
            recentVideos = recent,
            folders = folders,
            availableFolderNames = folderNames,
            accessState = accessState,
            mediaStoreVideoCount = msCount,
            searchQuery = query,
            selectedFolderName = folder,
            sortOption = sortOption,
            viewMode = viewMode,
            bannerMessage = banner
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    @OptIn(FlowPreview::class)
    private var lastObservedSafUris: Set<String>? = null
    private var lastObservedPickedUris: Set<String>? = null

    init {
        viewModelScope.launch {
            refreshResourceHealth()
        }
        viewModelScope.launch {
            settings.collect { current ->
                // Only rescan storage when authorized folder or picked video URI sets actually change
                if (lastObservedSafUris != current.safFolderUris ||
                    lastObservedPickedUris != current.pickedVideoUris
                ) {
                    lastObservedSafUris = current.safFolderUris
                    lastObservedPickedUris = current.pickedVideoUris
                    refreshLibrary(current.safFolderUris, current.pickedVideoUris)
                }
            }
        }
        // Debounced MediaStore ContentObserver: automatically refreshes when videos are added, renamed, or deleted
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            container.videoRepository.observeMediaStoreChanges()
                .debounce(650L)
                .collect {
                    refreshLibrary()
                }
        }
        // Automatic Network Transition & Hotspot Recovery Engine
        viewModelScope.launch {
            container.networkModeManager.snapshot.collect { snap ->
                val prevIp = lastKnownBindIp
                val prevMode = lastKnownNetworkMode
                val newIp = snap.preferredBindIpv4
                val newMode = snap.mode
                lastKnownBindIp = newIp
                lastKnownNetworkMode = newMode

                // Restart Google Cast discovery cleanly whenever Wi-Fi <-> Hotspot mode or IP changes
                if ((prevMode != newMode || (prevIp != null && prevIp != newIp)) && snap.isLocalCastNetworkReady) {
                    container.castController.restartDiscovery()
                }

                val currentPlayback = playbackState.value
                if (currentPlayback.isCastingActive) {
                    val activeVideo = currentPlayback.currentVideo
                    if (!snap.isLocalCastNetworkReady || newIp == null) {
                        // Preserve exact playback position for automatic recovery when hotspot/Wi-Fi returns
                        if (activeVideo != null) {
                            pendingRecoveryVideo = activeVideo
                            pendingRecoveryPositionMs = currentPlayback.positionMs
                            pendingRecoveryAutoplay = currentPlayback.playerStatus != RemotePlayerStatus.PAUSED
                        }
                        val droppedError = if (prevMode == NetworkTransportMode.MOBILE_HOTSPOT ||
                            prevMode == NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT
                        ) {
                            CastError.HotspotDisconnected
                        } else {
                            CastError.NetworkInterrupted
                        }
                        _pipelineState.value = CastPipelineState.Failed(
                            video = activeVideo,
                            error = droppedError
                        )
                    } else if (prevIp != null && prevIp != newIp && activeVideo != null) {
                        // Seamless IP / interface change recovery: rebind HTTP server & resume at saved timestamp!
                        runCatching {
                            container.httpServer.rebindIfNeeded(newIp, settings.value.preferredServerPort)
                        }
                        castVideo(
                            video = activeVideo,
                            startPositionMs = currentPlayback.positionMs,
                            autoplay = currentPlayback.playerStatus != RemotePlayerStatus.PAUSED
                        )
                    }
                }
            }
        }
        // Automatic TV Reconnection Recovery (e.g., TV disconnects & reconnects to Mobile Hotspot)
        viewModelScope.launch {
            playbackState.collect { state ->
                val prev = previousCastConnectionState
                previousCastConnectionState = state.connectionState
                if (prev != CastConnectionState.CONNECTED &&
                    state.connectionState == CastConnectionState.CONNECTED
                ) {
                    val recoveryVideo = pendingRecoveryVideo
                    val netReady = container.networkModeManager.refresh().isLocalCastNetworkReady
                    if (recoveryVideo != null && netReady && state.currentVideo == null) {
                        val resumeMs = pendingRecoveryPositionMs
                        val autoplay = pendingRecoveryAutoplay
                        pendingRecoveryVideo = null
                        castVideo(
                            video = recoveryVideo,
                            startPositionMs = resumeMs,
                            autoplay = autoplay
                        )
                    }
                } else if (prev == CastConnectionState.CONNECTED &&
                    state.connectionState == CastConnectionState.DISCONNECTED &&
                    state.currentVideo != null &&
                    state.positionMs > 0L
                ) {
                    // Preserve last position if TV dropped off hotspot unexpectedly
                    pendingRecoveryVideo = state.currentVideo
                    pendingRecoveryPositionMs = state.positionMs
                    pendingRecoveryAutoplay = state.playerStatus != RemotePlayerStatus.PAUSED
                }
            }
        }
    }

    fun refreshLibrary(
        safFolders: Set<String> = settings.value.safFolderUris,
        pickedVideos: Set<String> = settings.value.pickedVideoUris
    ) {
        viewModelScope.launch {
            _isScanningLibrary.value = true
            val result = container.videoRepository.scanLibrary(safFolders, pickedVideos)
            _rawVideos.value = result.videos
            _folders.value = result.folders
            _libraryAccessState.value = result.accessState
            _mediaStoreCount.value = result.mediaStoreVideoCount
            if (result.missingPickedVideoUris.isNotEmpty()) {
                result.missingPickedVideoUris.forEach { missingUri ->
                    container.preferencesRepository.removePickedVideoUri(missingUri)
                }
                _bannerMessage.value = "Removed ${result.missingPickedVideoUris.size} video(s) that were moved or deleted."
            } else if (result.revokedFolderUris.isNotEmpty()) {
                _bannerMessage.value = "Some previously added folders lost permission. Re-add them via Browse Folders."
            }
            _isScanningLibrary.value = false
        }
    }

    fun onSafFolderSelected(treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            container.preferencesRepository.addSafFolderUri(treeUri.toString())
            _bannerMessage.value = null
            // Re-scan external subtitles for active casting video if any
            playbackState.value.currentVideo?.let { activeVideo ->
                refreshSubtitlesForVideo(activeVideo)
            }
        }
    }

    fun removeSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            container.preferencesRepository.removeSafFolderUri(treeUri.toString())
        }
    }

    fun onPickedVideosSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            container.preferencesRepository.addPickedVideoUris(uris.map { it.toString() })
            _bannerMessage.value = null
        }
    }

    fun shareVideo(video: VideoItem) {
        viewModelScope.launch {
            if (!container.videoRepository.verifyUriAccessible(video.uri)) {
                _bannerMessage.value = "This video was moved or deleted. Refreshing library…"
                refreshLibrary()
                return@launch
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = video.mimeType.ifBlank { "video/*" }
                putExtra(Intent.EXTRA_STREAM, video.uri)
                putExtra(Intent.EXTRA_TITLE, video.displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${video.displayName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appContext.startActivity(chooser) }.onFailure {
                _bannerMessage.value = "No app available to share this video."
            }
        }
    }

    fun playVideoLocally(video: VideoItem) {
        viewModelScope.launch {
            if (!container.videoRepository.verifyUriAccessible(video.uri)) {
                _bannerMessage.value = "This video was moved or deleted. Refreshing library…"
                refreshLibrary()
                return@launch
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, video.mimeType.ifBlank { "video/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appContext.startActivity(viewIntent) }.onFailure {
                _bannerMessage.value = "No local video player app found to open ${video.displayName}."
            }
        }
    }

    fun openContainingFolder(video: VideoItem) {
        _selectedFolder.value = video.folderName
        val pathSummary = video.relativePath?.takeIf { it.isNotBlank() } ?: video.folderName
        _bannerMessage.value = "Showing folder: $pathSummary"
    }

    fun renameVideo(video: VideoItem, newBaseName: String) {
        viewModelScope.launch {
            when (val result = container.videoRepository.renameVideo(video, newBaseName)) {
                is MediaMutationResult.Success -> {
                    container.thumbnailLoader.invalidateVideo(video.id)
                    _bannerMessage.value = result.message
                    refreshLibrary()
                }
                is MediaMutationResult.NeedsUserConsent -> {
                    _pendingConsentRequest.value = PendingMediaConsentRequest(
                        intentSender = result.intentSender,
                        video = video,
                        pendingRenameTarget = newBaseName,
                        isDeleteAction = false
                    )
                }
                is MediaMutationResult.NotPermitted -> _bannerMessage.value = result.reason
                is MediaMutationResult.Failed -> _bannerMessage.value = result.reason
                is MediaMutationResult.RemovedReferenceOnly -> refreshLibrary()
            }
        }
    }

    fun deleteVideo(video: VideoItem) {
        viewModelScope.launch {
            when (val result = container.videoRepository.deleteVideo(video)) {
                is MediaMutationResult.Success -> {
                    container.thumbnailLoader.invalidateVideo(video.id)
                    _bannerMessage.value = result.message
                    refreshLibrary()
                }
                is MediaMutationResult.RemovedReferenceOnly -> {
                    container.thumbnailLoader.invalidateVideo(video.id)
                    container.preferencesRepository.removePickedVideoUri(result.uriString)
                    _bannerMessage.value = result.message
                    refreshLibrary()
                }
                is MediaMutationResult.NeedsUserConsent -> {
                    _pendingConsentRequest.value = PendingMediaConsentRequest(
                        intentSender = result.intentSender,
                        video = video,
                        isDeleteAction = true
                    )
                }
                is MediaMutationResult.NotPermitted -> _bannerMessage.value = result.reason
                is MediaMutationResult.Failed -> _bannerMessage.value = result.reason
            }
        }
    }

    fun onMediaConsentResult(granted: Boolean) {
        val pending = _pendingConsentRequest.value ?: return
        _pendingConsentRequest.value = null
        if (!granted) {
            _bannerMessage.value = "Action cancelled by user."
            return
        }
        viewModelScope.launch {
            if (pending.isDeleteAction) {
                // On Android 11+, MediaStore.createDeleteRequest deletes immediately once confirmed
                container.thumbnailLoader.invalidateVideo(pending.video.id)
                _bannerMessage.value = "Deleted ${pending.video.displayName}"
                refreshLibrary()
            } else if (pending.pendingRenameTarget != null) {
                // After MediaStore.createWriteRequest consent, retry the rename
                renameVideo(pending.video, pending.pendingRenameTarget)
            }
        }
    }

    /**
     * Adds a user-selected external subtitle file (.srt, .ass, .ssa, .vtt, .ttml, .sup, .idx)
     * via SAF OpenDocument and optionally loads it immediately onto the TV if casting is active.
     */
    fun onExternalSubtitlePicked(video: VideoItem, subtitleUri: Uri, activateImmediately: Boolean = true) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    subtitleUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            _manualSubtitleUrisByVideo.update { map ->
                val updated = (map[video.id].orEmpty() + subtitleUri).distinct()
                map + (video.id to updated)
            }
            val updatedList = refreshSubtitlesForVideo(video)
            if (activateImmediately && playbackState.value.currentVideo?.id == video.id) {
                val added = updatedList.firstOrNull { it.uri == subtitleUri }
                if (added != null) {
                    selectSubtitleDuringPlayback(added)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectFolderFilter(folderName: String?) {
        _selectedFolder.value = folderName
    }

    fun updateSortOption(option: SortOption) {
        viewModelScope.launch { container.preferencesRepository.setSortOption(option) }
    }

    fun updateViewMode(mode: ViewMode) {
        viewModelScope.launch { container.preferencesRepository.setViewMode(mode) }
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    fun findVideoById(videoId: String): VideoItem? {
        return _rawVideos.value.firstOrNull { it.id == videoId }
    }

    fun selectAudioTrackForVideo(video: VideoItem, trackIndex: Int, overrideProfile: ReceiverProfile? = null) {
        _selectedAudioTrackByVideo.update { it + (video.id to trackIndex) }
        inspectVideoCompatibility(video, overrideProfile)
    }

    fun inspectVideoCompatibility(video: VideoItem, overrideProfile: ReceiverProfile? = null) {
        viewModelScope.launch {
            _inspectingVideoIds.update { it + video.id }
            try {
                val info = container.mediaInspector.inspect(video)
                val currentSettings = settings.value
                val profile = overrideProfile ?: currentSettings.receiverProfile
                val annotatedAudio = container.compatibilityManager.annotateAudioTrackCompatibility(
                    tracks = info.audioTracks,
                    profile = profile,
                    preferAc3Passthrough = currentSettings.preferAc3Passthrough
                )
                val enrichedInfo = info.copy(audioTracks = annotatedAudio)
                val chosenTrackIdx = _selectedAudioTrackByVideo.value[video.id]
                val report = container.compatibilityManager.evaluate(
                    videoId = video.id,
                    info = enrichedInfo,
                    profile = profile,
                    autoRemuxEnabled = currentSettings.autoRemuxEnabled,
                    allowHardwareTranscode = currentSettings.allowHardwareTranscode,
                    maxTranscodeHeight = currentSettings.maxTranscodeHeight,
                    selectedAudioTrackIndex = chosenTrackIdx,
                    preferredAudioLanguage = currentSettings.preferredAudioLanguage,
                    preferAc3Passthrough = currentSettings.preferAc3Passthrough,
                    ffmpegAvailable = container.ffmpegBackend.isAvailable()
                )
                _compatibilityReports.update { it + (video.id to report) }

                val subtitles = container.subtitleManager.discoverSubtitles(
                    video = video,
                    technicalInfo = enrichedInfo,
                    authorizedSafFolderUris = currentSettings.safFolderUris,
                    manualSubtitleUris = _manualSubtitleUrisByVideo.value[video.id].orEmpty(),
                    isCustomReceiver = playbackState.value.isCustomReceiverConnected
                )
                _subtitlesByVideo.update { it + (video.id to subtitles) }
            } finally {
                _inspectingVideoIds.update { it - video.id }
            }
        }
    }

    private suspend fun refreshSubtitlesForVideo(video: VideoItem): List<SubtitleTrackInfo> {
        val currentSettings = settings.value
        val techInfo = _compatibilityReports.value[video.id]?.technicalInfo
            ?: container.mediaInspector.inspect(video)
        val subtitles = container.subtitleManager.discoverSubtitles(
            video = video,
            technicalInfo = techInfo,
            authorizedSafFolderUris = currentSettings.safFolderUris,
            manualSubtitleUris = _manualSubtitleUrisByVideo.value[video.id].orEmpty(),
            isCustomReceiver = playbackState.value.isCustomReceiverConnected
        )
        _subtitlesByVideo.update { it + (video.id to subtitles) }
        if (playbackState.value.currentVideo?.id == video.id) {
            container.castController.updateTrackCatalog(
                audioTracks = playbackState.value.availableAudioTracks,
                subtitles = subtitles
            )
        }
        return subtitles
    }

    /**
     * Full automatic media casting pipeline:
     * Inspect -> Discover audio & subtitles -> Decide optimal strategy -> Convert only what's needed
     * -> Validate output -> Serve media & prepared subtitles over LAN HTTP -> Load on TV.
     */
    fun castVideo(
        video: VideoItem,
        forceConservativeFallback: Boolean = false,
        startPositionMs: Long = 0L,
        autoplay: Boolean = true
    ) {
        activeCastJob?.cancel()
        activeCastJob = viewModelScope.launch {
            try {
                refreshResourceHealth()

                val accessible = container.videoRepository.verifyUriAccessible(video.uri)
                if (!accessible) {
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.FileMissingOrMoved(video.displayName)
                    )
                    return@launch
                }

                val netSnap = container.networkModeManager.refresh()
                val lanIp = netSnap.preferredBindIpv4
                if (!netSnap.isLocalCastNetworkReady || lanIp == null) {
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.WifiNotConnected
                    )
                    return@launch
                }

                val currentPlayback = playbackState.value
                if (currentPlayback.connectionState != CastConnectionState.CONNECTED) {
                    val err = if (!isCastSdkAvailable.value) {
                        CastError.CastSdkUnavailable
                    } else {
                        CastError.NoCastDeviceFound
                    }
                    _pipelineState.value = CastPipelineState.Failed(video = video, error = err)
                    return@launch
                }
                val deviceName = currentPlayback.connectedDeviceName ?: "Chromecast TV"

                _pipelineState.value = CastPipelineState.Inspecting(video)
                val rawTechInfo = container.mediaInspector.inspect(video)
                val prefs = settings.value
                val ffmpegAvailable = container.ffmpegBackend.isAvailable()

                val annotatedAudio = container.compatibilityManager.annotateAudioTrackCompatibility(
                    tracks = rawTechInfo.audioTracks,
                    profile = prefs.receiverProfile,
                    preferAc3Passthrough = prefs.preferAc3Passthrough
                )
                val techInfo = rawTechInfo.copy(audioTracks = annotatedAudio)
                val chosenTrackIdx = _selectedAudioTrackByVideo.value[video.id]

                val initialReport = container.compatibilityManager.evaluate(
                    videoId = video.id,
                    info = techInfo,
                    profile = prefs.receiverProfile,
                    autoRemuxEnabled = prefs.autoRemuxEnabled,
                    allowHardwareTranscode = prefs.allowHardwareTranscode,
                    maxTranscodeHeight = prefs.maxTranscodeHeight,
                    selectedAudioTrackIndex = chosenTrackIdx,
                    preferredAudioLanguage = prefs.preferredAudioLanguage,
                    preferAc3Passthrough = prefs.preferAc3Passthrough,
                    ffmpegAvailable = ffmpegAvailable
                )

                val report = if (forceConservativeFallback) {
                    container.compatibilityManager.buildConservativeFallbackReport(
                        videoId = video.id,
                        info = techInfo,
                        selectedAudioTrack = initialReport.selectedAudioTrack,
                        ffmpegAvailable = ffmpegAvailable
                    )
                } else {
                    initialReport
                }
                _compatibilityReports.update { it + (video.id to report) }

                // Discover embedded + external SAF subtitles for this video
                val discoveredSubtitles = container.subtitleManager.discoverSubtitles(
                    video = video,
                    technicalInfo = techInfo,
                    authorizedSafFolderUris = prefs.safFolderUris,
                    manualSubtitleUris = _manualSubtitleUrisByVideo.value[video.id].orEmpty(),
                    isCustomReceiver = playbackState.value.isCustomReceiverConnected
                )
                _subtitlesByVideo.update { it + (video.id to discoveredSubtitles) }

                if (report.strategy == PlaybackStrategy.UNSUPPORTED) {
                    val blocking = report.issues.firstOrNull()?.detail ?: report.summary
                    val error = when {
                        blocking.contains("Container", ignoreCase = true) ->
                            CastError.UnsupportedContainer(techInfo.containerFormat)
                        blocking.contains("Audio", ignoreCase = true) ->
                            CastError.UnsupportedAudioCodec(report.selectedAudioTrack?.codecLabel ?: techInfo.audioCodecLabel)
                        blocking.contains("HDR", ignoreCase = true) ->
                            CastError.UnsupportedHdr(techInfo.hdrFormat.label)
                        else -> CastError.UnsupportedVideoCodec(techInfo.videoCodecLabel)
                    }
                    _pipelineState.value = CastPipelineState.Failed(video = video, error = error)
                    return@launch
                }

                executePreparedCast(
                    video = video,
                    report = report,
                    discoveredSubtitles = discoveredSubtitles,
                    lanIp = lanIp,
                    deviceName = deviceName,
                    prefs = prefs,
                    startPositionMs = startPositionMs,
                    autoplay = autoplay,
                    allowFallbackRetry = !forceConservativeFallback &&
                        report.strategy != PlaybackStrategy.CONSERVATIVE_FALLBACK
                )
            } catch (_: CancellationException) {
                _pipelineState.value = CastPipelineState.Idle
            } catch (e: Exception) {
                _pipelineState.value = CastPipelineState.Failed(
                    video = video,
                    error = CastError.Generic(e.message.orEmpty())
                )
            }
        }
    }

    private suspend fun executePreparedCast(
        video: VideoItem,
        report: CompatibilityReport,
        discoveredSubtitles: List<SubtitleTrackInfo>,
        lanIp: String,
        deviceName: String,
        prefs: UserSettings,
        startPositionMs: Long,
        autoplay: Boolean,
        allowFallbackRetry: Boolean
    ) {
        val baseTelemetry = buildTelemetry(video, report, progressPercent = 0, etaSeconds = null)
        val cacheKey = variantCacheKey(video.id, report.selectedAudioTrack?.trackIndex, report.receiverProfile, report.strategy)

        val streamSource = if (report.strategy == PlaybackStrategy.DIRECT_CAST) {
            ContentUriMediaSource(
                context = appContext,
                uri = video.uri,
                mimeType = report.outputMimeType,
                displayName = video.displayName,
                knownSizeBytes = video.sizeBytes
            )
        } else {
            val cachedPrepared = preparedVariantCache[cacheKey]?.takeIf { it.file.exists() && it.file.length() > 0L }
            val prepared = if (cachedPrepared != null) {
                cachedPrepared
            } else {
                if (!container.resourceGuard.hasEnoughDiskSpace(report.estimatedOutputBytes)) {
                    val required = container.resourceGuard.requiredDiskBytes(report.estimatedOutputBytes)
                    val available = container.resourceGuard.availableCacheBytes()
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.InsufficientDiskSpace(
                            requiredLabel = Formatters.formatFileSize(required),
                            availableLabel = Formatters.formatFileSize(available)
                        )
                    )
                    return
                }

                val (thermalLabel, throttled) = container.resourceGuard.currentThermalStatus()
                if (throttled && !report.strategy.preservesVideoBitstream) {
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.ThermalThrottling(thermalLabel)
                    )
                    return
                }

                if (report.recommendedBackend == ProcessingBackendType.FFMPEG &&
                    !container.ffmpegBackend.isAvailable()
                ) {
                    val missingCodec = when {
                        !report.audioCompatible && report.selectedAudioTrack?.hardwareDecoderAvailable == false ->
                            report.selectedAudioTrack.codecLabel
                        !report.videoCompatible && report.technicalInfo.primaryVideoTrack?.hardwareDecoderAvailable == false ->
                            report.technicalInfo.videoCodecLabel
                        else -> null
                    }
                    if (missingCodec != null) {
                        _pipelineState.value = CastPipelineState.Failed(
                            video = video,
                            error = CastError.DecoderUnavailable(missingCodec)
                        )
                        return
                    }
                }

                CastStreamingService.start(
                    context = appContext,
                    videoTitle = video.title,
                    deviceName = deviceName,
                    statusText = report.userHeadline
                )

                val startedMs = System.currentTimeMillis()
                _pipelineState.value = CastPipelineState.PreparingStream(
                    video = video,
                    strategy = report.strategy,
                    progressPercent = 0,
                    stageLabel = report.userHeadline,
                    telemetry = baseTelemetry
                )

                val freshlyPrepared = try {
                    container.preparationEngine.prepareForCast(
                        video = video,
                        report = report,
                        maxTargetHeight = prefs.maxTranscodeHeight
                    ) { pct ->
                        val elapsedSec = ((System.currentTimeMillis() - startedMs) / 1000L).coerceAtLeast(1L)
                        val etaSec = if (pct in 2..99) {
                            ((elapsedSec * (100L - pct)) / pct).coerceAtLeast(1L)
                        } else null
                        _pipelineState.value = CastPipelineState.PreparingStream(
                            video = video,
                            strategy = report.strategy,
                            progressPercent = pct,
                            stageLabel = "${report.userHeadline} ($pct%)",
                            telemetry = buildTelemetry(video, report, pct, etaSec)
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (allowFallbackRetry) {
                        _pipelineState.value = CastPipelineState.RetryingFallback(
                            video = video,
                            reason = "Primary conversion failed (${e.message ?: "codec error"}); retrying with safe H.264/AAC Stereo fallback…"
                        )
                        val fallbackReport = container.compatibilityManager.buildConservativeFallbackReport(
                            videoId = video.id,
                            info = report.technicalInfo,
                            selectedAudioTrack = report.selectedAudioTrack,
                            ffmpegAvailable = container.ffmpegBackend.isAvailable()
                        )
                        executePreparedCast(video, fallbackReport, discoveredSubtitles, lanIp, deviceName, prefs, startPositionMs, autoplay, allowFallbackRetry = false)
                        return
                    }
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.TranscodingFailed(e.message.orEmpty())
                    )
                    return
                }

                _cacheSizeBytes.value = container.preparationEngine.cacheSizeBytes()

                _pipelineState.value = CastPipelineState.ValidatingOutput(
                    video = video,
                    strategy = report.strategy,
                    telemetry = buildTelemetry(video, report, 100, 0L)
                )
                val expectAudio = report.selectedAudioTrack != null
                val validation = container.mediaInspector.validatePreparedOutput(freshlyPrepared.file, expectAudio)
                if (!validation.isValid) {
                    freshlyPrepared.file.delete()
                    if (allowFallbackRetry) {
                        val fallbackReport = container.compatibilityManager.buildConservativeFallbackReport(
                            videoId = video.id,
                            info = report.technicalInfo,
                            selectedAudioTrack = report.selectedAudioTrack,
                            ffmpegAvailable = container.ffmpegBackend.isAvailable()
                        )
                        executePreparedCast(video, fallbackReport, discoveredSubtitles, lanIp, deviceName, prefs, startPositionMs, autoplay, allowFallbackRetry = false)
                        return
                    }
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = CastError.OutputValidationFailed(validation.reason.orEmpty())
                    )
                    return
                }

                preparedVariantCache[cacheKey] = freshlyPrepared
                freshlyPrepared
            }

            container.preparationEngine.markFileInUse(prepared.file)
            FileMediaSource(prepared.file, prepared.mimeType, video.displayName)
        }

        // Start local HTTP Range server on Wi-Fi LAN IP
        _pipelineState.value = CastPipelineState.StartingServer(
            video = video,
            strategy = report.strategy,
            telemetry = buildTelemetry(video, report, 100, 0L)
        )
        val registered = try {
            container.httpServer.start(lanIp, prefs.preferredServerPort)
            container.httpServer.registerMedia(streamSource)
        } catch (e: Exception) {
            _pipelineState.value = CastPipelineState.Failed(
                video = video,
                error = CastError.HttpServerFailure(e.message ?: "Failed to bind local HTTP server.")
            )
            return
        }

        // Prepare default external/embedded text subtitle (if configured) and register served URLs on HTTP server
        val preparedSubtitles = discoveredSubtitles.map { sub ->
            if (!sub.format.isBitmap && sub.sourceType != com.example.core.common.SubtitleSourceType.EMBEDDED) {
                runCatching {
                    val asset = container.subtitleManager.prepareSubtitleAsset(video, sub, prefs.subtitleDelayMs)
                    val regSub = container.httpServer.registerSubtitle(
                        source = FileMediaSource(asset.file, asset.mimeType, asset.file.name),
                        extension = if (asset.isBitmapManifest) "json" else "vtt"
                    )
                    asset.track.copy(servedUrl = regSub.url)
                }.getOrDefault(sub)
            } else sub
        }
        _subtitlesByVideo.update { it + (video.id to preparedSubtitles) }

        val autoSubtitle = container.subtitleManager.selectDefaultSubtitle(
            tracks = preparedSubtitles,
            preferredLanguage = prefs.preferredSubtitleLanguage
        )?.let { candidate ->
            if (candidate.servedUrl != null) candidate
            else runCatching {
                val asset = container.subtitleManager.prepareSubtitleAsset(video, candidate, prefs.subtitleDelayMs)
                val regSub = container.httpServer.registerSubtitle(
                    source = FileMediaSource(asset.file, asset.mimeType, asset.file.name),
                    extension = if (asset.isBitmapManifest) "json" else "vtt"
                )
                asset.track.copy(servedUrl = regSub.url)
            }.getOrNull()
        }

        val finalSubtitles = if (autoSubtitle != null) {
            preparedSubtitles.map { if (it.trackId == autoSubtitle.trackId) autoSubtitle else it }
        } else preparedSubtitles

        val audioCatalog = buildAudioCatalogWithConvertedEntries(report)
        val targetAudioId = report.selectedAudioTrack?.uniqueId
        val qualityLabel = "${report.estimatedTargetResolution} · ${report.outputVideoCodec} · ${report.outputAudioCodec}"

        CastStreamingService.start(
            context = appContext,
            videoTitle = video.title,
            deviceName = deviceName,
            statusText = "${report.strategy.shortBadge} · ${report.estimatedTargetResolution}"
        )

        _pipelineState.value = CastPipelineState.LoadingOnTv(
            video = video,
            deviceName = deviceName,
            strategy = report.strategy,
            streamUrl = registered.url,
            telemetry = buildTelemetry(video, report, 100, 0L)
        )

        container.castController.loadMedia(
            video = video,
            streamUrl = registered.url,
            mimeType = registered.mimeType,
            strategy = report.strategy,
            startPositionMs = startPositionMs,
            autoplay = autoplay,
            audioTracks = audioCatalog,
            targetAudioTrackId = targetAudioId,
            subtitleTracks = finalSubtitles,
            activeSubtitleTrack = autoSubtitle,
            subtitleAppearance = prefs.subtitleAppearance,
            playbackQualityLabel = qualityLabel
        ) { result ->
            result.onSuccess {
                _pipelineState.value = CastPipelineState.Ready(video, deviceName)
            }.onFailure {
                if (allowFallbackRetry && report.strategy != PlaybackStrategy.CONSERVATIVE_FALLBACK) {
                    _pipelineState.value = CastPipelineState.RetryingFallback(
                        video = video,
                        reason = "TV rejected initial stream; converting to conservative 1080p H.264 + AAC Stereo profile…"
                    )
                    castVideo(video, forceConservativeFallback = true, startPositionMs = startPositionMs, autoplay = autoplay)
                } else {
                    val host = registered.url.substringAfter("http://").substringBefore("/")
                    val netErr = if (container.networkModeManager.refresh().isHotspotActive) {
                        CastError.HotspotLocalStreamingBlocked(host)
                    } else {
                        CastError.ClientIsolationSuspected(host)
                    }
                    _pipelineState.value = CastPipelineState.Failed(
                        video = video,
                        error = netErr
                    )
                }
            }
        }
    }

    /**
     * Switches audio language during active Chromecast playback without restarting the movie.
     * - First attempts in-place Custom Web Receiver `AudioTracksManager` switching.
     * - If the requested audio track requires on-demand audio-only conversion (or stream handoff),
     *   converts ONLY that audio track (caching it & preserving original video), then resumes at
     *   the exact millisecond position and play/pause state.
     */
    fun switchAudioTrackDuringPlayback(track: AudioTrackInfo) {
        val currentState = playbackState.value
        val video = currentState.currentVideo ?: return
        if (currentState.activeAudioTrackId == track.uniqueId) return

        // Try live in-place Custom Web Receiver AudioTracksManager switch first if compatible & exposed
        if (currentState.isCustomReceiverConnected &&
            currentState.receiverTrackSwitchingSupported &&
            track.isCompatibleWithReceiver
        ) {
            container.castController.requestLiveAudioSwitchOnReceiver(
                track = track,
                castTrackId = track.trackIndex.toLong(),
                onFallbackRequired = {
                    performPositionPreservingAudioSwitch(video, track)
                }
            )
            return
        }

        performPositionPreservingAudioSwitch(video, track)
    }

    private fun performPositionPreservingAudioSwitch(video: VideoItem, targetTrack: AudioTrackInfo) {
        activeLiveTrackJob?.cancel()
        activeLiveTrackJob = viewModelScope.launch {
            val savedPositionMs = playbackState.value.positionMs
            val wasPlaying = playbackState.value.playerStatus != RemotePlayerStatus.PAUSED
            val prefs = settings.value
            val techInfo = _compatibilityReports.value[video.id]?.technicalInfo
                ?: container.mediaInspector.inspect(video)

            _selectedAudioTrackByVideo.update { it + (video.id to targetTrack.trackIndex) }

            val report = container.compatibilityManager.evaluate(
                videoId = video.id,
                info = techInfo,
                profile = prefs.receiverProfile,
                autoRemuxEnabled = prefs.autoRemuxEnabled,
                allowHardwareTranscode = prefs.allowHardwareTranscode,
                maxTranscodeHeight = prefs.maxTranscodeHeight,
                selectedAudioTrackIndex = targetTrack.trackIndex,
                preferredAudioLanguage = prefs.preferredAudioLanguage,
                preferAc3Passthrough = prefs.preferAc3Passthrough,
                ffmpegAvailable = container.ffmpegBackend.isAvailable()
            )

            try {
                val netSnap = container.networkModeManager.refresh()
                val lanIp = netSnap.preferredBindIpv4
                    ?: throw IllegalStateException("Wi-Fi or Mobile Hotspot connection unavailable.")
                if (!container.httpServer.isRunning) {
                    container.httpServer.start(lanIp, prefs.preferredServerPort)
                } else {
                    container.httpServer.rebindIfNeeded(lanIp, prefs.preferredServerPort)
                }

                val cacheKey = variantCacheKey(video.id, targetTrack.trackIndex, prefs.receiverProfile, report.strategy)
                val source = if (report.strategy == PlaybackStrategy.DIRECT_CAST) {
                    ContentUriMediaSource(appContext, video.uri, report.outputMimeType, video.displayName, video.sizeBytes)
                } else {
                    val cached = preparedVariantCache[cacheKey]?.takeIf { it.file.exists() && it.file.length() > 0L }
                    val prepared = cached ?: run {
                        if (report.recommendedBackend == ProcessingBackendType.FFMPEG &&
                            !container.ffmpegBackend.isAvailable() &&
                            !targetTrack.hardwareDecoderAvailable
                        ) {
                            throw IllegalStateException("No device decoder available for ${targetTrack.codecLabel}.")
                        }
                        val startedMs = System.currentTimeMillis()
                        _liveTrackConversion.value = LiveTrackConversionState(
                            trackTypeLabel = "Audio Track",
                            trackTitle = targetTrack.formattedSummaryLine,
                            sourceFormat = "${targetTrack.codecLabel} (${targetTrack.channelLayout})",
                            targetFormat = report.outputAudioCodec,
                            progressPercent = 0,
                            statusText = "Converting selected audio track (preserving original video)…"
                        )
                        val result = container.preparationEngine.prepareForCast(
                            video = video,
                            report = report,
                            maxTargetHeight = prefs.maxTranscodeHeight
                        ) { pct ->
                            val elapsed = ((System.currentTimeMillis() - startedMs) / 1000L).coerceAtLeast(1L)
                            val eta = if (pct in 2..99) ((elapsed * (100L - pct)) / pct).coerceAtLeast(1L) else null
                            _liveTrackConversion.value = LiveTrackConversionState(
                                trackTypeLabel = "Audio Track",
                                trackTitle = targetTrack.formattedSummaryLine,
                                sourceFormat = "${targetTrack.codecLabel} (${targetTrack.channelLayout})",
                                targetFormat = report.outputAudioCodec,
                                progressPercent = pct,
                                statusText = "Preparing ${targetTrack.languageDisplay} audio… ($pct%)",
                                estimatedSecondsRemaining = eta
                            )
                        }
                        val valid = container.mediaInspector.validatePreparedOutput(result.file, expectAudioTrack = true)
                        if (!valid.isValid) {
                            result.file.delete()
                            throw IllegalStateException(valid.reason ?: "Converted audio stream failed validation.")
                        }
                        preparedVariantCache[cacheKey] = result
                        result
                    }
                    container.preparationEngine.markFileInUse(prepared.file)
                    FileMediaSource(prepared.file, prepared.mimeType, video.displayName)
                }

                _liveTrackConversion.value = null
                val reg = container.httpServer.registerMedia(source)
                val updatedAudioCatalog = buildAudioCatalogWithConvertedEntries(report)
                val currentSubs = playbackState.value.availableSubtitles
                val currentActiveSub = currentSubs.firstOrNull { it.trackId == playbackState.value.activeSubtitleTrackId }

                // Resume seamlessly at exact playback position & play/pause state!
                container.castController.loadMedia(
                    video = video,
                    streamUrl = reg.url,
                    mimeType = reg.mimeType,
                    strategy = report.strategy,
                    startPositionMs = savedPositionMs,
                    autoplay = wasPlaying,
                    audioTracks = updatedAudioCatalog,
                    targetAudioTrackId = targetTrack.uniqueId,
                    subtitleTracks = currentSubs,
                    activeSubtitleTrack = currentActiveSub,
                    subtitleAppearance = playbackState.value.subtitleAppearance,
                    playbackQualityLabel = "${report.estimatedTargetResolution} · ${report.outputVideoCodec} · ${report.outputAudioCodec}"
                ) { /* Confirmation handled directly inside CastSessionController */ }
            } catch (_: CancellationException) {
                _liveTrackConversion.value = null
            } catch (e: Exception) {
                _liveTrackConversion.value = null
                _bannerMessage.value = e.message ?: "Audio switch failed."
            }
        }
    }

    /**
     * Enables, switches, or disables subtitles on TV during active playback without restarting video.
     * Automatically converts SRT/ASS/SSA/TTML -> UTF-8 WebVTT (or PGS/VobSub -> timed bitmap manifest)
     * with caching and active-file deletion protection.
     */
    fun selectSubtitleDuringPlayback(track: SubtitleTrackInfo?) {
        val currentState = playbackState.value
        val video = currentState.currentVideo ?: return

        if (track == null) {
            container.castController.selectSubtitleOnReceiver(null) {}
            return
        }

        activeLiveTrackJob?.cancel()
        activeLiveTrackJob = viewModelScope.launch {
            try {
                val netSnap = container.networkModeManager.refresh()
                val lanIp = netSnap.preferredBindIpv4
                    ?: throw IllegalStateException("Wi-Fi or Mobile Hotspot connection unavailable.")
                if (!container.httpServer.isRunning) {
                    container.httpServer.start(lanIp, settings.value.preferredServerPort)
                } else {
                    container.httpServer.rebindIfNeeded(lanIp, settings.value.preferredServerPort)
                }

                _liveTrackConversion.value = LiveTrackConversionState(
                    trackTypeLabel = "Subtitle Track",
                    trackTitle = track.title,
                    sourceFormat = track.format.displayLabel,
                    targetFormat = if (track.format.isBitmap) "Timed Bitmap Overlay" else "WebVTT (UTF-8)",
                    progressPercent = 15,
                    statusText = "Converting ${track.format.displayLabel} for Chromecast…"
                )

                val asset = container.subtitleManager.prepareSubtitleAsset(
                    video = video,
                    track = track,
                    delayMs = currentState.subtitleDelayMs
                ) { pct ->
                    _liveTrackConversion.value = LiveTrackConversionState(
                        trackTypeLabel = "Subtitle Track",
                        trackTitle = track.title,
                        sourceFormat = track.format.displayLabel,
                        targetFormat = if (track.format.isBitmap) "Timed Bitmap Overlay" else "WebVTT (UTF-8)",
                        progressPercent = pct,
                        statusText = "Preparing ${track.languageDisplay} subtitles ($pct%)…"
                    )
                }

                val regSub = container.httpServer.registerSubtitle(
                    source = FileMediaSource(asset.file, asset.mimeType, asset.file.name),
                    extension = if (asset.isBitmapManifest) "json" else "vtt"
                )
                val readyTrack = asset.track.copy(
                    servedUrl = regSub.url,
                    conversionStatus = if (asset.isBitmapManifest) {
                        SubtitleConversionStatus.REQUIRES_OCR_REVIEW
                    } else {
                        SubtitleConversionStatus.READY
                    }
                )

                val updatedSubs = currentState.availableSubtitles.map {
                    if (it.trackId == readyTrack.trackId) readyTrack else it
                }.let { list ->
                    if (list.any { it.trackId == readyTrack.trackId }) list else list + readyTrack
                }
                _subtitlesByVideo.update { it + (video.id to updatedSubs) }
                container.castController.updateTrackCatalog(currentState.availableAudioTracks, updatedSubs)
                _liveTrackConversion.value = null

                container.castController.selectSubtitleOnReceiver(
                    track = readyTrack,
                    onNeedReloadWithTrack = { reloadTrack ->
                        // Controlled fallback for Default Receiver when attaching a newly picked external subtitle
                        val streamUrl = playbackState.value.activeStreamUrl ?: return@selectSubtitleOnReceiver
                        val strategy = playbackState.value.activeStrategy ?: PlaybackStrategy.DIRECT_CAST
                        container.castController.loadMedia(
                            video = video,
                            streamUrl = streamUrl,
                            mimeType = "video/mp4",
                            strategy = strategy,
                            startPositionMs = playbackState.value.positionMs,
                            autoplay = playbackState.value.playerStatus != RemotePlayerStatus.PAUSED,
                            audioTracks = playbackState.value.availableAudioTracks,
                            targetAudioTrackId = playbackState.value.activeAudioTrackId,
                            subtitleTracks = updatedSubs,
                            activeSubtitleTrack = reloadTrack,
                            subtitleAppearance = playbackState.value.subtitleAppearance
                        ) {}
                    }
                )
            } catch (_: CancellationException) {
                _liveTrackConversion.value = null
            } catch (e: Exception) {
                _liveTrackConversion.value = null
                val failedSubs = playbackState.value.availableSubtitles.map {
                    if (it.trackId == track.trackId) it.copy(conversionStatus = SubtitleConversionStatus.FAILED) else it
                }
                container.castController.updateTrackCatalog(playbackState.value.availableAudioTracks, failedSubs)
                _bannerMessage.value = e.message ?: "Subtitle preparation failed."
            }
        }
    }

    fun adjustSubtitleDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-30_000L, 30_000L)
        viewModelScope.launch {
            container.preferencesRepository.setSubtitleDelayMs(clamped)
            container.castController.applySubtitleDelayOnReceiver(clamped)
            // If connected to Default Receiver with an active text subtitle, regenerate shifted VTT seamlessly
            val state = playbackState.value
            if (!state.isCustomReceiverConnected && state.activeSubtitleTrackId != null) {
                val activeSub = state.availableSubtitles.firstOrNull { it.trackId == state.activeSubtitleTrackId }
                if (activeSub != null && !activeSub.format.isBitmap) {
                    selectSubtitleDuringPlayback(activeSub)
                }
            }
        }
    }

    fun updateSubtitleAppearance(appearance: SubtitleAppearanceSettings) {
        viewModelScope.launch {
            container.preferencesRepository.setSubtitleAppearance(appearance)
            container.castController.applySubtitleAppearanceOnReceiver(appearance)
        }
    }

    fun cancelLiveTrackConversion() {
        activeLiveTrackJob?.cancel()
        activeLiveTrackJob = null
        _liveTrackConversion.value = null
    }

    private fun buildAudioCatalogWithConvertedEntries(report: CompatibilityReport): List<AudioTrackInfo> {
        val baseTracks = report.technicalInfo.audioTracks.ifEmpty {
            listOfNotNull(report.selectedAudioTrack)
        }
        return baseTracks.map { track ->
            val convertedKey = variantCacheKey(
                report.videoId,
                track.trackIndex,
                report.receiverProfile,
                PlaybackStrategy.AUDIO_ONLY_TRANSCODE
            )
            val cached = preparedVariantCache[convertedKey]?.takeIf { it.file.exists() }
            if (cached != null) {
                track.copy(
                    origin = TrackOrigin.CONVERTED,
                    convertedCachePath = cached.file.absolutePath
                )
            } else track
        }
    }

    private fun variantCacheKey(
        videoId: String,
        audioTrackIndex: Int?,
        profile: ReceiverProfile,
        strategy: PlaybackStrategy
    ): String = "${videoId.hashCode()}_${audioTrackIndex ?: -1}_${profile.name}_${strategy.name}"

    private fun buildTelemetry(
        video: VideoItem,
        report: CompatibilityReport,
        progressPercent: Int,
        etaSeconds: Long?
    ): ConversionTelemetry {
        val detectedProblem = report.issues.firstOrNull()?.detail
            ?: "All tracks natively compatible with ${report.receiverProfile.displayName}."
        return ConversionTelemetry(
            filename = video.displayName,
            originalVideoCodec = report.technicalInfo.videoCodecLabel,
            originalAudioCodec = report.selectedAudioTrack?.let {
                "${it.codecLabel} (${it.channelLayout})"
            } ?: report.technicalInfo.audioCodecLabel,
            outputVideoCodec = report.outputVideoCodec,
            outputAudioCodec = report.outputAudioCodec,
            originalResolution = Formatters.resolutionTag(
                report.technicalInfo.width,
                report.technicalInfo.height
            ),
            outputResolution = report.estimatedTargetResolution,
            detectedProblem = detectedProblem,
            headlineMessage = report.userHeadline,
            strategy = report.strategy,
            backend = report.recommendedBackend,
            progressPercent = progressPercent,
            estimatedSecondsRemaining = etaSeconds
        )
    }

    fun addVideoToQueue(video: VideoItem) {
        viewModelScope.launch {
            val netSnap = container.networkModeManager.refresh()
            val lanIp = netSnap.preferredBindIpv4 ?: return@launch
            if (playbackState.value.connectionState != CastConnectionState.CONNECTED) {
                _bannerMessage.value = "Connect to a Chromecast TV first to queue videos."
                return@launch
            }
            if (!container.httpServer.isRunning) {
                container.httpServer.start(lanIp, settings.value.preferredServerPort)
            } else {
                container.httpServer.rebindIfNeeded(lanIp, settings.value.preferredServerPort)
            }
            val source = ContentUriMediaSource(
                context = appContext,
                uri = video.uri,
                mimeType = video.mimeType,
                displayName = video.displayName,
                knownSizeBytes = video.sizeBytes
            )
            val stream = container.httpServer.registerMedia(source)
            container.castController.appendToQueue(video, stream.url, stream.mimeType)
            _bannerMessage.value = "Added \"${video.title}\" to Cast queue"
        }
    }

    fun cancelActiveCastPipeline() {
        activeCastJob?.cancel()
        activeCastJob = null
        _pipelineState.value = CastPipelineState.Idle
        if (!playbackState.value.isCastingActive) {
            CastStreamingService.stop(appContext)
        }
    }

    fun resetPipelineState() {
        _pipelineState.value = CastPipelineState.Idle
    }

    // Cast Discovery & Remote Controls
    fun refreshCastDevices() {
        container.networkModeManager.refresh()
        container.castController.restartDiscovery()
    }

    fun selectCastDevice(routeId: String) = container.castController.selectDevice(routeId)

    fun disconnectCast() {
        pendingRecoveryVideo = null
        activeCastJob?.cancel()
        activeLiveTrackJob?.cancel()
        _liveTrackConversion.value = null
        container.castController.disconnect()
        _pipelineState.value = CastPipelineState.Idle
    }

    fun togglePlayPause() = container.castController.togglePlayPause()
    fun seekTo(ms: Long) = container.castController.seekTo(ms)
    fun seekRelative(deltaMs: Long) = container.castController.seekRelative(deltaMs)
    fun setVolume(volume: Float) = container.castController.setVolume(volume)
    fun toggleMute() = container.castController.toggleMute()
    fun queueNext() = container.castController.queueNext()
    fun queuePrevious() = container.castController.queuePrevious()
    fun clearPlaybackError() = container.castController.clearError()

    fun openSystemHotspotSettings() {
        NetworkModeManager.openSystemHotspotSettings(appContext)
    }

    // Diagnostics & Settings
    suspend fun refreshResourceHealth() {
        val (health, cacheBytes) = withContext(Dispatchers.IO) {
            container.resourceGuard.snapshot(container.ffmpegBackend.isAvailable()) to
                container.preparationEngine.cacheSizeBytes()
        }
        _resourceHealth.value = health
        _cacheSizeBytes.value = cacheBytes
    }

    fun runHttpServerDiagnostics() {
        viewModelScope.launch {
            _isRunningNetworkDiagnostics.value = true
            try {
                refreshResourceHealth()
                val report = container.networkDiagnostics.runFullDiagnostics(
                    preferredServerPort = settings.value.preferredServerPort,
                    castSdkAvailable = isCastSdkAvailable.value,
                    castDiscoveryActive = isScanningDevices.value,
                    discoveredCastCount = discoveredDevices.value.size,
                    connectedDeviceName = playbackState.value.connectedDeviceName,
                    isCastingActive = playbackState.value.isCastingActive
                )
                _lastDiagnosticReport.value = report
                _lastHealthCheck.value = report.httpServerReachable to (report.httpServerLatencyMs ?: 0L)
            } finally {
                _isRunningNetworkDiagnostics.value = false
            }
        }
    }

    fun setReceiverProfile(profile: ReceiverProfile) {
        viewModelScope.launch {
            container.preferencesRepository.setReceiverProfile(profile)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setCustomReceiverAppId(appId: String) {
        viewModelScope.launch {
            container.preferencesRepository.setCustomReceiverAppId(appId)
        }
    }

    fun setAutoRemuxEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setAutoRemuxEnabled(enabled)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setAllowHardwareTranscode(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setAllowHardwareTranscode(enabled)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setMaxTranscodeHeight(height: Int) {
        viewModelScope.launch {
            container.preferencesRepository.setMaxTranscodeHeight(height)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setPreferredAudioLanguage(lang: String) {
        viewModelScope.launch {
            container.preferencesRepository.setPreferredAudioLanguage(lang)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setPreferAc3Passthrough(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setPreferAc3Passthrough(enabled)
            _compatibilityReports.value = emptyMap()
        }
    }

    fun setPreferredSubtitleLanguage(lang: String) {
        viewModelScope.launch {
            container.preferencesRepository.setPreferredSubtitleLanguage(lang)
        }
    }

    fun setPreferredServerPort(port: Int) {
        viewModelScope.launch { container.preferencesRepository.setPreferredServerPort(port) }
    }

    fun setKeepScreenOnRemote(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setKeepScreenOnRemote(enabled) }
    }

    fun clearPreparedMediaCache() {
        viewModelScope.launch {
            container.preparationEngine.clearCache()
            container.subtitleManager.clearUnusedCache()
            preparedVariantCache.entries.removeIf { !it.value.file.exists() }
            refreshResourceHealth()
        }
    }

    // In-App GitHub Releases Updater (delegated to process-scoped UpdateManager)
    fun setGithubUpdateRepo(repoInput: String) {
        val cleaned = repoInput.trim()
        val normalized = if (cleaned.isEmpty()) {
            ""
        } else {
            VersionComparator.normalizeGitHubRepoSlug(cleaned) ?: run {
                _bannerMessage.value = "Use format owner/repo (e.g. username/lumacast)."
                return
            }
        }
        viewModelScope.launch {
            container.preferencesRepository.setGithubUpdateRepo(normalized)
            container.preferencesRepository.setSkippedUpdateTag("")
            _bannerMessage.value = if (normalized.isNotBlank()) {
                "GitHub update source saved: $normalized"
            } else {
                "Cleared custom GitHub update repository."
            }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setAutoCheckUpdates(enabled) }
    }

    fun openUpdateDialog() = container.updateManager.openPrompt()

    fun dismissUpdateDialog() = container.updateManager.dismissPrompt()

    fun skipUpdateVersion(tagName: String) = container.updateManager.skipVersion(tagName)

    fun checkForUpdatesManually() = container.updateManager.checkManually()

    fun downloadAndInstallUpdate(release: GitHubReleaseInfo) =
        container.updateManager.startDownloadAndVerify(release)

    fun cancelUpdateDownload() = container.updateManager.cancelDownload()

    fun createManageUnknownAppsIntent(): Intent =
        container.apkInstallationManager.createManageUnknownAppSourcesIntent()

    fun createInstallApkIntent(apkFile: File): Intent =
        container.apkInstallationManager.createInstallApkIntent(apkFile)

    fun onInstallPermissionReturned() =
        container.updateManager.onResumeFromUnknownSourcesSettings()

    fun requestInstallIfReady(): Boolean =
        container.updateManager.requestInstallIfReady()

    fun onApkInstallerResult(resultCode: Int) =
        container.updateManager.onInstallerResult(resultCode)

    fun openUpdateReleaseInBrowser(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }.onFailure {
            _bannerMessage.value = "Could not open release page in browser."
        }
    }

    class Factory(
        private val appContext: Context,
        private val container: LumaCastContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LumaCastViewModel(appContext, container) as T
        }
    }
}
