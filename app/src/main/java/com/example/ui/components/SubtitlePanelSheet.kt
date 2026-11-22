package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.common.Formatters
import com.example.core.common.SubtitleAppearanceSettings
import com.example.core.common.SubtitleConversionStatus
import com.example.core.common.SubtitleEdgeType
import com.example.core.common.SubtitleFontFamily
import com.example.core.common.SubtitleSourceType
import com.example.core.common.SubtitleTextAlign
import com.example.core.common.SubtitleTrackInfo
import com.example.ui.theme.AppThemeTokens

enum class SubtitleSheetTab(val title: String) {
    TRACKS("Subtitles"),
    DELAY("Sync & Delay"),
    STYLE("TV Styling")
}

/**
 * Dedicated Subtitle Management modal bottom sheet with 3 tabs:
 * 1. Subtitles: Off, Embedded, External (SAF + Picked), Forced/SDH/Format/Conversion badges
 * 2. Sync & Delay: Millisecond delay slider & precision +/-100ms, +/-500ms steppers
 * 3. TV Styling: Font size, family, foreground/background color, opacity, edge outline/shadow, alignment
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleManagementSheet(
    initialTab: SubtitleSheetTab = SubtitleSheetTab.TRACKS,
    subtitles: List<SubtitleTrackInfo>,
    activeSubtitleId: String?,
    pendingSubtitleId: String?,
    subtitleDelayMs: Long,
    appearance: SubtitleAppearanceSettings,
    onSelectSubtitle: (SubtitleTrackInfo?) -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onAuthorizeFolder: () -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onUpdateAppearance: (SubtitleAppearanceSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTabIdx by remember(initialTab) { mutableIntStateOf(initialTab.ordinal) }
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = Modifier.testTag("subtitle_management_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ClosedCaption,
                    contentDescription = null,
                    tint = colors.secondary
                )
                Column {
                    Text(
                        text = "TV Subtitles & Captions",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Rendered directly on TV via Cast TextTrack",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            TabRow(
                selectedTabIndex = selectedTabIdx,
                containerColor = colors.surface,
                contentColor = colors.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIdx]),
                        color = colors.primary
                    )
                }
            ) {
                SubtitleSheetTab.entries.forEachIndexed { idx, tab ->
                    Tab(
                        selected = selectedTabIdx == idx,
                        onClick = { selectedTabIdx = idx },
                        text = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag("subtitle_tab_${tab.name.lowercase()}")
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (SubtitleSheetTab.entries[selectedTabIdx]) {
                SubtitleSheetTab.TRACKS -> SubtitleTracksTabContent(
                    subtitles = subtitles,
                    activeSubtitleId = activeSubtitleId,
                    pendingSubtitleId = pendingSubtitleId,
                    onSelectSubtitle = onSelectSubtitle,
                    onPickExternalSubtitle = onPickExternalSubtitle,
                    onAuthorizeFolder = onAuthorizeFolder
                )

                SubtitleSheetTab.DELAY -> SubtitleDelayTabContent(
                    delayMs = subtitleDelayMs,
                    onAdjustDelay = onAdjustDelay
                )

                SubtitleSheetTab.STYLE -> SubtitleStyleTabContent(
                    appearance = appearance,
                    onUpdateAppearance = onUpdateAppearance
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleTracksTabContent(
    subtitles: List<SubtitleTrackInfo>,
    activeSubtitleId: String?,
    pendingSubtitleId: String?,
    onSelectSubtitle: (SubtitleTrackInfo?) -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onAuthorizeFolder: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Off option
        item {
            val isOff = activeSubtitleId == null && pendingSubtitleId == null
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isOff) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainer,
                border = BorderStroke(if (isOff) 1.5.dp else 1.dp, if (isOff) colors.primary else colors.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSubtitle(null) }
                    .testTag("subtitle_option_off")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ClosedCaptionDisabled,
                        contentDescription = null,
                        tint = if (isOff) colors.primary else colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Off",
                        style = if (isOff) tokens.trackTitleSelected else tokens.trackTitle,
                        color = if (isOff) colors.primary else colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isOff) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Subtitles off",
                            tint = colors.primary
                        )
                    }
                }
            }
        }

        // Action row: Pick external subtitle or authorize movie folder via SAF
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickExternalSubtitle,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_external_subtitle_button")
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load file", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onAuthorizeFolder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("authorize_subtitle_folder_button")
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (subtitles.isEmpty()) {
            item {
                Text(
                    text = "No embedded or external subtitles found. Load a .srt, .ass, or .vtt file or scan the video's folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(subtitles, key = { it.trackId }) { track ->
                val isActive = track.trackId == activeSubtitleId
                val isPending = track.trackId == pendingSubtitleId

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isActive -> colors.secondaryContainer.copy(alpha = 0.45f)
                        isPending -> colors.primaryContainer.copy(alpha = 0.35f)
                        else -> colors.surfaceContainer
                    },
                    border = BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) colors.secondary else colors.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSubtitle(track) }
                        .testTag("subtitle_row_${track.castTrackId}")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${track.languageDisplay} · ${track.title}",
                                style = if (isActive) tokens.trackTitleSelected else tokens.trackTitle,
                                color = if (isActive) colors.secondary else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TrackBadgePill(
                                    text = if (track.sourceType == SubtitleSourceType.EMBEDDED) "Embedded" else "External",
                                    containerColor = colors.surface,
                                    contentColor = colors.secondary
                                )
                                TrackBadgePill(
                                    text = track.format.displayLabel,
                                    containerColor = colors.surface,
                                    contentColor = colors.onSurfaceVariant
                                )
                                if (track.isForced) {
                                    TrackBadgePill(
                                        text = "Forced",
                                        containerColor = colors.primaryContainer,
                                        contentColor = colors.primary
                                    )
                                }
                                if (track.isSdh) {
                                    TrackBadgePill(
                                        text = "SDH",
                                        containerColor = colors.secondaryContainer,
                                        contentColor = colors.secondary
                                    )
                                }
                                val (statusBg, statusFg) = when (track.conversionStatus) {
                                    SubtitleConversionStatus.READY -> semantic.successContainer to semantic.success
                                    SubtitleConversionStatus.REQUIRES_OCR_REVIEW -> colors.primaryContainer to colors.primary
                                    SubtitleConversionStatus.FAILED -> colors.errorContainer to colors.error
                                    else -> colors.surface to colors.onSurfaceVariant
                                }
                                TrackBadgePill(
                                    text = if (track.conversionStatus == SubtitleConversionStatus.IDLE) {
                                        track.conversionStrategy.label
                                    } else {
                                        track.conversionStatus.label
                                    },
                                    containerColor = statusBg,
                                    contentColor = statusFg
                                )
                            }

                            track.ocrReviewNotes?.let { note ->
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        when {
                            isPending -> CircularProgressIndicator(
                                color = colors.primary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                            isActive -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Active subtitle confirmed by TV",
                                tint = colors.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleDelayTabContent(
    delayMs: Long,
    onAdjustDelay: (Long) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceContainer,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Subtitle synchronization offset",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = Formatters.formatDelayMs(delayMs),
                    style = tokens.numericHeadline,
                    color = if (delayMs == 0L) semantic.success else colors.primary
                )
                Text(
                    text = "Negative values show captions earlier; positive values delay them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Slider(
            value = delayMs.coerceIn(-5000L, 5000L).toFloat(),
            onValueChange = { onAdjustDelay((it / 50f).toLong() * 50L) },
            valueRange = -5000f..5000f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("subtitle_delay_slider")
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(-500L to "-500 ms", -100L to "-100 ms", 0L to "Reset", 100L to "+100 ms", 500L to "+500 ms")
                .forEach { (delta, label) ->
                    OutlinedButton(
                        onClick = {
                            if (delta == 0L) onAdjustDelay(0L) else onAdjustDelay(delayMs + delta)
                        },
                        modifier = Modifier.testTag("subtitle_delay_btn_$delta")
                    ) {
                        Text(label, style = tokens.telemetryCompact)
                    }
                }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleStyleTabContent(
    appearance: SubtitleAppearanceSettings,
    onUpdateAppearance: (SubtitleAppearanceSettings) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography
    val fgColor = Color(appearance.foregroundColorArgb)
    val bgBase = Color(appearance.backgroundColorArgb)
    val bgColor = bgBase.copy(alpha = appearance.backgroundOpacity)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live TV Preview Swatch (always dark cinema canvas representing the TV screen)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(semantic.scrimDark),
            contentAlignment = when (appearance.textAlign) {
                SubtitleTextAlign.LEFT -> Alignment.BottomStart
                SubtitleTextAlign.CENTER -> Alignment.BottomCenter
                SubtitleTextAlign.RIGHT -> Alignment.BottomEnd
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = (appearance.bottomOffsetPercent.coerceIn(4, 24)).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "TV Preview · नमस्ते · বাংলা · 日本語",
                    color = fgColor,
                    fontSize = (15f * appearance.fontScale).sp,
                    fontFamily = when (appearance.fontFamily) {
                        SubtitleFontFamily.SANS_SERIF -> FontFamily.SansSerif
                        SubtitleFontFamily.MONOSPACED_SANS -> FontFamily.Monospace
                        SubtitleFontFamily.SERIF -> FontFamily.Serif
                        SubtitleFontFamily.CASUAL -> FontFamily.Cursive
                    },
                    textAlign = when (appearance.textAlign) {
                        SubtitleTextAlign.LEFT -> TextAlign.Start
                        SubtitleTextAlign.CENTER -> TextAlign.Center
                        SubtitleTextAlign.RIGHT -> TextAlign.End
                    }
                )
            }
        }

        // Font Scale
        Column {
            Text(
                text = "Font size · ${(appearance.fontScale * 100).toInt()}%",
                style = tokens.numericMetric,
                color = colors.onSurface
            )
            Slider(
                value = appearance.fontScale,
                onValueChange = { onUpdateAppearance(appearance.copy(fontScale = it)) },
                valueRange = 0.75f..1.75f,
                colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                modifier = Modifier.testTag("subtitle_font_scale_slider")
            )
        }

        // Background Opacity
        Column {
            Text(
                text = "Background opacity · ${(appearance.backgroundOpacity * 100).toInt()}%",
                style = tokens.numericMetric,
                color = colors.onSurface
            )
            Slider(
                value = appearance.backgroundOpacity,
                onValueChange = { onUpdateAppearance(appearance.copy(backgroundOpacity = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = colors.secondary, activeTrackColor = colors.secondary),
                modifier = Modifier.testTag("subtitle_bg_opacity_slider")
            )
        }

        // Font Color
        Text("Text color", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                0xFFFFFFFFL to "White",
                0xFFF4B740L to "Gold",
                0xFF22D3EEL to "Cyan",
                0xFFE2E8F0L to "Silver"
            ).forEach { (argb, label) ->
                val selected = appearance.foregroundColorArgb == argb
                Surface(
                    shape = CircleShape,
                    color = Color(argb),
                    border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onUpdateAppearance(appearance.copy(foregroundColorArgb = argb)) }
                        .testTag("subtitle_fg_color_${label.lowercase()}")
                ) {}
            }
        }

        // Font Family
        Text("Font family", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleFontFamily.entries.forEach { family ->
                FilterChip(
                    selected = appearance.fontFamily == family,
                    onClick = { onUpdateAppearance(appearance.copy(fontFamily = family)) },
                    label = { Text(family.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primaryContainer,
                        selectedLabelColor = colors.primary
                    )
                )
            }
        }

        // Edge Outline / Shadow
        Text("Outline & shadow", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleEdgeType.entries.forEach { edge ->
                FilterChip(
                    selected = appearance.edgeType == edge,
                    onClick = { onUpdateAppearance(appearance.copy(edgeType = edge)) },
                    label = { Text(edge.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.secondaryContainer,
                        selectedLabelColor = colors.secondary
                    )
                )
            }
        }

        // Alignment
        Text("Alignment", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleTextAlign.entries.forEach { align ->
                FilterChip(
                    selected = appearance.textAlign == align,
                    onClick = { onUpdateAppearance(appearance.copy(textAlign = align)) },
                    label = { Text(align.label) }
                )
            }
        }

        HorizontalDivider(color = colors.outlineVariant)
        Spacer(Modifier.height(4.dp))
    }
}
