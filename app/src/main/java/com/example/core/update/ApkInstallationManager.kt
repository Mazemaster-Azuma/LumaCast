package com.example.core.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles Android 8+ install permission checks (`REQUEST_INSTALL_PACKAGES`)
 * and builds safe `FileProvider` content:// installation intents with user consent.
 */
class ApkInstallationManager(
    private val context: Context,
    private val canInstallOverride: (() -> Boolean)? = null
) {

    fun canRequestPackageInstalls(): Boolean {
        canInstallOverride?.let { return it() }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createManageUnknownAppSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    fun formatInstallerResultMessage(resultCode: Int): String? {
        return when (resultCode) {
            Activity.RESULT_OK -> null
            Activity.RESULT_CANCELED -> "Installation was cancelled. Tap Install Update whenever you're ready."
            else -> "Android Package Installer could not install this APK. Ensure signatures match and storage is sufficient."
        }
    }

    companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
