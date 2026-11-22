package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.VideoItem
import com.example.core.common.VideoSourceType
import com.example.ui.theme.AppThemeTokens

@Composable
fun VideoRowOverflowMenu(
    video: VideoItem,
    onCast: () -> Unit,
    onPlayLocally: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(video.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("video_menu_${video.id}")
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_video_more_options, video.displayName),
                tint = colors.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceContainerHigh
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_cast), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Filled.Cast, contentDescription = null, tint = colors.secondary)
                },
                onClick = {
                    expanded = false
                    onCast()
                },
                modifier = Modifier.testTag("menu_cast_${video.id}")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_play_locally), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Filled.PlayCircleOutline, contentDescription = null, tint = colors.primary)
                },
                onClick = {
                    expanded = false
                    onPlayLocally()
                },
                modifier = Modifier.testTag("menu_play_local_${video.id}")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_video_details), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Filled.Info, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onDetails()
                },
                modifier = Modifier.testTag("menu_details_${video.id}")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(Icons.Filled.Share, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onShare()
                },
                modifier = Modifier.testTag("menu_share_${video.id}")
            )
            if (video.folderName.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_open_folder), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onOpenFolder()
                    },
                    modifier = Modifier.testTag("menu_folder_${video.id}")
                )
            }

            if (video.canRename || video.canDelete || video.sourceType == VideoSourceType.PHOTO_PICKER) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
            }

            if (video.canRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_rename), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onRequestRename()
                    },
                    modifier = Modifier.testTag("menu_rename_${video.id}")
                )
            }

            if (video.canDelete || video.sourceType == VideoSourceType.PHOTO_PICKER) {
                val deleteLabel = if (video.sourceType == VideoSourceType.PHOTO_PICKER) {
                    stringResource(R.string.menu_remove_from_library)
                } else {
                    stringResource(R.string.menu_delete)
                }
                DropdownMenuItem(
                    text = { Text(deleteLabel, style = MaterialTheme.typography.bodyMedium, color = colors.error) },
                    leadingIcon = {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = colors.error)
                    },
                    onClick = {
                        expanded = false
                        onRequestDelete()
                    },
                    modifier = Modifier.testTag("menu_delete_${video.id}")
                )
            }
        }
    }
}

@Composable
fun RenameVideoDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val initialBase = remember(video.id) {
        video.displayName.substringBeforeLast('.').ifBlank { video.displayName }
    }
    var newName by remember(video.id) { mutableStateOf(initialBase) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHigh,
        title = { Text("Rename video", style = AppThemeTokens.typography.dialogTitle) },
        text = {
            Column {
                Text(
                    text = if (video.extension.isNotBlank()) {
                        "The .${video.extension} extension is preserved automatically."
                    } else {
                        "Enter a new filename for this video."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { Text("Filename") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_video_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName.trim()) },
                enabled = newName.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                modifier = Modifier.testTag("rename_video_confirm")
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun DeleteVideoConfirmDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isPickerReference = video.sourceType == VideoSourceType.PHOTO_PICKER
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHigh,
        title = {
            Text(
                text = if (isPickerReference) "Remove from library?" else "Delete video?",
                style = AppThemeTokens.typography.dialogTitle
            )
        },
        text = {
            Text(
                text = if (isPickerReference) {
                    "\"${video.displayName}\" will be removed from LumaCast. The original file on your device will remain intact."
                } else {
                    "Permanently delete \"${video.displayName}\" from device storage? Android may ask you to confirm."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                modifier = Modifier.testTag("delete_video_confirm")
            ) {
                Text(if (isPickerReference) "Remove" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
