package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.AudioTrackInfo
import com.example.core.common.Formatters
import com.example.core.common.LiveTrackConversionState
import com.example.core.common.RemotePlaybackState
import com.example.core.common.RemotePlayerStatus
import com.example.core.common.SubtitleAppearanceSettings
import com.example.core.common.SubtitleTrackInfo
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.components.AudioTrackSelectionSheet
import com.example.ui.components.CastErrorCard
import com.example.ui.components.StrategyBadge
import com.example.ui.components.SubtitleManagementSheet
import com.example.ui.components.SubtitleSheetTab
import com.example.ui.components.VideoThumbnailImage
import com.example.ui.components.displayLabel
import com.example.ui.theme.AppThemeTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemotePlayerScreen(
    playbackState: RemotePlaybackState,
    liveTrackConversion: LiveTrackConversionState?,
    keepScreenOn: Boolean,
    thumbnailLoader: VideoThumbnailLoader? = null,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onQueuePrevious: () -> Unit,
    onQueueNext: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onSwitchAudioTrack: (AudioTrackInfo) -> Unit,
    onSelectSubtitle: (SubtitleTrackInfo?) -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onAuthorizeSubtitleFolder: () -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onUpdateSubtitleAppearance: (SubtitleAppearanceSettings) -> Unit,
    onCancelLiveConversion: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showAudioSheet by remember { mutableStateOf(false) }
    var subtitleSheetTab by remember { mutableStateOf<SubtitleSheetTab?>(null) }

    val activeSub = remember(playbackState.availableSubtitles, playbackState.activeSubtitleTrackId) {
        playbackState.availableSubtitles.firstOrNull {
            it.trackId == playbackState.activeSubtitleTrackId
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tv,
                            contentDescription = null,
                            tint = colors.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = playbackState.connectedDeviceName ?: "Remote Controller",
                            style = tokens.deviceName,
                            color = colors.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("remote_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .testTag("remote_player_screen"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Artwork Frame with Live Buffering / Loading overlay
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(colors.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        playbackState.currentVideo?.let { video ->
                            VideoThumbnailImage(
                                video = video,
                                thumbnailLoader = thumbnailLoader,
                                targetWidthPx = 640,
                                targetHeightPx = 360,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (playbackState.playerStatus == RemotePlayerStatus.BUFFERING ||
                            playbackState.playerStatus == RemotePlayerStatus.LOADING
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(semantic.scrimDark.copy(alpha = 0.62f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = colors.primary)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = if (playbackState.playerStatus == RemotePlayerStatus.BUFFERING) {
                                            "Buffering on TV"
                                        } else {
                                            "Loading on TV"
                                        },
                                        style = tokens.statusMessage,
                                        color = semantic.onScrimDark
                                    )
                                }
                            }
                        }
                    }
                }

                // Title, Quality Telemetry & Receiver-Confirmed Track Pills
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = playbackState.currentVideo?.title ?: "No active media",
                            style = tokens.mediaTitle,
                            color = colors.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            playbackState.activeStrategy?.let { StrategyBadge(strategy = it, compact = true) }
                            Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    text = playbackState.playerStatus.displayLabel(),
                                    style = tokens.badgeLabel,
                                    color = colors.secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            if (playbackState.isCustomReceiverConnected) {
                                Surface(color = semantic.successContainer, shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        text = "Custom Receiver",
                                        style = tokens.badgeLabel,
                                        color = semantic.success,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        playbackState.playbackQualityLabel?.let { quality ->
                            Text(
                                text = quality,
                                style = tokens.telemetryCompact,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Live On-Demand Track Conversion Progress Card
                if (liveTrackConversion != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_track_conversion_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, colors.primary)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Converting ${liveTrackConversion.trackTypeLabel.lowercase()}: ${liveTrackConversion.trackTitle}",
                                            style = tokens.statusMessage,
                                            color = colors.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${liveTrackConversion.sourceFormat} → ${liveTrackConversion.targetFormat}",
                                            style = tokens.telemetryCompact,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                    TextButton(
                                        onClick = onCancelLiveConversion,
                                        modifier = Modifier.testTag("cancel_live_conversion_button")
                                    ) {
                                        Icon(Icons.Filled.Cancel, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Cancel", color = colors.error)
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { (liveTrackConversion.progressPercent / 100f).coerceIn(0f, 1f) },
                                    color = colors.primary,
                                    trackColor = colors.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = liveTrackConversion.statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurface
                                    )
                                    liveTrackConversion.estimatedSecondsRemaining?.let { eta ->
                                        Text(
                                            text = Formatters.formatEta(eta),
                                            style = tokens.telemetryCompact,
                                            color = colors.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Seek Bar + Tabular Timestamps (isolated composable)
                item {
                    RemoteSeekSection(
                        positionMs = playbackState.positionMs,
                        durationMs = playbackState.durationMs,
                        onSeekTo = { target ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSeekTo(target)
                        }
                    )
                }

                // Main Transport Deck
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onQueuePrevious()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("remote_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_skip_previous),
                                tint = colors.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSeekRelative(-10_000L)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("remote_rewind_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay10,
                                contentDescription = stringResource(R.string.cd_rewind_10),
                                tint = colors.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(86.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            colors.primary.copy(alpha = 0.24f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        ) {
                            FilledIconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTogglePlayPause()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary
                                ),
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(70.dp)
                                    .testTag("remote_play_pause_button")
                            ) {
                                AnimatedContent(
                                    targetState = playbackState.playerStatus == RemotePlayerStatus.PLAYING,
                                    transitionSpec = {
                                        (fadeIn(tween(140)) + scaleIn(initialScale = 0.8f, animationSpec = tween(140))) togetherWith
                                            (fadeOut(tween(110)) + scaleOut(targetScale = 0.8f, animationSpec = tween(110)))
                                    },
                                    label = "remote_play_pause_morph"
                                ) { isPlaying ->
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.cd_play_pause),
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSeekRelative(30_000L)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("remote_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forward30,
                                contentDescription = stringResource(R.string.cd_forward_30),
                                tint = colors.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onQueueNext()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("remote_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.cd_skip_next),
                                tint = colors.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                // Audio & Language + TV Subtitles Panel Triggers
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { showAudioSheet = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp)
                                        .testTag("remote_audio_language_button")
                                ) {
                                    Icon(Icons.Filled.Audiotrack, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Audio", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text(
                                            text = playbackState.activeAudioLabel?.substringBefore("—")?.trim()
                                                ?: "${playbackState.availableAudioTracks.size} tracks",
                                            style = tokens.toolbarSubtitle,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { subtitleSheetTab = SubtitleSheetTab.TRACKS },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp)
                                        .testTag("remote_subtitles_button")
                                ) {
                                    Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Subtitles", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text(
                                            text = activeSub?.languageDisplay ?: "Off",
                                            style = tokens.toolbarSubtitle,
                                            color = if (activeSub != null) colors.secondary else colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { subtitleSheetTab = SubtitleSheetTab.DELAY },
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("remote_subtitle_delay_button")
                                ) {
                                    Icon(Icons.Filled.Timer, contentDescription = null, tint = colors.primary, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (playbackState.subtitleDelayMs == 0L) "Sub delay" else Formatters.formatDelayMs(playbackState.subtitleDelayMs),
                                        style = tokens.numericMetric,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                OutlinedButton(
                                    onClick = { subtitleSheetTab = SubtitleSheetTab.STYLE },
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("remote_subtitle_style_button")
                                ) {
                                    Icon(Icons.Filled.Style, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Sub styling",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Volume Control Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleMute()
                                },
                                modifier = Modifier.testTag("remote_mute_button")
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isMuted || playbackState.volume == 0f) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = stringResource(R.string.cd_volume_mute),
                                    tint = colors.secondary
                                )
                            }
                            Slider(
                                value = if (playbackState.isMuted) 0f else playbackState.volume,
                                onValueChange = onVolumeChange,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.secondary,
                                    activeTrackColor = colors.secondary,
                                    inactiveTrackColor = colors.surfaceContainerHigh
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("remote_volume_slider")
                            )
                            Text(
                                text = "${(playbackState.volume * 100).toInt()}%",
                                style = tokens.telemetryCompact,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                            )
                        }
                    }
                }

                // Error Card if TV reports playback/network/track issue
                playbackState.lastError?.let { error ->
                    item {
                        CastErrorCard(
                            error = error,
                            onOpenDiagnostics = onOpenDiagnostics
                        )
                    }
                }

                // Cast Queue
                if (playbackState.queue.size > 1) {
                    item {
                        Text(
                            text = "Cast Queue (${playbackState.queue.size})",
                            style = tokens.sectionHeading,
                            color = colors.onBackground
                        )
                    }
                    itemsIndexed(playbackState.queue) { idx, item ->
                        val isCurrent = idx == playbackState.currentQueueIndex
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) {
                                colors.secondaryContainer.copy(alpha = 0.45f)
                            } else {
                                colors.surfaceContainer
                            },
                            border = BorderStroke(
                                width = if (isCurrent) 1.5.dp else 1.dp,
                                color = if (isCurrent) colors.secondary else colors.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    style = tokens.telemetryCompact,
                                    color = colors.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = item.title,
                                    style = if (isCurrent) tokens.trackTitleSelected else MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Disconnect CTA
                item {
                    OutlinedButton(
                        onClick = {
                            onDisconnect()
                            onBack()
                        },
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("remote_disconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LinkOff,
                            contentDescription = null,
                            tint = colors.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Disconnect from ${playbackState.connectedDeviceName ?: "TV"}",
                            color = colors.error
                        )
                    }
                }
            }
        }
    }

    if (showAudioSheet) {
        AudioTrackSelectionSheet(
            tracks = playbackState.availableAudioTracks,
            activeTrackId = playbackState.activeAudioTrackId,
            pendingTrackId = playbackState.pendingAudioTrackId,
            isCustomReceiver = playbackState.isCustomReceiverConnected,
            onSelectTrack = { track ->
                onSwitchAudioTrack(track)
                showAudioSheet = false
            },
            onDismiss = { showAudioSheet = false }
        )
    }

    subtitleSheetTab?.let { initialTab ->
        SubtitleManagementSheet(
            initialTab = initialTab,
            subtitles = playbackState.availableSubtitles,
            activeSubtitleId = playbackState.activeSubtitleTrackId,
            pendingSubtitleId = playbackState.pendingSubtitleTrackId,
            subtitleDelayMs = playbackState.subtitleDelayMs,
            appearance = playbackState.subtitleAppearance,
            onSelectSubtitle = { sub -> onSelectSubtitle(sub) },
            onPickExternalSubtitle = onPickExternalSubtitle,
            onAuthorizeFolder = onAuthorizeSubtitleFolder,
            onAdjustDelay = onAdjustSubtitleDelay,
            onUpdateAppearance = onUpdateSubtitleAppearance,
            onDismiss = { subtitleSheetTab = null }
        )
    }
}

@Composable
private fun RemoteSeekSection(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayPositionMs = if (isScrubbing) {
        (scrubFraction * safeDuration).toLong()
    } else {
        positionMs.coerceIn(0L, safeDuration)
    }
    val sliderValue = if (isScrubbing) {
        scrubFraction
    } else {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    }

    Column {
        Slider(
            value = sliderValue,
            onValueChange = { fraction ->
                isScrubbing = true
                scrubFraction = fraction
            },
            onValueChangeFinished = {
                val targetMs = (scrubFraction * safeDuration).toLong()
                isScrubbing = false
                onSeekTo(targetMs)
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remote_seek_slider")
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = Formatters.formatDuration(displayPositionMs),
                style = tokens.playbackTimestamp,
                color = colors.onSurface
            )
            Text(
                text = Formatters.formatDuration(durationMs),
                style = tokens.playbackTimestamp,
                color = colors.onSurfaceVariant
            )
        }
    }
}
