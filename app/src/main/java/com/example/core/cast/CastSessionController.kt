package com.example.core.cast

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.core.common.AudioTrackInfo
import com.example.core.common.CastConnectionState
import com.example.core.common.CastDeviceInfo
import com.example.core.common.CastError
import com.example.core.common.PlaybackStrategy
import com.example.core.common.RemotePlaybackState
import com.example.core.common.RemotePlayerStatus
import com.example.core.common.SubtitleAppearanceSettings
import com.example.core.common.SubtitleEdgeType
import com.example.core.common.SubtitleFontFamily
import com.example.core.common.SubtitleTrackInfo
import com.example.core.common.VideoItem
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Wraps the official Google Cast Android Sender SDK (CastContext, SessionManager,
 * MediaRouter, RemoteMediaClient, MediaTrack, TextTrackStyle) + bidirectional custom
 * message channel (`urn:x-cast:com.lumacast.tracks`) to the LumaCast Custom Web Receiver.
 *
 * Core guarantees:
 * - Never fabricates fake TV devices.
 * - Never marks an audio or subtitle track as active in UI state until confirmed by the receiver.
 * - Supports real-time AudioTracksManager & TextTracksManager switching on Custom Web Receiver,
 *   plus position-preserving reload fallback when switching audio on progressive streams.
 */
class CastSessionController(
    private val context: Context,
    private val onSessionTerminated: () -> Unit,
    private val onDiscoveryActiveChanged: (Boolean) -> Unit = {},
    private val onDiscoveredDeviceCountChanged: (Int) -> Unit = {}
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initExecutor = Executors.newSingleThreadExecutor()

    private val _isSdkAvailable = MutableStateFlow(false)
    val isSdkAvailable: StateFlow<Boolean> = _isSdkAvailable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveryTimedOut = MutableStateFlow(false)
    val discoveryTimedOut: StateFlow<Boolean> = _discoveryTimedOut.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<CastDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val discoveryTimeoutRunnable = Runnable {
        if (_isScanning.value && _discoveredDevices.value.isEmpty()) {
            _discoveryTimedOut.value = true
        }
        // Release MulticastLock after the active discovery burst to avoid continuously holding network locks
        onDiscoveryActiveChanged(false)
    }

    private val _playbackState = MutableStateFlow(RemotePlaybackState())
    val playbackState: StateFlow<RemotePlaybackState> = _playbackState.asStateFlow()

    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var activeCastSession: CastSession? = null
    private var attachedRemoteClient: RemoteMediaClient? = null
    private var routerCallbackRegistered = false

    private val routeById = LinkedHashMap<String, MediaRouter.RouteInfo>()

    private val customMessageCallback = Cast.MessageReceivedCallback { _, namespace, message ->
        if (namespace != CastTrackProtocol.NAMESPACE) return@MessageReceivedCallback
        val event = CastTrackProtocol.parseReceiverEvent(message) ?: return@MessageReceivedCallback
        handleReceiverTrackEvent(event)
    }

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        _playbackState.update { state ->
            state.copy(
                positionMs = progressMs.coerceAtLeast(0L),
                durationMs = if (durationMs > 0L) durationMs else state.durationMs
            )
        }
    }

    private val remoteMediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = syncRemoteMediaStatus()
        override fun onMetadataUpdated() = syncRemoteMediaStatus()
        override fun onQueueStatusUpdated() = syncRemoteMediaStatus()
        override fun onPreloadStatusUpdated() = syncRemoteMediaStatus()
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDeviceName = session.castDevice?.friendlyName,
                    lastError = null
                )
            }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachSession(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            detachSession()
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.DISCONNECTED,
                    connectedDeviceName = null,
                    lastError = CastError.CastSessionLaunchFailed("Cast session failed to start (code $error).")
                )
            }
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachSession()
            _playbackState.update {
                RemotePlaybackState(
                    connectionState = CastConnectionState.DISCONNECTED,
                    lastError = if (error != 0) {
                        CastError.CastSessionLaunchFailed("Cast session ended unexpectedly (code $error).")
                    } else null
                )
            }
            onSessionTerminated()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.RECONNECTING,
                    connectedDeviceName = session.castDevice?.friendlyName
                )
            }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachSession(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detachSession()
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.DISCONNECTED,
                    connectedDeviceName = null,
                    lastError = CastError.CastSessionLaunchFailed("Could not reconnect to TV session (code $error).")
                )
            }
            onSessionTerminated()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _playbackState.update { it.copy(connectionState = CastConnectionState.RECONNECTING) }
        }
    }

    fun initialize() {
        try {
            CastContext.getSharedInstance(context, initExecutor)
                .addOnSuccessListener { ctx ->
                    mainHandler.post {
                        castContext = ctx
                        sessionManager = ctx.sessionManager
                        mediaRouter = MediaRouter.getInstance(context)
                        routeSelector = ctx.mergedSelector
                        _isSdkAvailable.value = true

                        ctx.sessionManager.addSessionManagerListener(
                            sessionManagerListener,
                            CastSession::class.java
                        )

                        ctx.sessionManager.currentCastSession?.let { existing ->
                            if (existing.isConnected) {
                                attachSession(existing)
                            }
                        }

                        startDiscovery()
                    }
                }
                .addOnFailureListener {
                    mainHandler.post {
                        _isSdkAvailable.value = false
                        _playbackState.update {
                            it.copy(
                                connectionState = CastConnectionState.UNAVAILABLE,
                                lastError = CastError.CastSdkUnavailable
                            )
                        }
                    }
                }
        } catch (_: Exception) {
            _isSdkAvailable.value = false
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.UNAVAILABLE,
                    lastError = CastError.CastSdkUnavailable
                )
            }
        }
    }

    fun startDiscovery() {
        mainHandler.post {
            val router = mediaRouter ?: return@post
            val selector = routeSelector ?: return@post
            _discoveryTimedOut.value = false
            onDiscoveryActiveChanged(true)
            if (!routerCallbackRegistered) {
                router.addCallback(
                    selector,
                    mediaRouterCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                )
                routerCallbackRegistered = true
            }
            _isScanning.value = true
            scheduleDiscoveryTimeout()
            refreshRoutes()
        }
    }

    /**
     * Tears down and re-registers MediaRouter Cast discovery — critical when transitioning
     * between Conventional Wi-Fi and Android Mobile Hotspot or after an IP address change.
     */
    fun restartDiscovery() {
        mainHandler.post {
            mainHandler.removeCallbacks(discoveryTimeoutRunnable)
            val router = mediaRouter
            val selector = routeSelector
            if (router != null && routerCallbackRegistered) {
                runCatching { router.removeCallback(mediaRouterCallback) }
                routerCallbackRegistered = false
            }
            _discoveryTimedOut.value = false
            if (router != null && selector != null) {
                onDiscoveryActiveChanged(true)
                runCatching {
                    router.addCallback(
                        selector,
                        mediaRouterCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                    )
                    routerCallbackRegistered = true
                }
                _isScanning.value = true
                scheduleDiscoveryTimeout()
                refreshRoutes()
            } else {
                onDiscoveryActiveChanged(false)
            }
        }
    }

    fun stopDiscovery() {
        mainHandler.post {
            mainHandler.removeCallbacks(discoveryTimeoutRunnable)
            if (routerCallbackRegistered) {
                mediaRouter?.removeCallback(mediaRouterCallback)
                routerCallbackRegistered = false
            }
            _isScanning.value = false
            onDiscoveryActiveChanged(false)
        }
    }

    private fun scheduleDiscoveryTimeout() {
        mainHandler.removeCallbacks(discoveryTimeoutRunnable)
        mainHandler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS)
    }

    fun refreshRoutes() {
        mainHandler.post {
            val router = mediaRouter ?: return@post
            val selector = routeSelector ?: return@post
            val routes = router.routes.filter { route ->
                !route.isDefault && route.isEnabled && route.matchesSelector(selector)
            }
            routeById.clear()
            val mapped = routes.map { route ->
                routeById[route.id] = route
                CastDeviceInfo(
                    routeId = route.id,
                    name = route.name.ifBlank { "Chromecast Device" },
                    description = route.description?.takeIf { it.isNotBlank() } ?: "Google Cast Receiver",
                    isSelected = route.isSelected,
                    isConnecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                    deviceTypeLabel = when (route.deviceType) {
                        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> "Smart TV"
                        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> "Cast Speaker"
                        else -> "Chromecast"
                    }
                )
            }
            _discoveredDevices.value = mapped
            if (mapped.isNotEmpty()) {
                _discoveryTimedOut.value = false
            }
            onDiscoveredDeviceCountChanged(mapped.size)
        }
    }

    fun selectDevice(routeId: String): Boolean {
        val route = routeById[routeId] ?: return false
        mainHandler.post {
            _playbackState.update {
                it.copy(
                    connectionState = CastConnectionState.CONNECTING,
                    connectedDeviceName = route.name,
                    lastError = null
                )
            }
            route.select()
        }
        return true
    }

    fun disconnect() {
        mainHandler.post {
            sessionManager?.endCurrentSession(true)
            detachSession()
            _playbackState.value = RemotePlaybackState(connectionState = CastConnectionState.DISCONNECTED)
            onSessionTerminated()
        }
    }

    fun updateTrackCatalog(
        audioTracks: List<AudioTrackInfo>,
        subtitles: List<SubtitleTrackInfo>,
        playbackQualityLabel: String? = null
    ) {
        _playbackState.update { state ->
            state.copy(
                availableAudioTracks = audioTracks,
                availableSubtitles = subtitles,
                playbackQualityLabel = playbackQualityLabel ?: state.playbackQualityLabel
            )
        }
    }

    /**
     * Loads media onto the Chromecast receiver, attaching all prepared WebVTT/TTML subtitle
     * MediaTracks and applying TextTrackStyle. Supports non-zero [startPositionMs] and [autoplay]
     * for seamless position-preserving audio track switching fallback.
     */
    fun loadMedia(
        video: VideoItem,
        streamUrl: String,
        mimeType: String,
        strategy: PlaybackStrategy,
        startPositionMs: Long = 0L,
        autoplay: Boolean = true,
        audioTracks: List<AudioTrackInfo> = _playbackState.value.availableAudioTracks,
        targetAudioTrackId: String? = null,
        subtitleTracks: List<SubtitleTrackInfo> = _playbackState.value.availableSubtitles,
        activeSubtitleTrack: SubtitleTrackInfo? = null,
        subtitleAppearance: SubtitleAppearanceSettings = _playbackState.value.subtitleAppearance,
        playbackQualityLabel: String? = null,
        onResult: (Result<Unit>) -> Unit
    ) {
        mainHandler.post {
            val session = activeCastSession ?: sessionManager?.currentCastSession
            val client = session?.remoteMediaClient
            if (session == null || !session.isConnected || client == null) {
                onResult(Result.failure(IllegalStateException("No active Chromecast session.")))
                return@post
            }

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, video.title)
                putString(
                    MediaMetadata.KEY_SUBTITLE,
                    "${playbackQualityLabel ?: video.resolutionLabel} · ${strategy.shortBadge} · LumaCast"
                )
            }

            // Build official Cast MediaTrack objects for all text subtitles that have a local HTTP URL
            val castTextTracks = subtitleTracks.mapNotNull { sub ->
                val url = sub.servedUrl ?: return@mapNotNull null
                if (sub.format.isBitmap) return@mapNotNull null
                MediaTrack.Builder(sub.castTrackId, MediaTrack.TYPE_TEXT)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(url)
                    .setContentType(if (sub.format.extension == "ttml") "application/ttml+xml" else "text/vtt")
                    .setName(sub.title)
                    .setLanguage(sub.languageCode ?: "en")
                    .build()
            }

            val mediaInfo = MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(metadata)
                .apply {
                    if (video.durationMs > 0L) setStreamDuration(video.durationMs)
                    if (castTextTracks.isNotEmpty()) setMediaTracks(castTextTracks)
                    setTextTrackStyle(toCastTextTrackStyle(subtitleAppearance))
                }
                .build()

            val activeIds = activeSubtitleTrack
                ?.takeIf { !it.format.isBitmap && it.servedUrl != null }
                ?.let { longArrayOf(it.castTrackId) }

            val loadRequest = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(autoplay)
                .setCurrentTime(startPositionMs.coerceAtLeast(0L))
                .apply {
                    if (activeIds != null) setActiveTrackIds(activeIds)
                }
                .build()

            // Set pending IDs — DO NOT mark active until receiver confirms LOAD success!
            _playbackState.update { state ->
                val updatedQueue = if (state.queue.any { it.id == video.id }) state.queue else state.queue + video
                state.copy(
                    currentVideo = video,
                    queue = updatedQueue,
                    currentQueueIndex = updatedQueue.indexOfFirst { it.id == video.id },
                    playerStatus = RemotePlayerStatus.LOADING,
                    durationMs = video.durationMs,
                    positionMs = startPositionMs.coerceAtLeast(0L),
                    activeStrategy = strategy,
                    activeStreamUrl = streamUrl,
                    playbackQualityLabel = playbackQualityLabel ?: "${video.resolutionLabel} · ${strategy.shortBadge}",
                    availableAudioTracks = audioTracks,
                    pendingAudioTrackId = targetAudioTrackId,
                    availableSubtitles = subtitleTracks,
                    pendingSubtitleTrackId = activeSubtitleTrack?.trackId,
                    subtitleAppearance = subtitleAppearance,
                    lastError = null
                )
            }

            client.load(loadRequest).setResultCallback { result ->
                if (result.status.isSuccess) {
                    val confirmedAudio = audioTracks.firstOrNull { it.uniqueId == targetAudioTrackId }
                        ?: audioTracks.firstOrNull()
                    _playbackState.update { state ->
                        state.copy(
                            activeAudioTrackId = confirmedAudio?.uniqueId,
                            activeAudioLabel = confirmedAudio?.formattedSummaryLine,
                            pendingAudioTrackId = null,
                            activeSubtitleTrackId = activeSubtitleTrack?.takeIf { !it.format.isBitmap }?.trackId,
                            pendingSubtitleTrackId = null
                        )
                    }
                    // Sync style & request receiver track inventory if Custom Receiver is active
                    sendCustomMessage(CastTrackProtocol.buildSetSubtitleStyle(subtitleAppearance))
                    sendCustomMessage(CastTrackProtocol.buildRequestTrackState())
                    if (activeSubtitleTrack != null && activeSubtitleTrack.format.isBitmap && activeSubtitleTrack.servedUrl != null) {
                        sendCustomMessage(
                            CastTrackProtocol.buildLoadBitmapSubtitles(
                                subtitleTrackId = activeSubtitleTrack.trackId,
                                manifestUrl = activeSubtitleTrack.servedUrl
                            )
                        )
                    }
                    onResult(Result.success(Unit))
                } else {
                    val host = streamUrl.substringAfter("http://").substringBefore("/")
                    val err = CastError.ClientIsolationSuspected(host)
                    _playbackState.update {
                        it.copy(
                            playerStatus = RemotePlayerStatus.ERROR,
                            pendingAudioTrackId = null,
                            pendingSubtitleTrackId = null,
                            lastError = err
                        )
                    }
                    onResult(Result.failure(IllegalStateException(result.status.statusMessage ?: err.message)))
                }
            }
        }
    }

    /**
     * Attempts in-place live audio track switching via Custom Web Receiver `AudioTracksManager`
     * without interrupting playback. Never marks the track active until the receiver sends
     * `AUDIO_TRACK_CONFIRMED`.
     */
    fun requestLiveAudioSwitchOnReceiver(
        track: AudioTrackInfo,
        castTrackId: Long? = null,
        onFallbackRequired: () -> Unit
    ) {
        mainHandler.post {
            val state = _playbackState.value
            val session = activeCastSession
            if (session == null || !session.isConnected) {
                _playbackState.update {
                    it.copy(lastError = CastError.ReceiverAudioSwitchFailed("No active Chromecast session."))
                }
                return@post
            }

            if (!state.isCustomReceiverConnected || !state.receiverTrackSwitchingSupported) {
                onFallbackRequired()
                return@post
            }

            _playbackState.update { it.copy(pendingAudioTrackId = track.uniqueId, lastError = null) }
            val payload = CastTrackProtocol.buildSelectAudioTrack(
                trackUniqueId = track.uniqueId,
                castTrackId = castTrackId,
                language = track.languageCode
            )
            val sent = sendCustomMessage(payload)
            if (!sent) {
                onFallbackRequired()
            }
        }
    }

    /**
     * Switches or disables TV-side subtitle rendering without stopping video playback.
     * Uses Custom Receiver `TextTracksManager` when connected, or Cast `RemoteMediaClient.setActiveMediaTracks`
     * on standard streams, updating UI active state ONLY upon receiver confirmation.
     */
    fun selectSubtitleOnReceiver(
        track: SubtitleTrackInfo?,
        onNeedReloadWithTrack: (SubtitleTrackInfo) -> Unit
    ) {
        mainHandler.post {
            val client = attachedRemoteClient ?: return@post
            val state = _playbackState.value

            if (track == null) {
                // Disable subtitles cleanly
                _playbackState.update { it.copy(pendingSubtitleTrackId = null) }
                if (state.isCustomReceiverConnected) {
                    sendCustomMessage(
                        CastTrackProtocol.buildSelectSubtitleTrack(
                            subtitleTrackId = null,
                            castTrackId = null,
                            disable = true
                        )
                    )
                }
                client.setActiveMediaTracks(longArrayOf()).setResultCallback { res ->
                    if (res.status.isSuccess) {
                        _playbackState.update {
                            it.copy(activeSubtitleTrackId = null, pendingSubtitleTrackId = null)
                        }
                    } else {
                        _playbackState.update {
                            it.copy(lastError = CastError.SubtitleLoadFailed(res.status.statusMessage.orEmpty()))
                        }
                    }
                }
                return@post
            }

            _playbackState.update { it.copy(pendingSubtitleTrackId = track.trackId, lastError = null) }

            val url = track.servedUrl
            if (url == null) {
                _playbackState.update {
                    it.copy(
                        pendingSubtitleTrackId = null,
                        lastError = CastError.SubtitleLoadFailed("Subtitle stream URL is not ready yet.")
                    )
                }
                return@post
            }

            if (track.format.isBitmap) {
                if (state.isCustomReceiverConnected) {
                    sendCustomMessage(CastTrackProtocol.buildLoadBitmapSubtitles(track.trackId, url))
                } else {
                    _playbackState.update {
                        it.copy(
                            pendingSubtitleTrackId = null,
                            lastError = CastError.UnsupportedSubtitleFormat(track.format.displayLabel)
                        )
                    }
                }
                return@post
            }

            // Custom Web Receiver can dynamically inject external WebVTT without reloading media!
            if (state.isCustomReceiverConnected) {
                sendCustomMessage(
                    CastTrackProtocol.buildAddExternalSubtitle(
                        subtitleTrackId = track.trackId,
                        url = url,
                        mimeType = if (track.format.extension == "ttml") "application/ttml+xml" else "text/vtt",
                        language = track.languageCode ?: "en",
                        name = track.title
                    )
                )
                return@post
            }

            // Check if this Cast MediaTrack ID is already attached to current MediaInfo
            val existingTracks = client.mediaInfo?.mediaTracks.orEmpty()
            if (existingTracks.any { it.id == track.castTrackId }) {
                client.setActiveMediaTracks(longArrayOf(track.castTrackId)).setResultCallback { res ->
                    if (res.status.isSuccess) {
                        _playbackState.update {
                            it.copy(activeSubtitleTrackId = track.trackId, pendingSubtitleTrackId = null)
                        }
                    } else {
                        _playbackState.update {
                            it.copy(
                                pendingSubtitleTrackId = null,
                                lastError = CastError.SubtitleLoadFailed(res.status.statusMessage.orEmpty())
                            )
                        }
                    }
                }
            } else {
                // Default Media Receiver requires attaching newly added external subtitle track via position-preserving reload
                onNeedReloadWithTrack(track)
            }
        }
    }

    fun applySubtitleDelayOnReceiver(delayMs: Long) {
        mainHandler.post {
            _playbackState.update { it.copy(subtitleDelayMs = delayMs) }
            if (_playbackState.value.isCustomReceiverConnected) {
                sendCustomMessage(CastTrackProtocol.buildSetSubtitleDelay(delayMs))
            }
        }
    }

    fun applySubtitleAppearanceOnReceiver(appearance: SubtitleAppearanceSettings) {
        mainHandler.post {
            _playbackState.update { it.copy(subtitleAppearance = appearance) }
            attachedRemoteClient?.setTextTrackStyle(toCastTextTrackStyle(appearance))
            if (_playbackState.value.isCustomReceiverConnected) {
                sendCustomMessage(CastTrackProtocol.buildSetSubtitleStyle(appearance))
            }
        }
    }

    fun appendToQueue(video: VideoItem, streamUrl: String, mimeType: String) {
        mainHandler.post {
            val client = attachedRemoteClient ?: return@post
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, video.title)
                putString(MediaMetadata.KEY_SUBTITLE, "${video.resolutionLabel} · LumaCast")
            }
            val mediaInfo = MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(metadata)
                .build()
            val queueItem = MediaQueueItem.Builder(mediaInfo).setAutoplay(true).build()
            client.queueAppendItem(queueItem, null)
            _playbackState.update { state ->
                if (state.queue.any { it.id == video.id }) state
                else state.copy(queue = state.queue + video)
            }
        }
    }

    fun togglePlayPause() {
        mainHandler.post {
            val client = attachedRemoteClient ?: return@post
            if (client.isPlaying) client.pause() else client.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            val client = attachedRemoteClient ?: return@post
            val target = positionMs.coerceAtLeast(0L)
            val options = MediaSeekOptions.Builder().setPosition(target).build()
            client.seek(options).setResultCallback { result ->
                if (!result.status.isSuccess) {
                    _playbackState.update {
                        it.copy(lastError = CastError.SeekingFailed(result.status.statusMessage.orEmpty()))
                    }
                }
            }
            _playbackState.update { it.copy(positionMs = target) }
        }
    }

    fun seekRelative(deltaMs: Long) {
        val current = _playbackState.value.positionMs
        val duration = _playbackState.value.durationMs
        val next = (current + deltaMs).coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
        seekTo(next)
    }

    fun setVolume(volume: Float) {
        mainHandler.post {
            val session = activeCastSession ?: return@post
            val clamped = volume.coerceIn(0f, 1f)
            runCatching { session.volume = clamped.toDouble() }
            _playbackState.update { it.copy(volume = clamped, isMuted = clamped == 0f) }
        }
    }

    fun toggleMute() {
        mainHandler.post {
            val session = activeCastSession ?: return@post
            val nextMute = !session.isMute
            runCatching { session.isMute = nextMute }
            _playbackState.update { it.copy(isMuted = nextMute) }
        }
    }

    fun queueNext() {
        mainHandler.post { attachedRemoteClient?.queueNext(null) }
    }

    fun queuePrevious() {
        mainHandler.post { attachedRemoteClient?.queuePrev(null) }
    }

    fun clearError() {
        _playbackState.update { it.copy(lastError = null) }
    }

    fun release() {
        stopDiscovery()
        mainHandler.post {
            sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
            detachSession()
        }
        initExecutor.shutdown()
    }

    private fun attachSession(session: CastSession) {
        detachSession()
        activeCastSession = session
        val client = session.remoteMediaClient
        attachedRemoteClient = client
        client?.registerCallback(remoteMediaCallback)
        client?.addProgressListener(progressListener, 500L)

        // Register custom LumaCast track control namespace for Custom Web Receiver
        runCatching {
            session.setMessageReceivedCallbacks(CastTrackProtocol.NAMESPACE, customMessageCallback)
        }

        val customConfigured = LumaCastOptionsProvider.isCustomReceiverConfigured(context)
        _playbackState.update {
            it.copy(
                connectionState = CastConnectionState.CONNECTED,
                connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast TV",
                volume = session.volume.toFloat().coerceIn(0f, 1f),
                isMuted = session.isMute,
                isCustomReceiverConnected = customConfigured,
                lastError = null
            )
        }
        if (customConfigured) {
            sendCustomMessage(CastTrackProtocol.buildRequestTrackState())
        }
        syncRemoteMediaStatus()
        refreshRoutes()
    }

    private fun detachSession() {
        runCatching { activeCastSession?.removeMessageReceivedCallbacks(CastTrackProtocol.NAMESPACE) }
        attachedRemoteClient?.unregisterCallback(remoteMediaCallback)
        attachedRemoteClient?.removeProgressListener(progressListener)
        attachedRemoteClient = null
        activeCastSession = null
    }

    private fun sendCustomMessage(jsonPayload: String): Boolean {
        val session = activeCastSession ?: return false
        if (!session.isConnected) return false
        return runCatching {
            session.sendMessage(CastTrackProtocol.NAMESPACE, jsonPayload)
            true
        }.getOrDefault(false)
    }

    /**
     * Handles Receiver -> Sender confirmation messages so the UI strictly reflects
     * receiver-confirmed audio and subtitle track states.
     */
    internal fun handleReceiverTrackEvent(event: ReceiverTrackEvent) {
        when (event) {
            is ReceiverTrackEvent.TrackStateSync -> {
                _playbackState.update { state ->
                    val confirmedSub = state.availableSubtitles.firstOrNull {
                        it.castTrackId in event.activeTextCastTrackIds
                    }?.trackId ?: state.activeSubtitleTrackId
                    state.copy(
                        isCustomReceiverConnected = true,
                        receiverTrackSwitchingSupported = event.supportsAudioTrackSwitching,
                        activeSubtitleTrackId = confirmedSub,
                        subtitleDelayMs = event.subtitleDelayMs
                    )
                }
            }

            is ReceiverTrackEvent.AudioTrackConfirmed -> {
                _playbackState.update { state ->
                    val matched = state.availableAudioTracks.firstOrNull {
                        it.uniqueId == event.trackUniqueId ||
                            (event.language != null && it.languageCode.equals(event.language, ignoreCase = true))
                    }
                    state.copy(
                        activeAudioTrackId = matched?.uniqueId ?: event.trackUniqueId,
                        activeAudioLabel = matched?.formattedSummaryLine ?: event.name ?: state.activeAudioLabel,
                        pendingAudioTrackId = null,
                        lastError = null
                    )
                }
            }

            is ReceiverTrackEvent.SubtitleTrackConfirmed -> {
                _playbackState.update { state ->
                    state.copy(
                        activeSubtitleTrackId = if (event.enabled) event.subtitleTrackId else null,
                        pendingSubtitleTrackId = null,
                        lastError = null
                    )
                }
            }

            is ReceiverTrackEvent.SubtitleDelayConfirmed -> {
                _playbackState.update { it.copy(subtitleDelayMs = event.delayMs) }
            }

            is ReceiverTrackEvent.SubtitleStyleConfirmed -> Unit

            is ReceiverTrackEvent.TrackError -> {
                val mappedError = when {
                    event.code.startsWith("AUDIO") -> CastError.ReceiverAudioSwitchFailed(event.message)
                    event.code.contains("BITMAP") -> CastError.SubtitleOcrFailed(event.message)
                    event.code.startsWith("SUBTITLE") -> CastError.SubtitleLoadFailed(event.message)
                    else -> CastError.CustomReceiverError("${event.code}: ${event.message}")
                }
                _playbackState.update {
                    it.copy(
                        pendingAudioTrackId = null,
                        pendingSubtitleTrackId = null,
                        lastError = mappedError
                    )
                }
            }
        }
    }

    private fun syncRemoteMediaStatus() {
        val client = attachedRemoteClient ?: return
        val session = activeCastSession
        val status = client.mediaStatus

        val mappedStatus = when (status?.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> RemotePlayerStatus.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> RemotePlayerStatus.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING -> RemotePlayerStatus.BUFFERING
            MediaStatus.PLAYER_STATE_LOADING -> RemotePlayerStatus.LOADING
            MediaStatus.PLAYER_STATE_IDLE -> {
                if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) RemotePlayerStatus.ERROR
                else RemotePlayerStatus.IDLE
            }
            else -> _playbackState.value.playerStatus
        }

        val mediaError = if (status?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        ) {
            val host = _playbackState.value.activeStreamUrl
                ?.substringAfter("http://")
                ?.substringBefore("/")
                ?: "local HTTP server"
            CastError.ClientIsolationSuspected(host)
        } else {
            _playbackState.value.lastError
        }

        val duration = client.streamDuration.takeIf { it > 0L } ?: _playbackState.value.durationMs
        val position = client.approximateStreamPosition.coerceAtLeast(0L)

        // Confirm active Cast MediaTrack IDs from MediaStatus when available
        val activeCastTrackIds = status?.activeTrackIds?.toSet()

        _playbackState.update { state ->
            val syncedSubId = if (activeCastTrackIds != null && !state.isCustomReceiverConnected) {
                state.availableSubtitles.firstOrNull { it.castTrackId in activeCastTrackIds }?.trackId
            } else {
                state.activeSubtitleTrackId
            }
            state.copy(
                playerStatus = mappedStatus,
                positionMs = position,
                durationMs = duration,
                volume = session?.volume?.toFloat()?.coerceIn(0f, 1f) ?: state.volume,
                isMuted = session?.isMute ?: state.isMuted,
                activeSubtitleTrackId = syncedSubId,
                lastError = if (mappedStatus == RemotePlayerStatus.PLAYING) null else mediaError
            )
        }
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 30_000L

        fun toCastTextTrackStyle(appearance: SubtitleAppearanceSettings): TextTrackStyle {
            val fgColor = appearance.foregroundColorArgb.toInt()
            val bgAlpha = (appearance.backgroundOpacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            val bgRgb = appearance.backgroundColorArgb.toInt() and 0x00FFFFFF
            val bgColor = (bgAlpha shl 24) or bgRgb
            val edgeColor = appearance.edgeColorArgb.toInt()

            val castEdge = when (appearance.edgeType) {
                SubtitleEdgeType.NONE -> TextTrackStyle.EDGE_TYPE_NONE
                SubtitleEdgeType.OUTLINE -> TextTrackStyle.EDGE_TYPE_OUTLINE
                SubtitleEdgeType.DROP_SHADOW -> TextTrackStyle.EDGE_TYPE_DROP_SHADOW
                SubtitleEdgeType.RAISED -> TextTrackStyle.EDGE_TYPE_RAISED
                SubtitleEdgeType.DEPRESSED -> TextTrackStyle.EDGE_TYPE_DEPRESSED
            }
            val castFont = when (appearance.fontFamily) {
                SubtitleFontFamily.SANS_SERIF -> TextTrackStyle.FONT_FAMILY_SANS_SERIF
                SubtitleFontFamily.MONOSPACED_SANS -> TextTrackStyle.FONT_FAMILY_MONOSPACED_SANS_SERIF
                SubtitleFontFamily.SERIF -> TextTrackStyle.FONT_FAMILY_SERIF
                SubtitleFontFamily.CASUAL -> TextTrackStyle.FONT_FAMILY_CASUAL
            }

            return TextTrackStyle().apply {
                fontScale = appearance.fontScale.coerceIn(0.6f, 2.0f)
                foregroundColor = fgColor
                backgroundColor = bgColor
                edgeType = castEdge
                this.edgeColor = edgeColor
                fontGenericFamily = castFont
                windowType = TextTrackStyle.WINDOW_TYPE_NONE
                windowColor = Color.TRANSPARENT
            }
        }
    }
}
