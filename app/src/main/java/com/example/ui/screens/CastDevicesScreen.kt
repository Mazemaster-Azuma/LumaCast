package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.CastDeviceInfo
import com.example.core.common.CastError
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkHealthState
import com.example.core.common.NetworkTransportMode
import com.example.core.common.RemotePlaybackState
import com.example.ui.components.CastErrorCard
import com.example.ui.components.OfficialMediaRouteButton
import com.example.ui.theme.AppThemeTokens

@Composable
fun CastDevicesScreen(
    isSdkAvailable: Boolean,
    isScanning: Boolean,
    discoveryTimedOut: Boolean,
    devices: List<CastDeviceInfo>,
    playbackState: RemotePlaybackState,
    networkHealth: NetworkHealthState,
    needsNearbyWifiPermission: Boolean,
    onRequestNearbyWifiPermission: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
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
                .widthIn(max = 820.dp)
                .testTag("cast_devices_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with native Google Cast MediaRouteButton
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chromecast Devices",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.onBackground
                        )
                        Text(
                            text = when (networkHealth.networkMode) {
                                NetworkTransportMode.MOBILE_HOTSPOT ->
                                    "Mobile hotspot · ${networkHealth.localIpv4Address ?: "Active"}"
                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT ->
                                    "Wi-Fi & hotspot · ${networkHealth.localIpv4Address ?: "Active"}"
                                NetworkTransportMode.CONVENTIONAL_WIFI ->
                                    "Wi-Fi LAN · ${networkHealth.localIpv4Address ?: "Active"}"
                                NetworkTransportMode.OFFLINE ->
                                    "Wi-Fi and hotspot inactive"
                            },
                            style = tokens.connectionStatus,
                            color = when (networkHealth.networkMode) {
                                NetworkTransportMode.MOBILE_HOTSPOT,
                                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> colors.primary
                                NetworkTransportMode.CONVENTIONAL_WIFI -> colors.secondary
                                NetworkTransportMode.OFFLINE -> colors.error
                            }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OfficialMediaRouteButton()
                        IconButton(
                            onClick = onRefreshDevices,
                            modifier = Modifier.testTag("devices_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                                tint = colors.primary
                            )
                        }
                    }
                }
            }

            // Optional Android 13+ Nearby Wi-Fi Devices permission prompt
            if (needsNearbyWifiPermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Allow Nearby Wi-Fi Devices",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.secondary
                                )
                                Text(
                                    text = "Improves Chromecast discovery on Android 13+ without requiring Location access.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = onRequestNearbyWifiPermission,
                                border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.6f)),
                                modifier = Modifier.testTag("devices_nearby_wifi_perm_button")
                            ) {
                                Text("Allow", color = colors.secondary)
                            }
                        }
                    }
                }
            }

            // Mobile Hotspot Active Banner & Guidance
            if (networkHealth.isHotspotActive) {
                item {
                    val multicastBlocked = networkHealth.multicastStatus == MulticastHealthStatus.RESTRICTED_OR_BLOCKED
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("devices_hotspot_banner"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (multicastBlocked) {
                                colors.errorContainer.copy(alpha = 0.7f)
                            } else {
                                colors.primaryContainer.copy(alpha = 0.55f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (multicastBlocked) colors.error.copy(alpha = 0.6f) else colors.primary.copy(alpha = 0.55f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = if (multicastBlocked) colors.error else colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (multicastBlocked) {
                                        "Hotspot multicast restricted"
                                    } else {
                                        "Mobile hotspot (${networkHealth.hotspotIpv4Address ?: "Active"})"
                                    },
                                    style = tokens.statusMessage,
                                    color = if (multicastBlocked) colors.error else colors.primary
                                )
                            }
                            Text(
                                text = networkHealth.diagnosticHeadline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface
                            )
                            networkHealth.troubleshootingHint?.let { hint ->
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onOpenHotspotSettings,
                                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f))
                                ) {
                                    Text("Hotspot settings", color = colors.primary)
                                }
                                OutlinedButton(
                                    onClick = onOpenTroubleshooting,
                                    border = BorderStroke(1.dp, colors.outlineVariant)
                                ) {
                                    Text("Run diagnostics")
                                }
                            }
                        }
                    }
                }
            }

            // Wi-Fi & Hotspot offline warning
            if (!networkHealth.isWifiConnected) {
                item {
                    CastErrorCard(
                        error = CastError.WifiNotConnected,
                        onOpenDiagnostics = onOpenTroubleshooting
                    )
                }
            }

            // SDK unavailable notice
            if (!isSdkAvailable) {
                item {
                    CastErrorCard(
                        error = CastError.CastSdkUnavailable,
                        onOpenDiagnostics = onOpenTroubleshooting
                    )
                }
            }

            // Active Connection Card
            if (playbackState.connectionState == CastConnectionState.CONNECTED) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer.copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.CastConnected,
                                    contentDescription = null,
                                    tint = colors.secondary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Connected session",
                                        style = tokens.badgeLabel,
                                        color = semantic.success
                                    )
                                    Text(
                                        text = playbackState.connectedDeviceName ?: "Chromecast TV",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onOpenRemote,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("devices_open_remote_button")
                                ) {
                                    Text(stringResource(R.string.action_open_remote))
                                }
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    border = BorderStroke(1.dp, colors.error.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("devices_disconnect_button")
                                ) {
                                    Icon(
                                        Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        tint = colors.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_disconnect),
                                        color = colors.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Discovered Devices List (Strictly real MediaRouter devices)
            item {
                val scopeLabel = if (networkHealth.isHotspotActive) "Available on hotspot / LAN" else "Available on Wi-Fi"
                Text(
                    text = "$scopeLabel (${devices.size})",
                    style = tokens.sectionHeading,
                    color = colors.onBackground
                )
            }

            if (devices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isScanning && !discoveryTimedOut && networkHealth.isWifiConnected && isSdkAvailable) {
                                CastRadarPulse()
                                Text(
                                    text = if (networkHealth.isHotspotActive) {
                                        "Scanning hotspot for Chromecast TVs"
                                    } else {
                                        stringResource(R.string.cast_scanning_title)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface
                                )
                            } else {
                                Icon(
                                    imageVector = if (networkHealth.isWifiConnected) Icons.Filled.Cast else Icons.Filled.WifiOff,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = if (discoveryTimedOut && networkHealth.isHotspotActive) {
                                        "Discovery timed out on hotspot"
                                    } else {
                                        stringResource(R.string.cast_no_devices_title)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface
                                )
                            }

                            Text(
                                text = if (networkHealth.isHotspotActive) {
                                    "Connect your Chromecast TV to this phone's hotspot. If the TV is connected and not listed, your phone's hotspot may restrict mDNS multicast traffic."
                                } else {
                                    stringResource(R.string.cast_no_devices_body)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onRefreshDevices,
                                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f))
                                ) {
                                    Text("Scan again", color = colors.primary)
                                }
                                OutlinedButton(
                                    onClick = onOpenTroubleshooting,
                                    border = BorderStroke(1.dp, colors.outlineVariant)
                                ) {
                                    Icon(
                                        Icons.Filled.HealthAndSafety,
                                        contentDescription = null,
                                        tint = colors.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Troubleshoot")
                                }
                            }
                        }
                    }
                }
            } else {
                items(devices, key = { it.routeId }) { device ->
                    CastDeviceRow(
                        device = device,
                        onClick = { onSelectDevice(device.routeId) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // Last Cast error if any
            playbackState.lastError?.let { error ->
                item {
                    CastErrorCard(
                        error = error,
                        onRetry = onRefreshDevices,
                        onOpenDiagnostics = onOpenTroubleshooting
                    )
                }
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDeviceInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("cast_device_${device.routeId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isSelected) colors.secondaryContainer.copy(alpha = 0.5f) else colors.surfaceContainer
        ),
        border = BorderStroke(
            width = if (device.isSelected) 1.5.dp else 1.dp,
            color = if (device.isSelected) colors.secondary else colors.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (device.isSelected) colors.secondary.copy(alpha = 0.16f) else colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Tv,
                    contentDescription = null,
                    tint = if (device.isSelected) colors.secondary else colors.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = tokens.deviceName,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${device.deviceTypeLabel} · ${device.description}",
                    style = tokens.videoMetadata,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when {
                device.isConnecting -> {
                    CircularProgressIndicator(
                        color = colors.secondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }

                device.isSelected -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.cast_connected),
                        tint = semantic.success
                    )
                }

                else -> {
                    Surface(
                        color = colors.primaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "Connect",
                            style = tokens.badgeLabel,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastRadarPulse() {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "cast_radar")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val secondaryPulse = ((pulse - 0.25f + 0.375f) % 0.75f) + 0.25f
    Box(
        modifier = Modifier.size(76.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            drawCircle(
                color = colors.secondary.copy(alpha = (1f - pulse) * 0.55f),
                radius = maxR * pulse,
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = colors.primary.copy(alpha = (1f - secondaryPulse) * 0.38f),
                radius = maxR * secondaryPulse,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = colors.secondary.copy(alpha = 0.16f),
                radius = size.minDimension * 0.3f
            )
        }
        Icon(
            imageVector = Icons.Filled.Cast,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(26.dp)
        )
    }
}
