package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.CompatibilityReport
import com.example.core.common.Formatters
import com.example.core.common.PlaybackStrategy
import com.example.core.common.VideoItem
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.components.MetaTag
import com.example.ui.components.StrategyBadge
import com.example.ui.components.VideoThumbnailImage
import com.example.ui.theme.AppThemeTokens
import com.example.ui.theme.CastCyan
import com.example.ui.theme.CinemaGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailsScreen(
    video: VideoItem?,
    report: CompatibilityReport?,
    isInspecting: Boolean,
    connectionState: CastConnectionState,
    connectedDeviceName: String?,
    thumbnailLoader: VideoThumbnailLoader? = null,
    onBack: () -> Unit,
    onInspect: (VideoItem) -> Unit,
    onSelectAudioTrack: (VideoItem, Int) -> Unit,
    onCastClick: (VideoItem) -> Unit,
    onOpenDevices: () -> Unit,
    onAddToQueue: (VideoItem) -> Unit,
    onOpenCompatibilityInfo: (VideoItem) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(video?.id) {
        if (video != null && report == null && !isInspecting) {
            onInspect(video)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Video Details", style = tokens.toolbarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("details_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.surfaceContainer,
                    titleContentColor = colors.onBackground
                )
            )
        }
    ) { innerPadding ->
        if (video == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Video not found in library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 820.dp)
                    .testTag("video_details_screen"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Video Frame
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        VideoThumbnailImage(
                            video = video,
                            thumbnailLoader = thumbnailLoader,
                            targetWidthPx = 640,
                            targetHeightPx = 360,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.5f to Color.Transparent,
                                        1f to semantic.scrimDark.copy(alpha = 0.88f)
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MetaTag(text = video.resolutionLabel, accent = CinemaGold)
                            MetaTag(text = video.containerLabel, accent = CastCyan)
                            if (video.durationMs > 0L) {
                                MetaTag(
                                    text = Formatters.formatDuration(video.durationMs),
                                    accent = Color.White
                                )
                            }
                        }
                    }
                }

                // Title & Metadata
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = video.title,
                            style = tokens.mediaTitle,
                            color = colors.onBackground
                        )
                        Text(
                            text = "${video.displayName} · ${video.folderName} · ${Formatters.formatFileSize(video.sizeBytes)}",
                            style = tokens.videoMetadata,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // Primary Cast CTA
                item {
                    val isConnected = connectionState == CastConnectionState.CONNECTED
                    val blocked = report?.strategy == PlaybackStrategy.UNSUPPORTED

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (isConnected) onCastClick(video) else onOpenDevices()
                            },
                            enabled = !blocked,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("details_cast_button")
                        ) {
                            Icon(Icons.Filled.Cast, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    blocked -> "Incompatible format"
                                    isConnected -> "Cast to ${connectedDeviceName ?: "TV"}"
                                    else -> "Select Chromecast TV"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenCompatibilityInfo(video) },
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("details_compatibility_button")
                            ) {
                                Icon(
                                    Icons.Filled.FactCheck,
                                    contentDescription = null,
                                    tint = colors.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_inspect_compatibility), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            if (isConnected) {
                                OutlinedButton(
                                    onClick = { onAddToQueue(video) },
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("details_queue_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_add_to_queue), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // Live Compatibility Strategy Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Compatibility & Conversion Plan",
                                style = tokens.sectionHeading,
                                color = colors.onSurface
                            )
                            if (isInspecting || report == null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = colors.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Checking receiver compatibility",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StrategyBadge(strategy = report.strategy)
                                    MetaTag(text = report.recommendedBackend.label, accent = colors.secondary)
                                }
                                Text(
                                    text = report.userHeadline,
                                    style = tokens.statusMessage,
                                    color = if (report.strategy == PlaybackStrategy.DIRECT_CAST) {
                                        semantic.success
                                    } else {
                                        colors.primary
                                    }
                                )
                                Text(
                                    text = report.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Audio Track & Language Selector
                if (report != null && report.technicalInfo.audioTracks.isNotEmpty()) {
                    val tracks = report.technicalInfo.audioTracks
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Audiotrack,
                                        contentDescription = null,
                                        tint = colors.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Audio Tracks (${tracks.size})",
                                        style = tokens.sectionHeading,
                                        color = colors.onSurface
                                    )
                                }
                                tracks.forEach { track ->
                                    val isSelected = report.selectedAudioTrack?.trackIndex == track.trackIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectAudioTrack(video, track.trackIndex)
                                            }
                                            .padding(vertical = 4.dp)
                                            .testTag("audio_track_${track.trackIndex}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectAudioTrack(video, track.trackIndex)
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = buildString {
                                                    append(track.languageDisplay)
                                                    track.title?.let { append(" · $it") }
                                                    if (track.isDefault) append(" (Default)")
                                                },
                                                style = if (isSelected) tokens.trackTitleSelected else tokens.trackTitle,
                                                color = if (isSelected) colors.primary else colors.onSurface
                                            )
                                            Text(
                                                text = buildString {
                                                    append(track.codecLabel)
                                                    append(" · ${track.channelLayout}")
                                                    track.sampleRateHz?.let { append(" · ${it / 1000} kHz") }
                                                    track.bitrateBps?.let { append(" · ${Formatters.formatBitrate(it)}") }
                                                },
                                                style = tokens.telemetryCompact,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                report.audioConversionPlan?.let { plan ->
                                    Text(
                                        text = if (plan.preserveOriginal) {
                                            "Audio output: Original ${plan.targetCodecLabel} (${plan.targetChannelLayout}) preserved"
                                        } else {
                                            "Audio output: ${plan.targetCodecLabel} · ${plan.targetChannelLayout} · ${plan.targetSampleRateHz / 1000} kHz · ${plan.targetBitrateBps / 1000} kbps" +
                                                (plan.downmixDescription?.let { " ($it)" } ?: "")
                                        },
                                        style = tokens.telemetryCompact,
                                        color = colors.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Technical Spec Sheet
                if (report != null) {
                    val tech = report.technicalInfo
                    val vTrack = tech.primaryVideoTrack
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Media Stream Info",
                                    style = tokens.sectionHeading,
                                    color = colors.onSurface
                                )
                                SpecRow("Container", tech.containerFormat)
                                SpecRow(
                                    "Video Codec",
                                    listOfNotNull(tech.videoCodecLabel, tech.videoProfile, tech.videoLevel).joinToString(" · ")
                                )
                                SpecRow(
                                    "Resolution",
                                    if (tech.width > 0 && tech.height > 0) {
                                        "${tech.width} × ${tech.height} (${vTrack?.aspectRatio ?: video.resolutionLabel})"
                                    } else "Unknown"
                                )
                                SpecRow(
                                    "Frame Rate",
                                    tech.frameRate?.let { String.format("%.2f fps", it) } ?: "Unknown"
                                )
                                SpecRow("Bitrate", Formatters.formatBitrate(tech.bitrateBps))
                                SpecRow("Color / HDR", listOfNotNull(tech.hdrFormat.label, vTrack?.colorSpace, vTrack?.pixelFormat).joinToString(" · "))
                                SpecRow("HW Video Decoder", if (vTrack?.hardwareDecoderAvailable == true) "Available" else "Software / FFmpeg")
                                SpecRow(
                                    "Selected Audio",
                                    report.selectedAudioTrack?.let { "${it.codecLabel} · ${it.channelLayout}" } ?: tech.audioCodecLabel
                                )
                                SpecRow("Subtitles", if (tech.subtitleTrackCount > 0) "${tech.subtitleTrackCount} embedded" else "None")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = AppThemeTokens.typography.telemetryCompact,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
    }
}
