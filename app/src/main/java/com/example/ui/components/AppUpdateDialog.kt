package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.Formatters
import com.example.core.update.AppUpdateState
import com.example.core.update.GitHubReleaseInfo
import com.example.ui.theme.AppThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AppUpdateDialog(
    updateState: AppUpdateState,
    currentVersion: String,
    onDownloadAndInstall: (GitHubReleaseInfo) -> Unit,
    onInstallNow: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography
    val semantic = AppThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current

    val isBlockingWork = updateState is AppUpdateState.Downloading || updateState is AppUpdateState.Verifying

    AlertDialog(
        onDismissRequest = {
            if (!isBlockingWork) onDismiss()
        },
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("app_update_dialog"),
        icon = {
            val (tint, bg, icon) = when (updateState) {
                is AppUpdateState.ReadyToInstall,
                is AppUpdateState.UpToDate -> Triple(semantic.success, semantic.successContainer, Icons.Filled.CheckCircle)
                is AppUpdateState.NeedsInstallPermission -> Triple(semantic.warning, semantic.warningContainer, Icons.Filled.Security)
                is AppUpdateState.Error -> Triple(colors.error, colors.errorContainer, Icons.Filled.ErrorOutline)
                is AppUpdateState.Downloading -> Triple(colors.primary, colors.primaryContainer, Icons.Filled.Download)
                is AppUpdateState.Verifying -> Triple(colors.secondary, colors.secondaryContainer, Icons.Filled.VerifiedUser)
                else -> Triple(colors.primary, colors.primaryContainer, Icons.Filled.SystemUpdate)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            val heading = when (updateState) {
                is AppUpdateState.Checking -> "Checking for Updates"
                is AppUpdateState.UpdateAvailable -> stringResource(R.string.update_available_title)
                is AppUpdateState.Downloading -> "Downloading Update"
                is AppUpdateState.Verifying -> "Verifying Package"
                is AppUpdateState.NeedsInstallPermission -> "Allow App Installs"
                is AppUpdateState.ReadyToInstall -> "Ready to Install"
                is AppUpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is AppUpdateState.Error -> "Update Check Failed"
                is AppUpdateState.Idle -> stringResource(R.string.update_section_title)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (updateState) {
                    is AppUpdateState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = colors.primary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Checking latest stable GitHub release…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    is AppUpdateState.UpdateAvailable -> {
                        ReleaseHeaderCard(
                            currentVersion = updateState.currentVersion,
                            release = updateState.release
                        )
                        ReleaseNotesBox(notes = updateState.release.releaseNotes)
                        if (updateState.release.apkAsset == null) {
                            Text(
                                text = "This GitHub Release does not attach an .apk file yet. You can open the release page on GitHub to view its assets.",
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.warning
                            )
                        }
                    }

                    is AppUpdateState.Downloading -> {
                        ReleaseHeaderCard(
                            currentVersion = currentVersion,
                            release = updateState.release
                        )
                        val animatedProgress by animateFloatAsState(
                            targetValue = (updateState.progressPercent / 100f).coerceIn(0f, 1f),
                            animationSpec = tween(durationMillis = 200),
                            label = "apk_download_progress"
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
                                text = "${updateState.progressPercent}%",
                                style = tokens.numericMetric,
                                color = colors.primary
                            )
                            val totalLabel = if (updateState.totalBytes > 0L) {
                                Formatters.formatFileSize(updateState.totalBytes)
                            } else "Unknown size"
                            Text(
                                text = "${Formatters.formatFileSize(updateState.bytesDownloaded)} / $totalLabel",
                                style = tokens.telemetryCompact,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    is AppUpdateState.Verifying -> {
                        ReleaseHeaderCard(
                            currentVersion = currentVersion,
                            release = updateState.release
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                color = colors.secondary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Verifying SHA-256 checksum, package ID, and signing certificate…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    is AppUpdateState.NeedsInstallPermission -> {
                        ReleaseHeaderCard(
                            currentVersion = currentVersion,
                            release = updateState.release
                        )
                        Text(
                            text = "To install updates from GitHub Releases, enable \"Allow from this source\" for LumaCast in Android Settings. Your verified APK is ready.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }

                    is AppUpdateState.ReadyToInstall -> {
                        ReleaseHeaderCard(
                            currentVersion = currentVersion,
                            release = updateState.release
                        )
                        Surface(
                            color = semantic.successContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = semantic.success,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (updateState.sha256Verified) {
                                        "SHA-256, package identity, and signing certificate verified."
                                    } else {
                                        "Package identity and signing certificate verified. Ready to install."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface
                                )
                            }
                        }
                        updateState.lastInstallFeedback?.let { feedback ->
                            Text(
                                text = feedback,
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.warning
                            )
                        }
                    }

                    is AppUpdateState.UpToDate -> {
                        Text(
                            text = "Installed version: v${updateState.currentVersion} (build ${updateState.currentVersionCode}).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }

                    is AppUpdateState.Error -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.error
                        )
                    }

                    is AppUpdateState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    val apkAsset = updateState.release.apkAsset
                    if (apkAsset != null) {
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDownloadAndInstall(updateState.release)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier.testTag("update_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_now))
                        }
                    } else if (updateState.release.htmlUrl.isNotBlank()) {
                        Button(
                            onClick = { onOpenReleasePage(updateState.release.htmlUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("View Release")
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }

                is AppUpdateState.Downloading,
                is AppUpdateState.Verifying -> {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is AppUpdateState.NeedsInstallPermission -> {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenInstallPermission()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.testTag("update_grant_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onInstallNow()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.testTag("update_install_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.InstallMobile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.update_install_now))
                    }
                }

                is AppUpdateState.Error -> {
                    Button(
                        onClick = onRetryCheck,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                is AppUpdateState.UpToDate,
                is AppUpdateState.Idle -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }

                is AppUpdateState.Checking -> Unit
            }
        },
        dismissButton = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!updateState.release.mandatoryUpdate) {
                            TextButton(
                                onClick = { onSkipVersion(updateState.release.tagName) },
                                modifier = Modifier.testTag("update_skip_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.update_skip_version),
                                    color = colors.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("update_later_button")
                            ) {
                                Text(stringResource(R.string.update_later))
                            }
                        }
                    }
                }

                is AppUpdateState.NeedsInstallPermission -> {
                    TextButton(onClick = onInstallNow) {
                        Text("Try Install")
                    }
                }

                is AppUpdateState.ReadyToInstall,
                is AppUpdateState.Error,
                is AppUpdateState.Checking -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                }

                else -> Unit
            }
        }
    )
}

@Composable
private fun ReleaseHeaderCard(
    currentVersion: String,
    release: GitHubReleaseInfo
) {
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography

    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = colors.surfaceContainerHighest,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Installed: v${currentVersion.removePrefix("v")}",
                        style = tokens.telemetryCompact,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(14.dp)
                )
                Surface(
                    color = colors.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Latest: v${release.versionName}",
                        style = tokens.telemetryCompact,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.releaseTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    formatReleaseDate(release.publishedAtIso)?.let { dateLabel ->
                        Text(
                            text = "Released $dateLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                release.apkAsset?.sizeBytes?.takeIf { it > 0L }?.let { size ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Formatters.formatFileSize(size),
                        style = tokens.telemetryCompact,
                        color = colors.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesBox(notes: String) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 170.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "What's New",
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary
            )
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private fun formatReleaseDate(isoTimestamp: String): String? {
    if (isoTimestamp.isBlank()) return null
    return runCatching {
        val instant = Instant.parse(isoTimestamp)
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrNull()
}
