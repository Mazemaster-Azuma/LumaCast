package com.example.core.common

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lumaCastDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lumacast_preferences"
)

@Immutable
data class UserSettings(
    val receiverProfile: ReceiverProfile = ReceiverProfile.ULTRA_4K_HDR,
    val customReceiverAppId: String = "",
    val autoRemuxEnabled: Boolean = true,
    val allowHardwareTranscode: Boolean = true,
    val maxTranscodeHeight: Int = 1080,
    val preferredAudioLanguage: String = "auto",
    val preferAc3Passthrough: Boolean = true,
    val preferredSubtitleLanguage: String = "auto",
    val subtitleDelayMs: Long = 0L,
    val subtitleAppearance: SubtitleAppearanceSettings = SubtitleAppearanceSettings(),
    val preferredServerPort: Int = 0, // 0 = auto ephemeral port
    val keepScreenOnRemote: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortOption: SortOption = SortOption.DATE_DESC,
    val safFolderUris: Set<String> = emptySet(),
    val pickedVideoUris: Set<String> = emptySet(),
    val githubUpdateRepo: String = BuildConfig.GITHUB_REPO,
    val autoCheckUpdates: Boolean = true,
    val skippedUpdateTag: String = "",
    val lastUpdateCheckMs: Long = 0L
)

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val RECEIVER_PROFILE = stringPreferencesKey("receiver_profile")
        val CUSTOM_RECEIVER_APP_ID = stringPreferencesKey("custom_receiver_app_id")
        val AUTO_REMUX = booleanPreferencesKey("auto_remux")
        val ALLOW_TRANSCODE = booleanPreferencesKey("allow_transcode")
        val MAX_TRANSCODE_HEIGHT = intPreferencesKey("max_transcode_height")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        val PREFER_AC3_PASSTHROUGH = booleanPreferencesKey("prefer_ac3_passthrough")
        val PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_language")
        val SUBTITLE_DELAY_MS = longPreferencesKey("subtitle_delay_ms")
        val SUB_FONT_SCALE = floatPreferencesKey("sub_font_scale")
        val SUB_FONT_FAMILY = stringPreferencesKey("sub_font_family")
        val SUB_FG_COLOR = longPreferencesKey("sub_fg_color")
        val SUB_BG_COLOR = longPreferencesKey("sub_bg_color")
        val SUB_BG_OPACITY = floatPreferencesKey("sub_bg_opacity")
        val SUB_EDGE_TYPE = stringPreferencesKey("sub_edge_type")
        val SUB_TEXT_ALIGN = stringPreferencesKey("sub_text_align")
        val SUB_BOTTOM_OFFSET_PCT = intPreferencesKey("sub_bottom_offset_pct")
        val PREFERRED_PORT = intPreferencesKey("preferred_server_port")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_remote")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SORT_OPTION = stringPreferencesKey("sort_option")
        val SAF_FOLDERS = stringSetPreferencesKey("saf_folder_uris")
        val PICKED_VIDEOS = stringSetPreferencesKey("picked_video_uris")
        val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val SKIPPED_UPDATE_TAG = stringPreferencesKey("skipped_update_tag")
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
    }

    val settingsFlow: Flow<UserSettings> = context.lumaCastDataStore.data.map { prefs ->
        val appearance = SubtitleAppearanceSettings(
            fontScale = prefs[Keys.SUB_FONT_SCALE] ?: 1.0f,
            fontFamily = prefs[Keys.SUB_FONT_FAMILY]?.let {
                runCatching { SubtitleFontFamily.valueOf(it) }.getOrNull()
            } ?: SubtitleFontFamily.SANS_SERIF,
            foregroundColorArgb = prefs[Keys.SUB_FG_COLOR] ?: 0xFFFFFFFFL,
            backgroundColorArgb = prefs[Keys.SUB_BG_COLOR] ?: 0x00000000L,
            backgroundOpacity = prefs[Keys.SUB_BG_OPACITY] ?: 0.72f,
            edgeType = prefs[Keys.SUB_EDGE_TYPE]?.let {
                runCatching { SubtitleEdgeType.valueOf(it) }.getOrNull()
            } ?: SubtitleEdgeType.DROP_SHADOW,
            textAlign = prefs[Keys.SUB_TEXT_ALIGN]?.let {
                runCatching { SubtitleTextAlign.valueOf(it) }.getOrNull()
            } ?: SubtitleTextAlign.CENTER,
            bottomOffsetPercent = prefs[Keys.SUB_BOTTOM_OFFSET_PCT] ?: 8
        )

        UserSettings(
            receiverProfile = prefs[Keys.RECEIVER_PROFILE]?.let {
                runCatching { ReceiverProfile.valueOf(it) }.getOrNull()
            } ?: ReceiverProfile.ULTRA_4K_HDR,
            customReceiverAppId = prefs[Keys.CUSTOM_RECEIVER_APP_ID] ?: "",
            autoRemuxEnabled = prefs[Keys.AUTO_REMUX] ?: true,
            allowHardwareTranscode = prefs[Keys.ALLOW_TRANSCODE] ?: true,
            maxTranscodeHeight = prefs[Keys.MAX_TRANSCODE_HEIGHT] ?: 1080,
            preferredAudioLanguage = prefs[Keys.PREFERRED_AUDIO_LANG] ?: "auto",
            preferAc3Passthrough = prefs[Keys.PREFER_AC3_PASSTHROUGH] ?: true,
            preferredSubtitleLanguage = prefs[Keys.PREFERRED_SUBTITLE_LANG] ?: "auto",
            subtitleDelayMs = prefs[Keys.SUBTITLE_DELAY_MS] ?: 0L,
            subtitleAppearance = appearance,
            preferredServerPort = prefs[Keys.PREFERRED_PORT] ?: 0,
            keepScreenOnRemote = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            viewMode = prefs[Keys.VIEW_MODE]?.let {
                runCatching { ViewMode.valueOf(it) }.getOrNull()
            } ?: ViewMode.LIST,
            sortOption = prefs[Keys.SORT_OPTION]?.let {
                runCatching { SortOption.valueOf(it) }.getOrNull()
            } ?: SortOption.DATE_DESC,
            safFolderUris = prefs[Keys.SAF_FOLDERS] ?: emptySet(),
            pickedVideoUris = prefs[Keys.PICKED_VIDEOS] ?: emptySet(),
            githubUpdateRepo = prefs[Keys.GITHUB_UPDATE_REPO]
                ?.takeIf { it.isNotBlank() }
                ?: BuildConfig.GITHUB_REPO,
            autoCheckUpdates = prefs[Keys.AUTO_CHECK_UPDATES] ?: true,
            skippedUpdateTag = prefs[Keys.SKIPPED_UPDATE_TAG] ?: "",
            lastUpdateCheckMs = prefs[Keys.LAST_UPDATE_CHECK_MS] ?: 0L
        )
    }

    suspend fun setReceiverProfile(profile: ReceiverProfile) {
        context.lumaCastDataStore.edit { it[Keys.RECEIVER_PROFILE] = profile.name }
    }

    suspend fun setCustomReceiverAppId(appId: String) {
        val cleaned = appId.trim().uppercase()
        context.lumaCastDataStore.edit { it[Keys.CUSTOM_RECEIVER_APP_ID] = cleaned }
        // Mirror synchronously in SharedPreferences so LumaCastOptionsProvider can read it during CastContext init
        context.getSharedPreferences(RECEIVER_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RECEIVER_APP_ID_KEY, cleaned)
            .apply()
    }

    suspend fun setAutoRemuxEnabled(enabled: Boolean) {
        context.lumaCastDataStore.edit { it[Keys.AUTO_REMUX] = enabled }
    }

    suspend fun setAllowHardwareTranscode(enabled: Boolean) {
        context.lumaCastDataStore.edit { it[Keys.ALLOW_TRANSCODE] = enabled }
    }

    suspend fun setMaxTranscodeHeight(height: Int) {
        context.lumaCastDataStore.edit { it[Keys.MAX_TRANSCODE_HEIGHT] = height }
    }

    suspend fun setPreferredAudioLanguage(lang: String) {
        context.lumaCastDataStore.edit { it[Keys.PREFERRED_AUDIO_LANG] = lang }
    }

    suspend fun setPreferAc3Passthrough(enabled: Boolean) {
        context.lumaCastDataStore.edit { it[Keys.PREFER_AC3_PASSTHROUGH] = enabled }
    }

    suspend fun setPreferredSubtitleLanguage(lang: String) {
        context.lumaCastDataStore.edit { it[Keys.PREFERRED_SUBTITLE_LANG] = lang }
    }

    suspend fun setSubtitleDelayMs(delayMs: Long) {
        context.lumaCastDataStore.edit { it[Keys.SUBTITLE_DELAY_MS] = delayMs.coerceIn(-30_000L, 30_000L) }
    }

    suspend fun setSubtitleAppearance(appearance: SubtitleAppearanceSettings) {
        context.lumaCastDataStore.edit {
            it[Keys.SUB_FONT_SCALE] = appearance.fontScale.coerceIn(0.6f, 2.0f)
            it[Keys.SUB_FONT_FAMILY] = appearance.fontFamily.name
            it[Keys.SUB_FG_COLOR] = appearance.foregroundColorArgb
            it[Keys.SUB_BG_COLOR] = appearance.backgroundColorArgb
            it[Keys.SUB_BG_OPACITY] = appearance.backgroundOpacity.coerceIn(0f, 1f)
            it[Keys.SUB_EDGE_TYPE] = appearance.edgeType.name
            it[Keys.SUB_TEXT_ALIGN] = appearance.textAlign.name
            it[Keys.SUB_BOTTOM_OFFSET_PCT] = appearance.bottomOffsetPercent.coerceIn(2, 30)
        }
    }

    suspend fun setPreferredServerPort(port: Int) {
        context.lumaCastDataStore.edit { it[Keys.PREFERRED_PORT] = port.coerceIn(0, 65535) }
    }

    suspend fun setKeepScreenOnRemote(enabled: Boolean) {
        context.lumaCastDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.lumaCastDataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setSortOption(option: SortOption) {
        context.lumaCastDataStore.edit { it[Keys.SORT_OPTION] = option.name }
    }

    suspend fun addSafFolderUri(uriString: String) {
        context.lumaCastDataStore.edit { prefs ->
            val current = prefs[Keys.SAF_FOLDERS] ?: emptySet()
            prefs[Keys.SAF_FOLDERS] = current + uriString
        }
    }

    suspend fun removeSafFolderUri(uriString: String) {
        context.lumaCastDataStore.edit { prefs ->
            val current = prefs[Keys.SAF_FOLDERS] ?: emptySet()
            prefs[Keys.SAF_FOLDERS] = current - uriString
        }
    }

    suspend fun addPickedVideoUris(uriStrings: Collection<String>) {
        context.lumaCastDataStore.edit { prefs ->
            val current = prefs[Keys.PICKED_VIDEOS] ?: emptySet()
            prefs[Keys.PICKED_VIDEOS] = current + uriStrings
        }
    }

    suspend fun removePickedVideoUri(uriString: String) {
        context.lumaCastDataStore.edit { prefs ->
            val current = prefs[Keys.PICKED_VIDEOS] ?: emptySet()
            prefs[Keys.PICKED_VIDEOS] = current - uriString
        }
    }

    suspend fun setGithubUpdateRepo(repoSlug: String) {
        context.lumaCastDataStore.edit { it[Keys.GITHUB_UPDATE_REPO] = repoSlug.trim() }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.lumaCastDataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setSkippedUpdateTag(tagName: String) {
        context.lumaCastDataStore.edit { it[Keys.SKIPPED_UPDATE_TAG] = tagName.trim() }
    }

    suspend fun setLastUpdateCheckMs(timestampMs: Long) {
        context.lumaCastDataStore.edit { it[Keys.LAST_UPDATE_CHECK_MS] = timestampMs }
    }

    companion object {
        const val RECEIVER_PREFS_NAME = "lumacast_receiver_sync"
        const val RECEIVER_APP_ID_KEY = "custom_receiver_app_id"

        fun readCustomReceiverAppIdSync(context: Context): String {
            return context.getSharedPreferences(RECEIVER_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RECEIVER_APP_ID_KEY, "")
                .orEmpty()
                .trim()
        }
    }
}
