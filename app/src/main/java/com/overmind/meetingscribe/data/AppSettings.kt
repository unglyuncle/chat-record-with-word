package com.overmind.meetingscribe.data

import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import com.overmind.meetingscribe.audio.RecordingAudioSourceMode
import com.overmind.meetingscribe.audio.RecordingFormat

/**
 * User-configurable settings, persisted via [SettingsRepository]. Credential defaults come from
 * BuildConfig (populated from local.properties at build time) so a developer can pre-bake keys
 * without committing them; the UI can still override at runtime.
 */
data class AppSettings(
    val engine: EngineType = EngineType.SHERPA_OFFLINE,
    val offlineModel: OfflineModelKind = OfflineModelKind.SENSE_VOICE,

    /** Provisional speaker labels while recording (offline engine only). */
    val liveDiarization: Boolean = false,
    /** Run the authoritative whole-recording diarization pass on stop (offline engine only). */
    val postDiarization: Boolean = true,
    /** Cosine threshold for speaker clustering; lower = more speakers. */
    val diarizationThreshold: Float = 0.5f,

    /** Offline VAD speech threshold. Lower = more sensitive, catches quieter far-field speech. */
    val vadThreshold: Float = 0.22f,
    /** Offline input gain multiplier applied to captured PCM before VAD/ASR. 1.0 = unchanged. */
    val micGain: Float = 4.0f,
    val recordingFormat: RecordingFormat = RecordingFormat.M4A_AAC,
    val recordingAudioSourceMode: RecordingAudioSourceMode = RecordingAudioSourceMode.CLEAN,
    val recordingAgc: Boolean = false,

    // 讯飞 RTASR
    val xfyunAppId: String = "",
    val xfyunApiKey: String = "",
    /** Enable role separation. NOTE: the enabling param differs by product (standard vs 大模型);
     *  see XfyunRtasrEngine. Off by default until verified against your console product. */
    val xfyunRoleSeparation: Boolean = false,

    // 阿里百炼 DashScope (realtime WS — no speaker diarization available)
    val dashScopeApiKey: String = "",
    val dashScopeModel: String = "paraformer-realtime-v2",

    // 火山引擎 豆包 大模型流式 ASR
    val volcAppId: String = "",
    val volcAccessKey: String = "",
    val volcResourceId: String = "volc.bigasr.sauc.duration",
)
