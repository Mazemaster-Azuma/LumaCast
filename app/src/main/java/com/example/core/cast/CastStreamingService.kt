package com.example.core.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.LumaCastApplication
import com.example.MainActivity
import com.example.R

/**
 * Android 14+ compliant foreground service (mediaPlayback) that keeps the local
 * embedded HTTP streaming server and Wi-Fi lock alive when the app moves to the
 * background or the user locks their phone. Automatically stops when casting ends.
 */
class CastStreamingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val app = application as? LumaCastApplication
                app?.container?.castController?.disconnect()
                app?.container?.httpServer?.stop()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val title = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: getString(R.string.app_name)
                val device = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "Chromecast"
                val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Streaming over local Wi-Fi"
                acquireLocks()
                val notification = buildNotification(title, device, statusText)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaCast::HttpStreamWakeLock")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // 4h safety cap
                }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "LumaCast::HttpStreamWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        videoTitle: String,
        deviceName: String,
        statusText: String
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CastStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_casting_title, deviceName))
            .setContentText("$videoTitle · $statusText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lumacast_streaming_channel"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.example.action.START_CAST_STREAM"
        private const val ACTION_STOP = "com.example.action.STOP_CAST_STREAM"
        private const val EXTRA_VIDEO_TITLE = "extra_video_title"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context, videoTitle: String, deviceName: String, statusText: String) {
            val intent = Intent(context, CastStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_TITLE, videoTitle)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CastStreamingService::class.java))
            }
        }
    }
}
