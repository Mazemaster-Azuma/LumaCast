package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CastPipelineState
import com.example.core.common.ConversionTelemetry
import com.example.core.common.Formatters
import com.example.core.common.VideoItem
import com.example.ui.components.CastErrorCard
import com.example.ui.components.StrategyBadge
import com.example.ui.theme.AppThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastingProgressScreen(
    pipelineState: CastPipelineState,
    onCancel: () -> Unit,
    onRetry: (VideoItem) -> Unit,
    onOpenRemote: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(pipelineState) {
        if (pipelineState is CastPipelineState.Ready) {
            onOpenRemote()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Preparing Cast", style = tokens.toolbarTitle) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("casting_progress_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (pipelineState) {
                    is CastPipelineState.Failed -> {
                        item {
                            CastErrorCard(
                                error = pipelineState.error,
                                onRetry = pipelineState.video?.let { vid -> { onRetry(vid) } },
                                onOpenDiagnostics = onOpenDiagnostics
                            )
                        }
                    }

                    else -> {
                        val activeStep = when (pipelineState) {
                            is CastPipelineState.Inspecting -> 0
                            is CastPipelineState.PreparingStream -> 1
                            is CastPipelineState.ValidatingOutput -> 2
                            is CastPipelineState.StartingServer -> 3
                            is CastPipelineState.LoadingOnTv -> 4
                            is CastPipelineState.RetryingFallback -> 1
                            is CastPipelineState.Ready -> 5
                            else -> 0
                        }

                        val telemetry: ConversionTelemetry? = when (pipelineState) {
                            is CastPipelineState.PreparingStream -> pipelineState.telemetry
                            is CastPipelineState.ValidatingOutput -> pipelineState.telemetry
                            is CastPipelineState.StartingServer -> pipelineState.telemetry
                            is CastPipelineState.LoadingOnTv -> pipelineState.telemetry
                            else -> null
                        }

                        val title = when (pipelineState) {
                            is CastPipelineState.Inspecting -> pipelineState.video.displayName
                            is CastPipelineState.PreparingStream -> pipelineState.video.displayName
                            is CastPipelineState.ValidatingOutput -> pipelineState.video.displayName
                            is CastPipelineState.StartingServer -> pipelineState.video.displayName
                            is CastPipelineState.LoadingOnTv -> pipelineState.video.displayName
                            is CastPipelineState.RetryingFallback -> pipelineState.video.displayName
                            is CastPipelineState.Ready -> pipelineState.video.displayName
                            else -> "Preparing media"
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                                border = BorderStroke(1.dp, colors.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = colors.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (telemetry != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            StrategyBadge(strategy = telemetry.strategy)
                                            Surface(
                                                color = colors.secondaryContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = telemetry.backend.label,
                                                    style = tokens.badgeLabel,
                                                    color = colors.secondary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = telemetry.headlineMessage,
                                            style = tokens.statusMessage,
                                            color = colors.primary
                                        )

                                        Text(
                                            text = telemetry.detectedProblem,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    } else if (pipelineState is CastPipelineState.RetryingFallback) {
                                        Text(
                                            text = pipelineState.reason,
                                            style = tokens.statusMessage,
                                            color = colors.primary
                                        )
                                    }

                                    // Live Progress + Tabular ETA
                                    if (pipelineState is CastPipelineState.PreparingStream) {
                                        val animatedProgress by animateFloatAsState(
                                            targetValue = (pipelineState.progressPercent / 100f).coerceIn(0f, 1f),
                                            animationSpec = tween(durationMillis = 220),
                                            label = "casting_prep_progress"
                                        )
                                        LinearProgressIndicator(
                                            progress = { animatedProgress },
                                            color = colors.primary,
                                            trackColor = colors.surfaceContainerHigh,
                                            strokeCap = StrokeCap.Round,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${pipelineState.progressPercent}% complete",
                                                style = tokens.numericMetric,
                                                color = colors.primary
                                            )
                                            Text(
                                                text = Formatters.formatEta(telemetry?.estimatedSecondsRemaining),
                                                style = tokens.telemetryCompact,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        LinearProgressIndicator(
                                            color = colors.secondary,
                                            trackColor = colors.surfaceContainerHigh,
                                            strokeCap = StrokeCap.Round,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                        )
                                    }

                                    // Source -> Output Codec & Resolution comparison
                                    if (telemetry != null) {
                                        HorizontalDivider(color = colors.outlineVariant)
                                        CodecTransitionRow(
                                            label = "Video",
                                            original = "${telemetry.originalVideoCodec} · ${telemetry.originalResolution}",
                                            output = "${telemetry.outputVideoCodec} · ${telemetry.outputResolution}",
                                            preserved = telemetry.strategy.preservesVideoBitstream
                                        )
                                        CodecTransitionRow(
                                            label = "Audio",
                                            original = telemetry.originalAudioCodec,
                                            output = telemetry.outputAudioCodec,
                                            preserved = telemetry.strategy.preservesAudioBitstream
                                        )
                                    }

                                    HorizontalDivider(color = colors.outlineVariant)

                                    PipelineStepRow("Checking receiver compatibility", activeStep, 0)
                                    PipelineStepRow("Preparing media tracks", activeStep, 1)
                                    PipelineStepRow("Validating output stream", activeStep, 2)
                                    PipelineStepRow("Starting local media server", activeStep, 3)
                                    PipelineStepRow("Connecting to TV", activeStep, 4)

                                    if (pipelineState is CastPipelineState.LoadingOnTv) {
                                        Text(
                                            text = "Streaming to ${pipelineState.deviceName}",
                                            style = tokens.connectionStatus,
                                            color = colors.secondary
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedButton(
                                onClick = {
                                    onCancel()
                                    onBack()
                                },
                                border = BorderStroke(1.dp, colors.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("cancel_casting_button")
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodecTransitionRow(
    label: String,
    original: String,
    output: String,
    preserved: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            Text(
                text = if (preserved) "Original preserved" else "Converted",
                style = tokens.badgeLabel,
                color = if (preserved) semantic.success else colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = original,
                style = tokens.telemetryCompact,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(16.dp)
            )
            Text(
                text = output,
                style = tokens.telemetryCompact,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PipelineStepRow(label: String, activeStep: Int, stepIndex: Int) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            activeStep > stepIndex -> {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = semantic.success,
                    modifier = Modifier.size(18.dp)
                )
            }

            activeStep == stepIndex -> {
                CircularProgressIndicator(
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }

            else -> {
                Icon(
                    imageVector = Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (activeStep >= stepIndex) {
                colors.onSurface
            } else {
                colors.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )
    }
}
