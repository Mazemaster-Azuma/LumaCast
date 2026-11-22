package com.example.core.cast

import android.content.Context
import com.example.MainActivity
import com.example.core.common.AppPreferencesRepository
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Official Google Cast Sender SDK OptionsProvider registered in AndroidManifest.xml.
 *
 * Automatically connects to the user's registered LumaCast Custom Web Receiver App ID
 * (configured in Settings or DEFAULT_CUSTOM_RECEIVER_APP_ID) for full AudioTracksManager
 * and TextTracksManager support, or falls back cleanly to Google's Default Media Receiver
 * when no Custom Receiver App ID has been registered yet.
 */
class LumaCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val buttonActions = listOf(
            MediaIntentReceiver.ACTION_REWIND,
            MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
            MediaIntentReceiver.ACTION_FORWARD,
            MediaIntentReceiver.ACTION_STOP_CASTING
        )
        val compatButtonIndices = intArrayOf(1, 3)

        val notificationOptions = NotificationOptions.Builder()
            .setActions(buttonActions, compatButtonIndices)
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        val launchOptions = LaunchOptions.Builder()
            .setAndroidReceiverCompatible(true)
            .build()

        val configuredCustomId = AppPreferencesRepository.readCustomReceiverAppIdSync(context)
            .ifBlank { DEFAULT_CUSTOM_RECEIVER_APP_ID }
        val receiverAppId = configuredCustomId.ifBlank {
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        }

        return CastOptions.Builder()
            .setReceiverApplicationId(receiverAppId)
            .setCastMediaOptions(mediaOptions)
            .setLaunchOptions(launchOptions)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /**
         * Replace with your 8-character Google Cast SDK Developer Console Application ID
         * after deploying `/receiver/` over HTTPS, or configure it directly inside
         * LumaCast -> Settings -> Custom Web Receiver App ID.
         */
        const val DEFAULT_CUSTOM_RECEIVER_APP_ID = ""

        fun isCustomReceiverConfigured(context: Context): Boolean {
            val id = AppPreferencesRepository.readCustomReceiverAppIdSync(context)
                .ifBlank { DEFAULT_CUSTOM_RECEIVER_APP_ID }
            return id.isNotBlank() && id != CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        }
    }
}
