package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CompatibilityReport
import com.example.core.common.Formatters
import com.example.core.common.IssueSeverity
import com.example.core.common.ReceiverProfile
import com.example.core.common.VideoItem
import com.example.ui.components.MetaTag
import com.example.ui.components.StrategyBadge
import com.example.ui.theme.AppThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityInfoScreen(
    video: VideoItem?,
    report: CompatibilityReport?,
    defaultProfile: ReceiverProfile,
    isInspecting: Boolean,
    onInspectForProfile: (VideoItem, ReceiverProfile) -> Unit,
    onSelectAudioTrack: (VideoItem, Int, ReceiverProfile) -> Unit,
    onBack: () -> Unit
) {
    var selectedProfile by remember(defaultProfile) { mutableStateOf(defaultProfile) }
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    LaunchedEffect(video?.id, selectedProfile) {
        if (video != null) {
            onInspectForProfile(video, selectedProfile)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Playback Compatibility", style = tokens.toolbarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("compat_back_button")) {
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
                    .widthIn(max = 820.dp)
                    .testTag("compatibility_info_screen"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (video != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = video.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.onBackground
                            )
                            Text(
                                text = "Target receiver profile",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReceiverProfile.entries.forEach { profile ->
                                    FilterChip(
                                        selected = selectedProfile == profile,
                                        onClick = { selectedProfile = profile },
                                        label = {
                                            Text(
                                                if (profile == ReceiverProfile.ULTRA_4K_HDR) "4K HDR / Google TV"
                                                else "1080p Standard"
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = colors.primaryContainer,
                                            selectedLabelColor = colors.primary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (isInspecting || report == null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = colors.primary)
                        }
                    }
                } else {
                    // Decision Banner
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StrategyBadge(strategy = report.strategy)
                                    MetaTag(text = report.recommendedBackend.label, accent = colors.secondary)
                                }
                                Text(
                                    text = report.userHeadline,
                                    style = tokens.sectionHeading,
                                    color = colors.primary
                                )
                                Text(
                                    text = report.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface
                                )
                                CompatKv("Output Video", "${report.outputVideoCodec} · ${report.outputHdrFormat.label}")
                                CompatKv("Output Audio", report.outputAudioCodec)
                                CompatKv("Output Container", report.outputMimeType)
                                report.audioConversionPlan?.downmixDescription?.let { downmix ->
                                    CompatKv("Channel Downmix", downmix)
                                }
                            }
                        }
                    }

                    // Independent Track Verdict Pills
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TrackStatusChip("Container", report.containerCompatible, Modifier.weight(1f))
                            TrackStatusChip("Video", report.videoCompatible, Modifier.weight(1f))
                            TrackStatusChip("Audio", report.audioCompatible, Modifier.weight(1f))
                        }
                    }

                    // Audio Tracks Selector
                    if (video != null && report.technicalInfo.audioTracks.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                                border = BorderStroke(1.dp, colors.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Detected Audio Tracks (${report.technicalInfo.audioTracks.size})",
                                        style = tokens.sectionHeading,
                                        color = colors.onSurface
                                    )
                                    report.technicalInfo.audioTracks.forEach { track ->
                                        val isSelected = report.selectedAudioTrack?.trackIndex == track.trackIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onSelectAudioTrack(video, track.trackIndex, selectedProfile)
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = {
                                                    onSelectAudioTrack(video, track.trackIndex, selectedProfile)
                                                },
                                                colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${track.languageDisplay} · ${track.codecLabel} · ${track.channelLayout}",
                                                    style = if (isSelected) tokens.trackTitleSelected else tokens.trackTitle,
                                                    color = if (isSelected) colors.primary else colors.onSurface
                                                )
                                                Text(
                                                    text = "Delay ${track.audioDelayMs} ms · HW decoder: ${if (track.hardwareDecoderAvailable) "Yes" else "FFmpeg"}",
                                                    style = tokens.telemetryCompact,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Findings
                    item {
                        Text(
                            text = if (report.issues.isEmpty()) "All tracks verified" else "Compatibility findings (${report.issues.size})",
                            style = tokens.sectionHeading,
                            color = colors.onBackground
                        )
                    }

                    if (report.issues.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                                border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = semantic.success
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Container, video codec, audio codec, channel layout, resolution, and dynamic range are natively supported by ${report.receiverProfile.displayName}.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface
                                    )
                                }
                            }
                        }
                    } else {
                        items(report.issues) { issue ->
                            val (icon, tint) = when (issue.severity) {
                                IssueSeverity.INFO -> Icons.Filled.Info to colors.secondary
                                IssueSeverity.WARNING -> Icons.Filled.Warning to semantic.warning
                                IssueSeverity.BLOCKING -> Icons.Filled.Error to colors.error
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                                border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = issue.component,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = tint
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = issue.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Raw Technical Table
                    item {
                        val t = report.technicalInfo
                        val v = t.primaryVideoTrack
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Extracted Track Metadata",
                                    style = tokens.sectionHeading,
                                    color = colors.onSurface
                                )
                                CompatKv("Container / MIME", "${t.containerFormat} (${t.mimeType})")
                                CompatKv("Video Stream", "${t.videoCodecLabel} (${t.videoCodecMime ?: "none"})")
                                CompatKv("Profile / Level", listOfNotNull(t.videoProfile, t.videoLevel).joinToString(" ").ifBlank { "Standard" })
                                CompatKv("Dimensions", "${t.width} × ${t.height} (${v?.aspectRatio ?: "N/A"})")
                                CompatKv("Pixel / Color", listOfNotNull(v?.pixelFormat, v?.colorSpace).joinToString(" · ").ifBlank { "Standard" })
                                CompatKv("Scan Mode", if (v?.isInterlaced == true) "Interlaced" else "Progressive")
                                CompatKv("Frame Rate", t.frameRate?.let { String.format("%.2f fps", it) } ?: "Unknown")
                                CompatKv("Bitrate", Formatters.formatBitrate(t.bitrateBps))
                                CompatKv("HDR Transfer", t.hdrFormat.label)
                                CompatKv("Subtitles", "${t.subtitleTrackCount} track(s)")
                                CompatKv("Duration", Formatters.formatDuration(t.durationMs))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackStatusChip(label: String, compatible: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tint = if (compatible) semantic.success else semantic.warning
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (compatible) "Native" else "Convert",
                style = MaterialTheme.typography.labelMedium,
                color = tint
            )
        }
    }
}

@Composable
private fun CompatKv(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = AppThemeTokens.typography.telemetryCompact,
            color = colors.onSurface
        )
    }
}
