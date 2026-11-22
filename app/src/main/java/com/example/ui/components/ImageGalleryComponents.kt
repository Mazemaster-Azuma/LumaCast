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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.core.common.Formatters
import com.example.core.common.ImageItem
import com.example.ui.theme.AppThemeTokens

@Composable
fun ImageGridCard(
    image: ImageItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography
    val context = LocalContext.current
    val request = remember(image.uri, context) {
        ImageRequest.Builder(context)
            .data(image.uri)
            .crossfade(160)
            .build()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("image_card_${image.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(colors.surfaceContainerHighest)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(32.dp)
                )
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.cd_image_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.58f to Color.Transparent,
                                1f to semantic.scrimDark.copy(alpha = 0.78f)
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
                        text = image.formatLabel,
                        style = tokens.badgeLabel,
                        color = semantic.onScrimDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (image.effectiveWidth > 0 && image.effectiveHeight > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = image.exactResolutionLabel,
                            style = tokens.durationOverlay,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = image.displayName,
                    style = tokens.videoFilename,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${image.folderName} · ${Formatters.formatFileSize(image.sizeBytes)}",
                    style = tokens.videoMetadata,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ImageListRow(
    image: ImageItem,
    onClick: () -> Unit,
    onCast: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    val context = LocalContext.current
    val request = remember(image.uri, context) {
        ImageRequest.Builder(context)
            .data(image.uri)
            .crossfade(160)
            .build()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("image_row_${image.id}"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(28.dp)
                )
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.cd_image_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = image.displayName,
                    style = tokens.videoFilename,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetaTag(text = image.formatLabel, accent = colors.primary)
                    if (image.sizeBytes > 0L) {
                        Text(
                            text = Formatters.formatFileSize(image.sizeBytes),
                            style = tokens.videoMetadata,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "${image.exactResolutionLabel} · ${image.folderName}",
                    style = tokens.videoMetadata,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onCast,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("image_cast_button_${image.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Cast,
                    contentDescription = stringResource(R.string.action_cast_photo_to_tv),
                    tint = colors.primary
                )
            }
            IconButton(
                onClick = onShare,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("image_share_button_${image.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.menu_share),
                    tint = colors.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagePreviewCastDialog(
    image: ImageItem,
    isCastConnected: Boolean,
    connectedDeviceName: String?,
    onCastToTv: () -> Unit,
    onOpenDevices: () -> Unit,
    onShare: () -> Unit,
    onOpenLocally: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    val context = LocalContext.current
    val request = remember(image.uri, context) {
        ImageRequest.Builder(context)
            .data(image.uri)
            .crossfade(180)
            .build()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("image_preview_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = image.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = image.folderName,
                            style = tokens.toolbarSubtitle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("image_preview_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close preview",
                            tint = colors.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceContainerLowest),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetaTag(text = image.formatLabel, accent = colors.primary)
                    MetaTag(text = image.exactResolutionLabel, accent = colors.secondary)
                    if (image.sizeBytes > 0L) {
                        MetaTag(text = Formatters.formatFileSize(image.sizeBytes), accent = colors.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isCastConnected) onCastToTv() else onOpenDevices()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("image_preview_cast_button")
                ) {
                    Icon(
                        imageVector = if (isCastConnected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isCastConnected) {
                            "Cast Photo to ${connectedDeviceName ?: "TV"}"
                        } else {
                            "Connect TV to Cast Photo"
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("image_preview_share_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.menu_share))
                    }
                    OutlinedButton(
                        onClick = onOpenLocally,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("image_preview_open_button")
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                }
            }
        }
    }
}
