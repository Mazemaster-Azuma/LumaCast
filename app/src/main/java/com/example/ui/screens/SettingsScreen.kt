package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.core.common.Formatters
import com.example.core.common.IndexedFolder
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkHealthState
import com.example.core.common.NetworkTransportMode
import com.example.core.common.ReceiverProfile
import com.example.core.common.UserSettings
import com.example.core.update.AppUpdateState
import com.example.ui.theme.AppThemeTokens
import java.text.DateFormat
import java.util.Date

private val AUDIO_LANGUAGE_OPTIONS = listOf(
    "auto" to "Default",
    "en" to "English",
    "hi" to "Hindi",
    "bn" to "Bengali",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "ja" to "Japanese",
    "ko" to "Korean"
)

private val SUBTITLE_LANGUAGE_OPTIONS = listOf(
    "off" to "Off",
    "auto" to "Auto",
    "en" to "English",
    "hi" to "Hindi",
    "bn" to "Bengali",
    "es" to "Spanish",
    "ja" to "Japanese",
    "ko" to "Korean"
)

@Composable
fun SettingsScreen(
    settings: UserSettings,
    folders: List<IndexedFolder>,
    cacheSizeBytes: Long,
    networkHealth: NetworkHealthState,
    connectedDeviceName: String?,
    discoveredDeviceCount: Int,
    isScanningDevices: Boolean,
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    onSetReceiverProfile: (ReceiverProfile) -> Unit,
    onSetCustomReceiverAppId: (String) -> Unit,
    onSetAutoRemux: (Boolean) -> Unit,
    onSetAllowTranscode: (Boolean) -> Unit,
    onSetMaxTranscodeHeight: (Int) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetPreferAc3Passthrough: (Boolean) -> Unit,
    onSetPreferredSubtitleLanguage: (String) -> Unit,
    onSetPreferredPort: (Int) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onClearCache: () -> Unit,
    onRefreshDevices: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onOpenTroubleshooting: () -> Unit,
    onSetGithubRepo: (String) -> Unit = {},
    onSetAutoCheckUpdates: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val semantic = AppThemeTokens.semanticColors
    val tokens = AppThemeTokens.typography

    var receiverIdDraft by remember(settings.customReceiverAppId) {
        mutableStateOf(settings.customReceiverAppId)
    }
    var githubRepoDraft by remember(settings.githubUpdateRepo) {
        mutableStateOf(settings.githubUpdateRepo)
    }

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
                .testTag("settings_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground
                )
            }

            // Network & Connection (Dual-Mode: Conventional Wi-Fi & Mobile Hotspot)
            item {
                SettingsSectionCard(title = "Network & Connection") {
                    val modeIcon = when (networkHealth.networkMode) {
                        NetworkTransportMode.MOBILE_HOTSPOT,
                        NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> Icons.Filled.WifiTethering
                        NetworkTransportMode.CONVENTIONAL_WIFI -> Icons.Filled.Wifi
                        NetworkTransportMode.OFFLINE -> Icons.Filled.WifiOff
                    }
                    val modeAccent = when (networkHealth.networkMode) {
                        NetworkTransportMode.CONVENTIONAL_WIFI -> colors.secondary
                        NetworkTransportMode.MOBILE_HOTSPOT,
                        NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> colors.primary
                        NetworkTransportMode.OFFLINE -> colors.error
                    }

                    Surface(
                        color = colors.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, modeAccent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_network_mode_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = modeIcon,
                                    contentDescription = null,
                                    tint = modeAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = networkHealth.networkMode.label,
                                    style = tokens.statusMessage,
                                    color = modeAccent
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
                        }
                    }

                    NetworkInfoRow("Active Network", networkHealth.activeNetworkLabel ?: "Unavailable")
                    NetworkInfoRow(
                        "Local Bound IP",
                        networkHealth.localIpv4Address?.let { ip ->
                            "$ip (${networkHealth.interfaceName ?: "lan"})"
                        } ?: "None"
                    )
                    NetworkInfoRow(
                        "Mobile Hotspot",
                        if (networkHealth.isHotspotActive) {
                            "Active (${networkHealth.hotspotIpv4Address ?: "softap"})"
                        } else {
                            "Inactive"
                        }
                    )
                    NetworkInfoRow(
                        "Chromecast Discovery",
                        when {
                            discoveredDeviceCount > 0 -> "$discoveredDeviceCount TV(s) discovered"
                            networkHealth.multicastStatus == MulticastHealthStatus.RESTRICTED_OR_BLOCKED -> "Multicast blocked"
                            isScanningDevices -> "Scanning via Cast SDK…"
                            else -> "Idle"
                        }
                    )
                    NetworkInfoRow("Connected TV", connectedDeviceName ?: "Not connected")
                    NetworkInfoRow(
                        "Media Server",
                        if (networkHealth.serverRunning) {
                            "Running on :${networkHealth.serverPort} (${networkHealth.activeStreamsCount} stream)"
                        } else {
                            "Ready (starts on cast)"
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenHotspotSettings,
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_open_hotspot_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Hotspot settings", color = colors.primary, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onRefreshDevices,
                            border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_restart_discovery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = colors.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Re-scan TVs", color = colors.secondary, maxLines = 1)
                        }
                    }
                }
            }

            // Receiver Capability Profile
            item {
                SettingsSectionCard(title = "Chromecast Receiver Profile") {
                    ReceiverProfile.entries.forEach { profile ->
                        val isSelected = settings.receiverProfile == profile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetReceiverProfile(profile) }
                                .padding(vertical = 6.dp)
                                .testTag("receiver_profile_${profile.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSetReceiverProfile(profile) },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.displayName,
                                    style = if (isSelected) tokens.trackTitleSelected else tokens.trackTitle,
                                    color = if (isSelected) colors.primary else colors.onSurface
                                )
                                Text(
                                    text = profile.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Quality & Selective Transcoding Engine
            item {
                SettingsSectionCard(title = "Automatic Video & Audio Transcoding") {
                    SettingsToggleRow(
                        title = "Auto-remux incompatible containers",
                        subtitle = "Losslessly repackage MKV, MOV, or TS to MP4 without re-encoding video or audio.",
                        checked = settings.autoRemuxEnabled,
                        onCheckedChange = onSetAutoRemux,
                        tag = "toggle_auto_remux"
                    )
                    SettingsToggleRow(
                        title = "Selective track transcoding",
                        subtitle = "Convert only unsupported audio (DTS, TrueHD, FLAC to AAC) or video (Xvid, MPEG-2 to H.264) while preserving compatible tracks.",
                        checked = settings.allowHardwareTranscode,
                        onCheckedChange = onSetAllowTranscode,
                        tag = "toggle_allow_transcode"
                    )
                    SettingsToggleRow(
                        title = "AC-3 / E-AC-3 Dolby Digital passthrough",
                        subtitle = "Pass AC-3 and E-AC-3 audio untouched on 4K Chromecast and Google TV receivers.",
                        checked = settings.preferAc3Passthrough,
                        onCheckedChange = onSetPreferAc3Passthrough,
                        tag = "toggle_ac3_passthrough"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Preferred audio language",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AUDIO_LANGUAGE_OPTIONS) { (code, label) ->
                                FilterChip(
                                    selected = settings.preferredAudioLanguage == code,
                                    onClick = { onSetPreferredAudioLanguage(code) },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primaryContainer,
                                        selectedLabelColor = colors.primary
                                    )
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Preferred subtitle language",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SUBTITLE_LANGUAGE_OPTIONS) { (code, label) ->
                                FilterChip(
                                    selected = settings.preferredSubtitleLanguage == code,
                                    onClick = { onSetPreferredSubtitleLanguage(code) },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primaryContainer,
                                        selectedLabelColor = colors.primary
                                    )
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Custom Web Receiver App ID (Optional)",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Enter your 8-character Google Cast Console Custom Receiver ID for live audio track switching. Leave blank for Default Media Receiver.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = receiverIdDraft,
                                onValueChange = { receiverIdDraft = it.uppercase().take(12) },
                                textStyle = tokens.numericMetric,
                                placeholder = { Text("e.g. A1B2C3D4", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_custom_receiver_id_input")
                            )
                            OutlinedButton(
                                onClick = { onSetCustomReceiverAppId(receiverIdDraft) },
                                modifier = Modifier.testTag("settings_save_receiver_id_button")
                            ) {
                                Text("Save")
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Max transcode resolution cap",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (height, label) ->
                                FilterChip(
                                    selected = settings.maxTranscodeHeight == height,
                                    onClick = { onSetMaxTranscodeHeight(height) },
                                    label = { Text(label, style = tokens.telemetryCompact) },
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

            // Local HTTP Server & Remote
            item {
                SettingsSectionCard(title = "Local Media Server & Remote") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "HTTP server listening port",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Auto", 8080 to "8080", 8899 to "8899").forEach { (port, label) ->
                                FilterChip(
                                    selected = settings.preferredServerPort == port,
                                    onClick = { onSetPreferredPort(port) },
                                    label = { Text(label, style = tokens.telemetryCompact) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primaryContainer,
                                        selectedLabelColor = colors.primary
                                    )
                                )
                            }
                        }
                    }

                    SettingsToggleRow(
                        title = "Keep screen on in Remote Controller",
                        subtitle = "Prevents display timeout while actively controlling TV playback.",
                        checked = settings.keepScreenOnRemote,
                        onCheckedChange = onSetKeepScreenOn,
                        tag = "toggle_keep_screen_on"
                    )
                }
            }

            // Indexed SAF Folders
            if (folders.isNotEmpty()) {
                item {
                    SettingsSectionCard(title = "Linked Folders (${folders.size})") {
                        folders.forEach { folder ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = colors.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "${folder.videoCount} videos",
                                        style = tokens.toolbarSubtitle,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onRemoveFolder(folder.treeUri) }) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Remove folder",
                                        tint = colors.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Cache & Privacy
            item {
                SettingsSectionCard(title = "Storage Cache & Privacy") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prepared media cache",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Converted files: ${Formatters.formatFileSize(cacheSizeBytes)} (active streams protected)",
                                style = tokens.videoMetadata,
                                color = colors.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onClearCache,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.testTag("settings_clear_cache_button")
                        ) {
                            Text("Clear")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = semantic.success,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Local LAN streaming only. Videos never leave your network, and each session uses a 192-bit random token bound to your local interface.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenTroubleshooting,
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HealthAndSafety,
                            contentDescription = null,
                            tint = colors.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_diagnostics), color = colors.secondary)
                    }
                }
            }

            // App Updates (GitHub Releases)
            item {
                SettingsSectionCard(title = stringResource(R.string.update_section_title)) {
                    NetworkInfoRow(
                        label = "Application Version",
                        value = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
                    )

                    val lastCheckedLabel = if (settings.lastUpdateCheckMs > 0L) {
                        remember(settings.lastUpdateCheckMs) {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(settings.lastUpdateCheckMs))
                        }
                    } else {
                        "Not checked yet"
                    }
                    NetworkInfoRow(label = "Last Checked", value = lastCheckedLabel)

                    val statusText = when (appUpdateState) {
                        is AppUpdateState.Checking -> "Checking GitHub Releases…"
                        is AppUpdateState.UpdateAvailable -> "Update available: v${appUpdateState.release.versionName}"
                        is AppUpdateState.Downloading -> "Downloading v${appUpdateState.release.versionName} (${appUpdateState.progressPercent}%)"
                        is AppUpdateState.Verifying -> "Verifying APK signature & SHA-256…"
                        is AppUpdateState.NeedsInstallPermission -> "Install permission required"
                        is AppUpdateState.ReadyToInstall -> "Installation pending: v${appUpdateState.release.versionName}"
                        is AppUpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                        is AppUpdateState.Error -> appUpdateState.message
                        is AppUpdateState.Idle -> if (settings.githubUpdateRepo.isBlank()) {
                            "Enter owner/repo below (auto-filled when built in GitHub Actions)."
                        } else {
                            "Ready (github.com/${settings.githubUpdateRepo})"
                        }
                    }
                    val statusTint = when (appUpdateState) {
                        is AppUpdateState.UpdateAvailable,
                        is AppUpdateState.ReadyToInstall -> colors.primary
                        is AppUpdateState.UpToDate -> semantic.success
                        is AppUpdateState.Error -> colors.error
                        else -> colors.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Update Status",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusTint
                        )
                    }

                    // Surface update details directly inside Settings when available or downloading
                    when (appUpdateState) {
                        is AppUpdateState.UpdateAvailable -> {
                            Surface(
                                color = colors.surfaceContainerHigh,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Available: v${appUpdateState.release.versionName}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.primary
                                        )
                                        appUpdateState.release.apkAsset?.sizeBytes?.takeIf { it > 0L }?.let { bytes ->
                                            Text(
                                                text = Formatters.formatFileSize(bytes),
                                                style = tokens.telemetryCompact,
                                                color = colors.secondary
                                            )
                                        }
                                    }
                                    Text(
                                        text = appUpdateState.release.releaseNotes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        is AppUpdateState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { (appUpdateState.progressPercent / 100f).coerceIn(0f, 1f) },
                                color = colors.primary,
                                trackColor = colors.surfaceContainerHigh,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        is AppUpdateState.NeedsInstallPermission -> {
                            Text(
                                text = "Enable \"Allow from this source\" for LumaCast in Android Settings, then tap Install Update.",
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.warning
                            )
                        }

                        else -> Unit
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "GitHub repository (owner/repo)",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = githubRepoDraft,
                                onValueChange = { githubRepoDraft = it },
                                textStyle = tokens.telemetryCompact,
                                placeholder = { Text("e.g. username/lumacast", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_github_repo_input")
                            )
                            OutlinedButton(
                                onClick = { onSetGithubRepo(githubRepoDraft) },
                                modifier = Modifier.testTag("settings_save_github_repo_button")
                            ) {
                                Text("Save")
                            }
                        }
                    }

                    SettingsToggleRow(
                        title = "Auto-check when app opens",
                        subtitle = "Checks GitHub once per cold launch when online and idle (never polls in the background).",
                        checked = settings.autoCheckUpdates,
                        onCheckedChange = onSetAutoCheckUpdates,
                        tag = "toggle_auto_check_updates"
                    )

                    val hasReadyUpdate = appUpdateState is AppUpdateState.UpdateAvailable ||
                        appUpdateState is AppUpdateState.Downloading ||
                        appUpdateState is AppUpdateState.Verifying ||
                        appUpdateState is AppUpdateState.NeedsInstallPermission ||
                        appUpdateState is AppUpdateState.ReadyToInstall

                    if (hasReadyUpdate) {
                        Button(
                            onClick = onOpenUpdateDialog,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_install_update_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            val ctaLabel = when (appUpdateState) {
                                is AppUpdateState.ReadyToInstall,
                                is AppUpdateState.NeedsInstallPermission -> stringResource(R.string.update_install_now)
                                is AppUpdateState.Downloading -> "Downloading… ${appUpdateState.progressPercent}%"
                                else -> stringResource(R.string.update_download_action)
                            }
                            Text(ctaLabel)
                        }
                    } else {
                        val isChecking = appUpdateState is AppUpdateState.Checking
                        OutlinedButton(
                            onClick = onCheckForUpdates,
                            enabled = !isChecking,
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_check_updates_button")
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(
                                    color = colors.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.SystemUpdate,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isChecking) "Checking…" else stringResource(R.string.update_check_action),
                                color = colors.primary
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
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
                text = title,
                style = AppThemeTokens.typography.sectionHeading,
                color = colors.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun NetworkInfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            color = colors.onSurface
        )
    }
}
