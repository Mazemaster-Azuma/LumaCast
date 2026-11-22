package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.NetworkHealthState
import com.example.core.common.NetworkTransportMode
import com.example.core.common.RemotePlaybackState
import com.example.core.common.VideoItem
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.LibraryUiState
import com.example.ui.components.NativeCastActionPill
import com.example.ui.components.VideoGridCard
import com.example.ui.components.VideoListRow
import com.example.ui.components.displayLabel
import com.example.ui.theme.AppThemeTokens
import com.example.ui.theme.CastCyan
import com.example.ui.theme.CinemaGold
import com.example.ui.theme.SignalCoral

@Composable
fun HomeScreen(
    libraryState: LibraryUiState,
    playbackState: RemotePlaybackState,
    networkHealth: NetworkHealthState,
    discoveredDeviceCount: Int,
    thumbnailLoader: VideoThumbnailLoader? = null,
    onPickVideos: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenVideoDetails: (VideoItem) -> Unit,
    onOpenLibrary: (String?) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenTroubleshooting: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .testTag("home_screen"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Top Brand Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_lumacast_fg),
                            contentDescription = stringResource(R.string.cd_app_logo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = tokens.toolbarTitle,
                                color = colors.onBackground
                            )
                            Text(
                                text = when (networkHealth.networkMode) {
                                    NetworkTransportMode.MOBILE_HOTSPOT ->
                                        "Hotspot · ${networkHealth.localIpv4Address ?: "Active"}"
                                    NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT ->
                                        "Hotspot & Wi-Fi · ${networkHealth.localIpv4Address ?: "Active"}"
                                    NetworkTransportMode.CONVENTIONAL_WIFI ->
                                        "LAN · ${networkHealth.localIpv4Address ?: "Ready"}"
                                    NetworkTransportMode.OFFLINE ->
                                        "Connect Wi-Fi or hotspot"
                                },
                                style = tokens.toolbarSubtitle,
                                color = when (networkHealth.networkMode) {
                                    NetworkTransportMode.MOBILE_HOTSPOT,
                                    NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> colors.primary
                                    NetworkTransportMode.CONVENTIONAL_WIFI -> semantic.success
                                    NetworkTransportMode.OFFLINE -> colors.error
                                }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onOpenTroubleshooting,
                            modifier = Modifier.testTag("home_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.HealthAndSafety,
                                contentDescription = stringResource(R.string.action_open_diagnostics),
                                tint = colors.onSurfaceVariant
                            )
                        }
                        NativeCastActionPill(
                            connectionState = playbackState.connectionState,
                            connectedDeviceName = playbackState.connectedDeviceName,
                            discoveredCount = discoveredDeviceCount,
                            onOpenDevices = onOpenDevices
                        )
                    }
                }
            }

            // Cinema Hero Banner (uses dark scrim over cinema photography for crisp white text in both themes)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = semantic.scrimDark)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_cinema_backdrop),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            semantic.scrimDark.copy(alpha = 0.92f),
                                            semantic.scrimDark.copy(alpha = 0.75f),
                                            semantic.scrimDark.copy(alpha = 0.50f)
                                        )
                                    )
                                )
                        )
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = semantic.scrimDark.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (networkHealth.networkMode) {
                                                NetworkTransportMode.MOBILE_HOTSPOT,
                                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> Icons.Filled.WifiTethering
                                                NetworkTransportMode.CONVENTIONAL_WIFI -> Icons.Filled.Wifi
                                                NetworkTransportMode.OFFLINE -> Icons.Filled.WifiOff
                                            },
                                            contentDescription = null,
                                            tint = when (networkHealth.networkMode) {
                                                NetworkTransportMode.MOBILE_HOTSPOT,
                                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> CinemaGold
                                                NetworkTransportMode.CONVENTIONAL_WIFI -> CastCyan
                                                NetworkTransportMode.OFFLINE -> SignalCoral
                                            },
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = when (networkHealth.networkMode) {
                                                NetworkTransportMode.MOBILE_HOTSPOT -> "Mobile hotspot"
                                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> "Wi-Fi & hotspot"
                                                NetworkTransportMode.CONVENTIONAL_WIFI -> "Direct cast"
                                                NetworkTransportMode.OFFLINE -> "Network offline"
                                            },
                                            style = tokens.badgeLabel,
                                            color = when (networkHealth.networkMode) {
                                                NetworkTransportMode.MOBILE_HOTSPOT,
                                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> CinemaGold
                                                NetworkTransportMode.CONVENTIONAL_WIFI -> CastCyan
                                                NetworkTransportMode.OFFLINE -> SignalCoral
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Stream local videos directly to your TV",
                                style = MaterialTheme.typography.headlineMedium,
                                color = semantic.onScrimDark
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Your TV decodes the local stream directly over Wi-Fi without screen mirroring.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onPickVideos,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CinemaGold,
                                        contentColor = Color(0xFF1D1300)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_add_videos_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AddPhotoAlternate,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_add_videos))
                                }
                                OutlinedButton(
                                    onClick = onPickFolder,
                                    border = BorderStroke(1.dp, CinemaGold.copy(alpha = 0.65f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_add_folder_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CreateNewFolder,
                                        contentDescription = null,
                                        tint = CinemaGold,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_add_folder),
                                        color = semantic.onScrimDark
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Currently Casting Card
            if (playbackState.connectionState == CastConnectionState.CONNECTED) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenRemote)
                            .testTag("home_now_casting_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer.copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tv,
                                contentDescription = null,
                                tint = colors.secondary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_now_casting),
                                    style = tokens.badgeLabel,
                                    color = colors.secondary
                                )
                                Text(
                                    text = playbackState.currentVideo?.title
                                        ?: "Connected to ${playbackState.connectedDeviceName}",
                                    style = tokens.deviceName,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playbackState.connectedDeviceName ?: "Chromecast"} · ${playbackState.playerStatus.displayLabel()}",
                                    style = tokens.connectionStatus,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = onOpenRemote) {
                                Text(
                                    text = stringResource(R.string.action_open_remote),
                                    color = colors.primary
                                )
                            }
                        }
                    }
                }
            }

            // Indexed Folders Row
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_folders_section),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onBackground
                        )
                        TextButton(
                            onClick = onPickFolder,
                            modifier = Modifier.testTag("home_browse_folders_button")
                        ) {
                            Text(
                                text = stringResource(R.string.action_browse_folders),
                                color = colors.primary
                            )
                        }
                    }

                    if (libraryState.folders.isEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClick = onPickFolder),
                            color = colors.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = colors.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Link a movie folder",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Scan MP4, MKV, WebM, and MOV folders securely via Storage Access Framework.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(libraryState.folders, key = { it.treeUri.toString() }) { folder ->
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onOpenLibrary(folder.displayName) },
                                    color = colors.surfaceContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, colors.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = colors.primary
                                        )
                                        Column {
                                            Text(
                                                text = folder.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = colors.onSurface
                                            )
                                            Text(
                                                text = "${folder.videoCount} video${if (folder.videoCount == 1) "" else "s"}",
                                                style = tokens.toolbarSubtitle,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recently Added Carousel
            if (libraryState.recentVideos.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.home_recently_added),
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.onBackground
                            )
                            TextButton(onClick = { onOpenLibrary(null) }) {
                                Text("See all", color = colors.primary)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(libraryState.recentVideos, key = { it.id }) { video ->
                                VideoGridCard(
                                    video = video,
                                    thumbnailLoader = thumbnailLoader,
                                    onClick = { onOpenVideoDetails(video) },
                                    modifier = Modifier
                                        .width(230.dp)
                                        .animateItem()
                                )
                            }
                        }
                    }
                }
            }

            // All Videos Preview
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.home_all_videos)} (${libraryState.allVideos.size})",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground
                    )
                    if (libraryState.allVideos.isNotEmpty()) {
                        TextButton(onClick = { onOpenLibrary(null) }) {
                            Text("Open library", color = colors.primary)
                        }
                    }
                }
            }

            if (libraryState.allVideos.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.library_empty_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.library_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onPickVideos,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                                ) {
                                    Text(stringResource(R.string.action_add_videos))
                                }
                                OutlinedButton(
                                    onClick = onPickFolder,
                                    border = BorderStroke(1.dp, colors.outlineVariant)
                                ) {
                                    Text(stringResource(R.string.action_add_folder))
                                }
                            }
                        }
                    }
                }
            } else {
                items(libraryState.allVideos.take(8), key = { it.id }) { video ->
                    VideoListRow(
                        video = video,
                        thumbnailLoader = thumbnailLoader,
                        onClick = { onOpenVideoDetails(video) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
