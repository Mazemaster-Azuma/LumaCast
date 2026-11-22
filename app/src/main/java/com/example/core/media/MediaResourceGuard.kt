package com.example.core.media

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.example.core.common.ResourceHealthStatus
import java.io.File

/**
 * Monitors available temporary storage space and Android thermal status
 * before and during media remuxing or transcoding to prevent OOM, disk exhaustion,
 * or severe device overheating.
 */
class MediaResourceGuard(private val context: Context) {

    fun availableCacheBytes(): Long {
        return runCatching {
            val dir: File = context.cacheDir
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        }.getOrDefault(0L)
    }

    fun hasEnoughDiskSpace(estimatedOutputBytes: Long): Boolean {
        val required = estimatedOutputBytes.coerceAtLeast(32L * 1024 * 1024) + SAFETY_HEADROOM_BYTES
        return availableCacheBytes() >= required
    }

    fun requiredDiskBytes(estimatedOutputBytes: Long): Long {
        return estimatedOutputBytes.coerceAtLeast(32L * 1024 * 1024) + SAFETY_HEADROOM_BYTES
    }

    fun currentThermalStatus(): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Nominal" to false
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "Unknown" to false
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal" to false
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm (Light)" to false
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm (Moderate)" to false
            PowerManager.THERMAL_STATUS_SEVERE -> "Hot (Severe)" to true
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical" to true
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency" to true
            else -> "Nominal" to false
        }
    }

    fun snapshot(ffmpegAvailable: Boolean): ResourceHealthStatus {
        val (label, throttled) = currentThermalStatus()
        return ResourceHealthStatus(
            availableDiskBytes = availableCacheBytes(),
            thermalStatusLabel = label,
            isThermallyThrottled = throttled,
            ffmpegAvailable = ffmpegAvailable
        )
    }

    companion object {
        const val SAFETY_HEADROOM_BYTES = 256L * 1024 * 1024 // 256 MB safety buffer
    }
}
