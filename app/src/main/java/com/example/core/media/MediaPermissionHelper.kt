package com.example.core.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.core.common.MediaPermissionStatus

/**
 * Centralizes Android visual media permission handling across Android 8–16:
 * - Android 14+ (API 34+): READ_MEDIA_VIDEO, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED
 * - Android 13 (API 33): READ_MEDIA_VIDEO, READ_MEDIA_IMAGES
 * - Android 8–12 (API 26–32): READ_EXTERNAL_STORAGE
 */
object MediaPermissionHelper {

    fun requiredMediaPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    fun evaluatePermissionStatus(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): MediaPermissionStatus {
        return evaluateFromCheck(sdkInt) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    internal fun evaluateFromCheck(
        sdkInt: Int,
        isGranted: (String) -> Boolean
    ): MediaPermissionStatus {
        return when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasVideo = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
                val hasImages = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
                val hasUserSelected = isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                when {
                    hasVideo && hasImages -> MediaPermissionStatus.FULL_ACCESS
                    hasVideo || hasImages || hasUserSelected -> MediaPermissionStatus.PARTIAL_ACCESS
                    else -> MediaPermissionStatus.DENIED
                }
            }
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
                val hasVideo = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
                val hasImages = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
                when {
                    hasVideo && hasImages -> MediaPermissionStatus.FULL_ACCESS
                    hasVideo || hasImages -> MediaPermissionStatus.PARTIAL_ACCESS
                    else -> MediaPermissionStatus.DENIED
                }
            }
            else -> {
                if (isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    MediaPermissionStatus.FULL_ACCESS
                } else {
                    MediaPermissionStatus.DENIED
                }
            }
        }
    }

    fun createAppSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
