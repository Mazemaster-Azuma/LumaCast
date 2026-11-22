package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.AudioTrackInfo
import com.example.core.common.AudioTrackRole
import com.example.core.common.Formatters
import com.example.core.common.TrackOrigin
import com.example.ui.theme.AppThemeTokens

/**
 * Dedicated Audio & Language Selection bottom sheet for active Chromecast playback.
 * Clearly distinguishes Original vs Converted tracks, receiver-confirmed active track,
 * default track, commentary/descriptive roles, and bitrate/channel layout.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioTrackSelectionSheet(
    tracks: List<AudioTrackInfo>,
    activeTrackId: String?,
    pendingTrackId: String?,
    isCustomReceiver: Boolean,
    onSelectTrack: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = Modifier.testTag("audio_selection_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Audiotrack,
                    contentDescription = null,
                    tint = colors.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audio & Language",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (isCustomReceiver) {
                            "Live track switching via Custom Receiver"
                        } else {
                            "Preserves current playback position"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            if (tracks.isEmpty()) {
                Text(
                    text = "No embedded audio tracks found in this video.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tracks, key = { it.uniqueId }) { track ->
                        val isConfirmedActive = track.uniqueId == activeTrackId
                        val isPending = track.uniqueId == pendingTrackId

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = when {
                                isConfirmedActive -> colors.primaryContainer.copy(alpha = 0.45f)
                                isPending -> colors.secondaryContainer.copy(alpha = 0.35f)
                                else -> colors.surfaceContainer
                            },
                            border = BorderStroke(
                                width = if (isConfirmedActive) 1.5.dp else 1.dp,
                                color = when {
                                    isConfirmedActive -> colors.primary
                                    isPending -> colors.secondary
                                    else -> colors.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTrack(track) }
                                .testTag("audio_track_row_${track.audioStreamOrdinal}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = track.formattedSummaryLine,
                                        style = if (isConfirmedActive) tokens.trackTitleSelected else tokens.trackTitle,
                                        color = if (isConfirmedActive) colors.primary else colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (!track.title.isNullOrBlank()) {
                                        Text(
                                            text = track.title,
                                            style = tokens.trackMetadata,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        TrackBadgePill(
                                            text = if (track.origin == TrackOrigin.CONVERTED) "Converted (AAC)" else "Original",
                                            containerColor = if (track.origin == TrackOrigin.CONVERTED) colors.secondaryContainer else colors.surface,
                                            contentColor = if (track.origin == TrackOrigin.CONVERTED) colors.secondary else colors.onSurfaceVariant
                                        )

                                        if (track.isDefault) {
                                            TrackBadgePill(
                                                text = "Default",
                                                containerColor = colors.surface,
                                                contentColor = colors.primary
                                            )
                                        }

                                        if (track.role != AudioTrackRole.MAIN) {
                                            TrackBadgePill(
                                                text = track.role.label,
                                                containerColor = colors.surface,
                                                contentColor = colors.secondary
                                            )
                                        }

                                        TrackBadgePill(
                                            text = if (track.isCompatibleWithReceiver) "TV compatible" else "Convert to AAC",
                                            containerColor = if (track.isCompatibleWithReceiver) semantic.successContainer else colors.primaryContainer,
                                            contentColor = if (track.isCompatibleWithReceiver) semantic.success else colors.primary
                                        )

                                        track.bitrateBps?.takeIf { it > 0L }?.let { bps ->
                                            Text(
                                                text = Formatters.formatBitrate(bps),
                                                style = tokens.telemetryCompact,
                                                color = colors.onSurfaceVariant,
                                                modifier = Modifier.align(Alignment.CenterVertically)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                when {
                                    isPending -> {
                                        CircularProgressIndicator(
                                            color = colors.secondary,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    isConfirmedActive -> {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = "Active audio track confirmed by TV",
                                            tint = colors.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            imageVector = Icons.Filled.GraphicEq,
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun TrackBadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = AppThemeTokens.typography.badgeLabel,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
