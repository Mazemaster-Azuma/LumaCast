package com.example.ui.components

import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.CastError
import com.example.core.common.Formatters
import com.example.core.common.PlaybackStrategy
import com.example.core.common.RemotePlaybackState
import com.example.core.common.RemotePlayerStatus
import com.example.core.common.VideoItem
import com.example.core.media.ThumbnailLoadResult
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.theme.AppThemeTokens
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Formats remote playback states in concise, title-case commercial media copy
 * rather than raw enum names.
 */
fun RemotePlayerStatus.displayLabel(): String = when (this) {
    RemotePlayerStatus.IDLE -> "Ready"
    RemotePlayerStatus.LOADING -> "Loading"
    RemotePlayerStatus.BUFFERING -> "Buffering"
    RemotePlayerStatus.PLAYING -> "Playing"
    RemotePlayerStatus.PAUSED -> "Paused"
    RemotePlayerStatus.ERROR -> "Error"
}

/**
 * Shared animated shimmer brush that adapts cleanly to light and dark surfaces.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "luma_shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    val base = colors.surfaceContainerHigh
    val highlight = colors.surfaceContainerHighest.copy(alpha = 0.92f)
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(progress * 420f, 0f),
        end = Offset(progress * 420f + 280f, 180f)
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    brush: Brush = rememberShimmerBrush()
) {
    Box(modifier = modifier.background(brush))
}

@Composable
fun VideoListSkeleton(
    count: Int = 7,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shimmer = rememberShimmerBrush()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        userScrollEnabled = false,
        modifier = modifier
            .fillMaxSize()
            .testTag("video_library_skeleton_list")
    ) {
        items(count) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShimmerBox(
                        brush = shimmer,
                        modifier = Modifier
                            .width(132.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShimmerBox(
                            brush = shimmer,
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShimmerBox(
                                brush = shimmer,
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            ShimmerBox(
                                brush = shimmer,
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                        ShimmerBox(
                            brush = shimmer,
                            modifier = Modifier
                                .width(88.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoGridSkeleton(
    count: Int = 6,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shimmer = rememberShimmerBrush()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        userScrollEnabled = false,
        modifier = modifier
            .fillMaxSize()
            .testTag("video_library_skeleton_grid")
    ) {
        items(count) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column {
                    ShimmerBox(
                        brush = shimmer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShimmerBox(
                            brush = shimmer,
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        ShimmerBox(
                            brush = shimmer,
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Official Google Cast MediaRouteButton embedded cleanly for Android FragmentActivity,
 * paired with a tactile Cast status chip that opens our full Chromecast discovery screen.
 */
@Composable
fun NativeCastActionPill(
    connectionState: CastConnectionState,
    connectedDeviceName: String?,
    discoveredCount: Int,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == CastConnectionState.CONNECTED
    val colors = MaterialTheme.colorScheme
    val bgColor = if (isConnected) colors.secondaryContainer else colors.surfaceContainerHigh
    val contentColor = if (isConnected) colors.secondary else colors.onSurface
    val borderColor = if (isConnected) colors.secondary.copy(alpha = 0.5f) else colors.outlineVariant

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onOpenDevices)
            .testTag("cast_status_button"),
        shape = RoundedCornerShape(50),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                contentDescription = stringResource(R.string.cd_cast_button),
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = when {
                    isConnected -> connectedDeviceName ?: "Connected"
                    connectionState == CastConnectionState.CONNECTING -> "Connecting…"
                    discoveredCount > 0 -> "$discoveredCount TV${if (discoveredCount > 1) "s" else ""}"
                    else -> "Cast"
                },
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Wraps the native AndroidX MediaRouteButton wired through Google Cast's CastButtonFactory
 * for direct access to the system Cast chooser dialog on physical devices.
 */
@Composable
fun OfficialMediaRouteButton(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasFragmentActivity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .any { it is FragmentActivity }
    }

    if (hasFragmentActivity) {
        AndroidView(
            modifier = modifier
                .size(48.dp)
                .testTag("official_media_route_button"),
            factory = { ctx ->
                MediaRouteButton(ctx).apply {
                    contentDescription = ctx.getString(R.string.cd_cast_button)
                    runCatching {
                        CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
                    }
                }
            }
        )
    }
}

@Composable
fun VideoThumbnailImage(
    video: VideoItem,
    modifier: Modifier = Modifier,
    thumbnailLoader: VideoThumbnailLoader? = null,
    targetWidthPx: Int = VideoThumbnailLoader.DEFAULT_THUMB_WIDTH_PX,
    targetHeightPx: Int = VideoThumbnailLoader.DEFAULT_THUMB_HEIGHT_PX
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val thumbBg = remember(colors.surfaceContainerHigh, colors.surfaceContainerLow) {
        Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerLow))
    }

    if (thumbnailLoader != null) {
        val initialCached = remember(video.id, video.dateModifiedSec, targetWidthPx, targetHeightPx) {
            thumbnailLoader.getCached(video, targetWidthPx, targetHeightPx)
        }
        val initialFailed = remember(video.id, video.dateModifiedSec, targetWidthPx, targetHeightPx) {
            thumbnailLoader.isKnownFailed(video, targetWidthPx, targetHeightPx)
        }
        val bitmapState = remember(video.id, video.dateModifiedSec, targetWidthPx, targetHeightPx) {
            mutableStateOf(initialCached)
        }
        val failedState = remember(video.id, video.dateModifiedSec, targetWidthPx, targetHeightPx) {
            mutableStateOf(initialFailed)
        }

        LaunchedEffect(video.id, video.dateModifiedSec, targetWidthPx, targetHeightPx) {
            if (bitmapState.value == null && !failedState.value) {
                when (val result = thumbnailLoader.loadThumbnail(video, targetWidthPx, targetHeightPx)) {
                    is ThumbnailLoadResult.Success -> {
                        bitmapState.value = result.bitmap
                        failedState.value = false
                    }
                    ThumbnailLoadResult.Failed -> {
                        failedState.value = true
                    }
                }
            }
        }

        val bmp = bitmapState.value
        val validBmp = bmp?.takeIf { !it.isRecycled }
        // Remember the ImageBitmap wrapper so recompositions never re-wrap the native Android Bitmap
        val imageBitmap = remember(validBmp) { validBmp?.asImageBitmap() }
        val revealAlpha by animateFloatAsState(
            targetValue = if (imageBitmap != null) 1f else 0f,
            animationSpec = tween(durationMillis = if (initialCached != null) 0 else 180),
            label = "thumb_fade"
        )

        Box(
            modifier = modifier.background(thumbBg),
            contentAlignment = Alignment.Center
        ) {
            when {
                imageBitmap != null -> {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.cd_video_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(revealAlpha)
                    )
                }
                failedState.value -> {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = stringResource(R.string.cd_video_thumbnail),
                        tint = colors.onSurfaceVariant.copy(alpha = 0.42f),
                        modifier = Modifier.size(24.dp)
                    )
                }
                else -> {
                    ShimmerBox(modifier = Modifier.fillMaxSize())
                }
            }
        }
    } else {
        Box(
            modifier = modifier.background(thumbBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(32.dp)
            )
            val request = remember(video.uri, context) {
                ImageRequest.Builder(context)
                    .data(video.uri)
                    .videoFrameMillis(1500L)
                    .crossfade(180)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.cd_video_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun StrategyBadge(
    strategy: PlaybackStrategy,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val (bg, fg) = when (strategy) {
        PlaybackStrategy.DIRECT_CAST -> semantic.successContainer to semantic.success
        PlaybackStrategy.REMUX -> semantic.warningContainer to semantic.warning
        PlaybackStrategy.AUDIO_ONLY_TRANSCODE -> colors.secondaryContainer to colors.secondary
        PlaybackStrategy.VIDEO_ONLY_TRANSCODE -> semantic.transcodeContainer to semantic.transcode
        PlaybackStrategy.FULL_TRANSCODE -> semantic.transcodeContainer to semantic.transcode
        PlaybackStrategy.CONSERVATIVE_FALLBACK -> semantic.warningContainer to semantic.warning
        PlaybackStrategy.UNSUPPORTED -> colors.errorContainer to colors.error
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.32f))
    ) {
        Text(
            text = if (compact) strategy.shortBadge else strategy.label,
            style = AppThemeTokens.typography.badgeLabel,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VideoGridCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: VideoThumbnailLoader? = null,
    overflowMenu: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("video_card_${video.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            ) {
                VideoThumbnailImage(
                    video = video,
                    thumbnailLoader = thumbnailLoader,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to semantic.scrimDark.copy(alpha = 0.84f)
                            )
                        )
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    color = semantic.scrimDark.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = video.containerLabel,
                        style = tokens.badgeLabel,
                        color = semantic.onScrimDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (video.durationMs > 0L) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        color = Color.Black.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = Formatters.formatDuration(video.durationMs),
                            style = tokens.durationOverlay,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (overflowMenu != null) 2.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = video.displayName,
                        style = tokens.videoFilename,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = video.exactResolutionLabel,
                        style = tokens.videoMetadata,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${video.humanReadableFormat} · ${Formatters.formatFileSize(video.sizeBytes)}",
                        style = tokens.videoMetadata,
                        color = colors.onSurfaceVariant.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (overflowMenu != null) {
                    overflowMenu()
                }
            }
        }
    }
}

@Composable
fun VideoListRow(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: VideoThumbnailLoader? = null,
    overflowMenu: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("video_row_${video.id}"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = if (overflowMenu != null) 2.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT SIDE: Rounded thumbnail with subtle bottom scrim and duration overlay
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                VideoThumbnailImage(
                    video = video,
                    thumbnailLoader = thumbnailLoader,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to semantic.scrimDark.copy(alpha = 0.65f)
                            )
                        )
                )
                if (video.durationMs > 0L) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp),
                        color = Color.Black.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = Formatters.formatDuration(video.durationMs),
                            style = tokens.durationOverlay,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // MIDDLE: Filename (primary), Format + File Size + Original Resolution (secondary, tabular)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = video.displayName,
                    style = tokens.videoFilename,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetaTag(text = video.humanReadableFormat, accent = colors.secondary)
                    if (video.sizeBytes > 0L) {
                        Text(
                            text = Formatters.formatFileSize(video.sizeBytes),
                            style = tokens.videoMetadata,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = video.exactResolutionLabel,
                    style = tokens.videoMetadata,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // RIGHT SIDE: Three-dot overflow menu
            if (overflowMenu != null) {
                overflowMenu()
            }
        }
    }
}

@Composable
fun MetaTag(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = AppThemeTokens.typography.badgeLabel,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MiniCastControllerBar(
    playbackState: RemotePlaybackState,
    onTogglePlayPause: () -> Unit,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: VideoThumbnailLoader? = null
) {
    if (playbackState.connectionState != CastConnectionState.CONNECTED) return
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    val haptics = LocalHapticFeedback.current
    val currentVideo = playbackState.currentVideo
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenRemote)
            .testTag("mini_cast_controller"),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.42f)),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentVideo != null) {
                    Box(
                        modifier = Modifier
                            .width(54.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        VideoThumbnailImage(
                            video = currentVideo,
                            thumbnailLoader = thumbnailLoader,
                            targetWidthPx = 160,
                            targetHeightPx = 90,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tv,
                            contentDescription = null,
                            tint = colors.secondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentVideo?.title ?: "Ready to cast",
                        style = tokens.trackTitleSelected,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playbackState.connectedDeviceName ?: "Chromecast"} · ${playbackState.playerStatus.displayLabel()}",
                        style = tokens.connectionStatus,
                        color = colors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (currentVideo != null) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePlayPause()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mini_play_pause_button")
                    ) {
                        AnimatedContent(
                            targetState = playbackState.playerStatus == RemotePlayerStatus.PLAYING,
                            transitionSpec = {
                                (fadeIn(tween(140)) + scaleIn(initialScale = 0.85f, animationSpec = tween(140))) togetherWith
                                    (fadeOut(tween(110)) + scaleOut(targetScale = 0.85f, animationSpec = tween(110)))
                            },
                            label = "mini_play_pause_icon"
                        ) { isPlaying ->
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = colors.primary
                            )
                        }
                    }
                }
            }

            // Sleek live playback progress bar along the bottom edge
            if (currentVideo != null && playbackState.durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = colors.primary,
                    trackColor = colors.outlineVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                )
            }
        }
    }
}

@Composable
fun CastErrorCard(
    error: CastError,
    onRetry: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = error.title,
                        style = tokens.sectionHeading,
                        color = colors.onSurface
                    )
                    Text(
                        text = error.code,
                        style = tokens.telemetryCompact,
                        color = colors.error
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = 0.92f)
            )
            if (error.recoverySteps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "How to resolve",
                    style = tokens.statusMessage,
                    color = colors.primary
                )
                Spacer(Modifier.height(6.dp))
                error.recoverySteps.forEachIndexed { idx, step ->
                    Text(
                        text = "${idx + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            if (onRetry != null || onOpenDiagnostics != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onRetry != null) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier.testTag("error_retry_button")
                        ) {
                            Text(text = stringResource(R.string.action_retry))
                        }
                    }
                    if (onOpenDiagnostics != null) {
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            border = BorderStroke(1.dp, colors.error.copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("error_diagnostics_button")
                        ) {
                            Text(
                                text = stringResource(R.string.action_open_diagnostics),
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
