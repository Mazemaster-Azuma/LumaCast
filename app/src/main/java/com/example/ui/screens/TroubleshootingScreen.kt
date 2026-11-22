package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.Formatters
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkHealthState
import com.example.core.common.NetworkTransportMode
import com.example.core.common.ResourceHealthStatus
import com.example.ui.theme.AppThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootingScreen(
    networkHealth: NetworkHealthState,
    resourceHealth: ResourceHealthStatus,
    isCastSdkAvailable: Boolean,
    discoveredDeviceCount: Int,
    connectedDeviceName: String?,
    isRunningDiagnostics: Boolean,
    onRunHttpSelfTest: () -> Unit,
    onRefreshDevices: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics & Help", style = tokens.toolbarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("troubleshoot_back_button")) {
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
                    .testTag("troubleshooting_screen"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Live Telemetry Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Network, Hotspot & Transcoding Status",
                                style = tokens.sectionHeading,
                                color = colors.primary
                            )
                            Text(
                                text = networkHealth.diagnosticHeadline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (networkHealth.isWifiConnected) colors.secondary else colors.error
                            )
                            DiagRow(
                                label = "Network Mode",
                                value = networkHealth.networkMode.label,
                                ok = networkHealth.networkMode != NetworkTransportMode.OFFLINE
                            )
                            DiagRow(
                                label = "Bound LAN / Hotspot IP",
                                value = if (networkHealth.isWifiConnected) {
                                    "${networkHealth.localIpv4Address ?: "Active"} (${networkHealth.interfaceName ?: "lan"})"
                                } else {
                                    "Offline"
                                },
                                ok = networkHealth.isWifiConnected
                            )
                            DiagRow(
                                label = "Mobile Hotspot",
                                value = if (networkHealth.isHotspotActive) {
                                    "Active (${networkHealth.hotspotIpv4Address ?: networkHealth.hotspotInterfaceName ?: "softap"})"
                                } else {
                                    "Inactive"
                                },
                                ok = networkHealth.isHotspotActive || networkHealth.isConventionalWifiConnected
                            )
                            DiagRow(
                                label = "Mobile Data",
                                value = if (networkHealth.isMobileDataEnabled) "Enabled" else "Off",
                                ok = true
                            )
                            DiagRow(
                                label = "mDNS / Multicast Status",
                                value = networkHealth.multicastStatus.label,
                                ok = networkHealth.multicastStatus != MulticastHealthStatus.RESTRICTED_OR_BLOCKED
                            )
                            DiagRow(
                                label = "VPN Active",
                                value = if (networkHealth.isVpnActive) "Yes (may block LAN)" else "No",
                                ok = !networkHealth.isVpnActive
                            )
                            DiagRow(
                                label = "Google Cast Sender SDK",
                                value = if (isCastSdkAvailable) "Initialized" else "Play Services missing",
                                ok = isCastSdkAvailable
                            )
                            DiagRow(
                                label = "Discovered Cast Devices",
                                value = "$discoveredDeviceCount TV(s)${connectedDeviceName?.let { " · $it" } ?: ""}",
                                ok = discoveredDeviceCount > 0 || connectedDeviceName != null
                            )
                            DiagRow(
                                label = "Backend A (Media3 HW + Muxer)",
                                value = "Active",
                                ok = true
                            )
                            DiagRow(
                                label = "Backend B (FFmpeg libffmpeg.so)",
                                value = if (resourceHealth.ffmpegAvailable) "Bundled & ready" else "Not bundled in APK",
                                ok = resourceHealth.ffmpegAvailable
                            )
                            DiagRow(
                                label = "Available Temp Storage",
                                value = Formatters.formatFileSize(resourceHealth.availableDiskBytes),
                                ok = resourceHealth.availableDiskBytes > 512L * 1024 * 1024
                            )
                            DiagRow(
                                label = "Thermal State",
                                value = resourceHealth.thermalStatusLabel,
                                ok = !resourceHealth.isThermallyThrottled
                            )
                            DiagRow(
                                label = "Embedded HTTP Server",
                                value = if (networkHealth.serverRunning) {
                                    "Running on :${networkHealth.serverPort} (${networkHealth.activeStreamsCount} stream)"
                                } else {
                                    "Idle (starts on cast)"
                                },
                                ok = true
                            )
                            networkHealth.lastHealthCheckPassed?.let { passed ->
                                DiagRow(
                                    label = "HTTP Server Self-Test",
                                    value = if (passed) {
                                        "200 OK (${networkHealth.lastHealthCheckLatencyMs} ms)"
                                    } else {
                                        "Failed"
                                    },
                                    ok = passed
                                )
                            }

                            networkHealth.lastDiagnosticReport?.let { report ->
                                if (report.multicastProbeDetail.isNotBlank()) {
                                    Text(
                                        text = "mDNS probe: ${report.multicastProbeDetail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (report.oemCompatibilityNote.isNotBlank()) {
                                    Text(
                                        text = "Device compatibility: ${report.oemCompatibilityNote}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                report.actionableRecommendations.forEach { rec ->
                                    Text(
                                        text = "• $rec",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.primary
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onRunHttpSelfTest,
                                    enabled = !isRunningDiagnostics,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("run_http_selftest_button")
                                ) {
                                    if (isRunningDiagnostics) {
                                        CircularProgressIndicator(
                                            color = colors.onPrimary,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text("Run diagnostics")
                                }
                                Button(
                                    onClick = onRefreshDevices,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.secondary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("diag_scan_devices_button")
                                ) {
                                    Text("Re-scan TVs")
                                }
                            }

                            OutlinedButton(
                                onClick = onOpenHotspotSettings,
                                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("diag_hotspot_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Open Android Hotspot Settings", color = colors.primary)
                            }
                        }
                    }
                }

                // Actionable Guides
                item {
                    Text(
                        text = "Common Casting & Playback Solutions",
                        style = tokens.sectionHeading,
                        color = colors.onBackground
                    )
                }

                item {
                    TroubleshootGuideCard(
                        title = "Casting Over Android Mobile Hotspot",
                        body = "When your phone acts as a Wi-Fi hotspot, LumaCast binds its local HTTP media server to the active hotspot IP and acquires WifiManager.MulticastLock during Chromecast scanning. Some OEMs block mDNS multicast forwarding on SoftAP interfaces; if your TV does not appear, disable Hotspot Client Isolation if available or use a standard Wi-Fi router."
                    )
                }

                item {
                    TroubleshootGuideCard(
                        title = "Video Plays Without Sound (DTS, TrueHD, AC-3, 7.1)",
                        body = "LumaCast inspects audio independently of video. When video is compatible and only audio is unsupported, audio-only conversion preserves the original video bitstream and converts only the selected audio track to AAC-LC."
                    )
                }

                item {
                    TroubleshootGuideCard(
                        title = "TV Connects, Then Fails to Load Media",
                        body = "If the Chromecast joins the session but cannot fetch the stream, your router or VPN may block local LAN traffic on port ${networkHealth.serverPort ?: "auto"}. Disable AP/Client Isolation and pause VPNs blocking local subnets."
                    )
                }

                item {
                    TroubleshootGuideCard(
                        title = "DTS / TrueHD / Xvid Decoder Availability",
                        body = "Backend A uses Android Media3 Transformer and device MediaCodec decoders. For codecs lacking an Android MediaCodec decoder, Backend B delegates to a bundled native FFmpeg binary when packaged."
                    )
                }

                item {
                    TroubleshootGuideCard(
                        title = "Background Conversion & Screen Lock",
                        body = "LumaCast runs a mediaPlayback Foreground Service with wake and Wi-Fi locks during conversion and casting. Set battery optimization to Unrestricted on devices that restrict background services."
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) semantic.success else colors.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = AppThemeTokens.typography.telemetryCompact,
            color = colors.onSurface
        )
    }
}

@Composable
private fun TroubleshootGuideCard(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}
