package com.overmind.meetingscribe.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.overmind.meetingscribe.BuildConfig
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import com.overmind.meetingscribe.audio.RecordingAudioSourceMode
import com.overmind.meetingscribe.audio.RecordingFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persists [AppSettings] via Preferences DataStore. Credential fields fall back to BuildConfig
 * values (populated from local.properties at build time) until the user overrides them in the UI.
 */
class SettingsRepository(context: Context) {

    private val ds = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = ds.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        ds.edit { prefs -> transform(prefs.toSettings()).writeInto(prefs) }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        engine = enumOrDefault(this[Keys.ENGINE], EngineType.SHERPA_OFFLINE),
        offlineModel = enumOrDefault(this[Keys.OFFLINE_MODEL], OfflineModelKind.SENSE_VOICE),
        liveDiarization = this[Keys.LIVE_DIAR] ?: false,
        postDiarization = this[Keys.POST_DIAR] ?: true,
        diarizationThreshold = this[Keys.DIAR_THRESHOLD] ?: 0.5f,
        vadThreshold = farFieldDefault(this[Keys.VAD_THRESHOLD], oldDefault = 0.35f, newDefault = 0.22f),
        micGain = farFieldDefault(this[Keys.MIC_GAIN], oldDefault = 1.5f, newDefault = 4.0f),
        recordingFormat = enumOrDefault(this[Keys.RECORDING_FORMAT], RecordingFormat.M4A_AAC),
        recordingAudioSourceMode = enumOrDefault(this[Keys.RECORDING_SOURCE], RecordingAudioSourceMode.CLEAN),
        recordingAgc = this[Keys.RECORDING_AGC] ?: false,
        xfyunAppId = this[Keys.XF_APP_ID] ?: BuildConfig.XFYUN_APP_ID,
        xfyunApiKey = this[Keys.XF_API_KEY] ?: BuildConfig.XFYUN_API_KEY,
        xfyunRoleSeparation = this[Keys.XF_ROLE] ?: false,
        dashScopeApiKey = this[Keys.ALI_KEY] ?: BuildConfig.DASHSCOPE_API_KEY,
        dashScopeModel = this[Keys.ALI_MODEL] ?: "paraformer-realtime-v2",
        volcAppId = this[Keys.VOLC_APP_ID] ?: BuildConfig.VOLC_APP_ID,
        volcAccessKey = this[Keys.VOLC_ACCESS_KEY] ?: BuildConfig.VOLC_ACCESS_KEY,
        volcResourceId = this[Keys.VOLC_RESOURCE_ID] ?: BuildConfig.VOLC_RESOURCE_ID,
    )

    private fun AppSettings.writeInto(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[Keys.ENGINE] = engine.name
        prefs[Keys.OFFLINE_MODEL] = offlineModel.name
        prefs[Keys.LIVE_DIAR] = liveDiarization
        prefs[Keys.POST_DIAR] = postDiarization
        prefs[Keys.DIAR_THRESHOLD] = diarizationThreshold
        prefs[Keys.VAD_THRESHOLD] = vadThreshold
        prefs[Keys.MIC_GAIN] = micGain
        prefs[Keys.RECORDING_FORMAT] = recordingFormat.name
        prefs[Keys.RECORDING_SOURCE] = recordingAudioSourceMode.name
        prefs[Keys.RECORDING_AGC] = recordingAgc
        prefs[Keys.XF_APP_ID] = xfyunAppId
        prefs[Keys.XF_API_KEY] = xfyunApiKey
        prefs[Keys.XF_ROLE] = xfyunRoleSeparation
        prefs[Keys.ALI_KEY] = dashScopeApiKey
        prefs[Keys.ALI_MODEL] = dashScopeModel
        prefs[Keys.VOLC_APP_ID] = volcAppId
        prefs[Keys.VOLC_ACCESS_KEY] = volcAccessKey
        prefs[Keys.VOLC_RESOURCE_ID] = volcResourceId
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private fun farFieldDefault(value: Float?, oldDefault: Float, newDefault: Float): Float =
        if (value == null || abs(value - oldDefault) < 0.001f) newDefault else value

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val OFFLINE_MODEL = stringPreferencesKey("offline_model")
        val LIVE_DIAR = booleanPreferencesKey("live_diarization")
        val POST_DIAR = booleanPreferencesKey("post_diarization")
        val DIAR_THRESHOLD = floatPreferencesKey("diarization_threshold")
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val MIC_GAIN = floatPreferencesKey("mic_gain")
        val RECORDING_FORMAT = stringPreferencesKey("recording_format")
        val RECORDING_SOURCE = stringPreferencesKey("recording_source")
        val RECORDING_AGC = booleanPreferencesKey("recording_agc")
        val XF_APP_ID = stringPreferencesKey("xfyun_app_id")
        val XF_API_KEY = stringPreferencesKey("xfyun_api_key")
        val XF_ROLE = booleanPreferencesKey("xfyun_role_separation")
        val ALI_KEY = stringPreferencesKey("dashscope_api_key")
        val ALI_MODEL = stringPreferencesKey("dashscope_model")
        val VOLC_APP_ID = stringPreferencesKey("volc_app_id")
        val VOLC_ACCESS_KEY = stringPreferencesKey("volc_access_key")
        val VOLC_RESOURCE_ID = stringPreferencesKey("volc_resource_id")
    }
}
