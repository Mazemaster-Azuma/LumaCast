package com.example.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ParsedApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val signingCertSha256Set: Set<String>
)

sealed interface ApkVerificationResult {
    data class Verified(val sha256Verified: Boolean) : ApkVerificationResult
    data class Rejected(val reason: String) : ApkVerificationResult
}

/**
 * Validates downloaded update APKs before Android installation:
 * - Non-empty ZIP/APK binary header
 * - SHA-256 digest match against trusted release metadata
 * - Package name identity match
 * - Monotonic versionCode check (rejects downgrades)
 * - Signing certificate fingerprint match against the installed application
 */
class ApkVerificationManager(
    private val context: Context,
    private val installedPackageInspector: () -> ParsedApkMetadata? = {
        readInstalledPackageMetadata(context)
    },
    private val archiveInspector: (File) -> ParsedApkMetadata? = { apkFile ->
        readArchiveMetadata(context, apkFile)
    }
) {

    fun verifyDownloadedApk(
        apkFile: File,
        installedVersionCode: Int,
        expectedSha256: String?,
        requireChecksumMatch: Boolean = false
    ): ApkVerificationResult {
        if (!apkFile.exists() || apkFile.length() < 4L) {
            return ApkVerificationResult.Rejected("Downloaded APK file is missing or empty.")
        }

        if (!hasValidZipHeader(apkFile)) {
            return ApkVerificationResult.Rejected("Downloaded file is corrupted or not a valid Android APK.")
        }

        val actualSha256 = computeSha256(apkFile)
        val shaVerified = if (!expectedSha256.isNullOrBlank()) {
            if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                return ApkVerificationResult.Rejected(
                    "Security verification failed: SHA-256 checksum mismatch."
                )
            }
            true
        } else if (requireChecksumMatch) {
            return ApkVerificationResult.Rejected(
                "Release is missing required SHA-256 verification metadata."
            )
        } else {
            false
        }

        val archiveMeta = archiveInspector(apkFile)
            ?: return ApkVerificationResult.Rejected(
                "Unable to parse Android APK package metadata. The file may be corrupted."
            )

        if (archiveMeta.packageName != context.packageName) {
            return ApkVerificationResult.Rejected(
                "Package ID mismatch (${archiveMeta.packageName}). Update rejected."
            )
        }

        if (archiveMeta.versionCode <= installedVersionCode.toLong()) {
            return ApkVerificationResult.Rejected(
                "Version downgrade or duplicate rejected (APK build ${archiveMeta.versionCode} vs installed $installedVersionCode)."
            )
        }

        if (archiveMeta.signingCertSha256Set.isEmpty()) {
            return ApkVerificationResult.Rejected(
                "Update APK is unsigned and cannot be installed."
            )
        }

        val installedMeta = installedPackageInspector()
        if (installedMeta != null && installedMeta.signingCertSha256Set.isNotEmpty()) {
            val sharesSigningCert = archiveMeta.signingCertSha256Set.any { cert ->
                cert in installedMeta.signingCertSha256Set
            }
            if (!sharesSigningCert) {
                return ApkVerificationResult.Rejected(
                    "Signing certificate mismatch. This APK is not signed by LumaCast's release key."
                )
            }
        }

        return ApkVerificationResult.Verified(sha256Verified = shaVerified)
    }

    private fun hasValidZipHeader(file: File): Boolean {
        val header = ByteArray(2)
        return runCatching {
            file.inputStream().use { it.read(header) } == 2 &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte()
        }.getOrDefault(false)
    }

    companion object {
        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun readInstalledPackageMetadata(context: Context): ParsedApkMetadata? {
            return runCatching {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES.toLong()
                }
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(flags)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, flags.toInt())
                }
                extractMetadata(info)
            }.getOrNull()
        }

        private fun readArchiveMetadata(context: Context, apkFile: File): ParsedApkMetadata? {
            return runCatching {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES.toLong()
                }
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(
                        apkFile.absolutePath,
                        PackageManager.PackageInfoFlags.of(flags)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags.toInt())
                }
                info?.let(::extractMetadata)
            }.getOrNull()
        }

        private fun extractMetadata(info: PackageInfo): ParsedApkMetadata {
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                when {
                    signingInfo == null -> emptyArray()
                    signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners.orEmpty()
                    else -> (signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners).orEmpty()
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures.orEmpty()
            }
            val certDigests = signatures.mapTo(mutableSetOf()) { sig ->
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            return ParsedApkMetadata(
                packageName = info.packageName.orEmpty(),
                versionCode = versionCode,
                signingCertSha256Set = certDigests
            )
        }
    }
}
