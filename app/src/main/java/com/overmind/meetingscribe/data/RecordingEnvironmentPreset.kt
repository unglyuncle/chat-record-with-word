package com.overmind.meetingscribe.data

import com.overmind.meetingscribe.audio.RecordingAudioSourceMode

enum class RecordingEnvironmentPreset(
    val title: String,
    val description: String,
) {
    QUIET_MEETING(
        title = "安静会议室",
        description = "默认推荐。普通会议室、桌面近中距离收音。",
    ),
    FAR_FIELD(
        title = "远距离/大会议室",
        description = "手机离人较远、声音偏小，优先减少漏识别。",
    ),
    NOISY_ROOM(
        title = "嘈杂环境",
        description = "背景声多、容易误触发时使用，优先压住噪声。",
    ),
    INTERVIEW(
        title = "近距离采访",
        description = "手机靠近说话人，音质自然，减少过度放大。",
    ),
    LONG_MEETING(
        title = "长会议省电",
        description = "一小时以上会议，降低实时处理压力和发热。",
    ),
    CUSTOM(
        title = "自定义",
        description = "手动调节下面的高级参数。",
    );

    fun applyTo(settings: AppSettings): AppSettings = when (this) {
        QUIET_MEETING -> settings.copy(
            recordingPreset = this,
            vadThreshold = 0.22f,
            micGain = 4.0f,
            recordingAudioSourceMode = RecordingAudioSourceMode.CLEAN,
            recordingAgc = false,
            liveDiarization = false,
            postDiarization = true,
            diarizationThreshold = 0.50f,
        )
        FAR_FIELD -> settings.copy(
            recordingPreset = this,
            vadThreshold = 0.16f,
            micGain = 7.0f,
            recordingAudioSourceMode = RecordingAudioSourceMode.VOICE_RECOGNITION,
            recordingAgc = true,
            liveDiarization = false,
            postDiarization = true,
            diarizationThreshold = 0.48f,
        )
        NOISY_ROOM -> settings.copy(
            recordingPreset = this,
            vadThreshold = 0.34f,
            micGain = 2.0f,
            recordingAudioSourceMode = RecordingAudioSourceMode.CALL_PROCESSING,
            recordingAgc = false,
            liveDiarization = false,
            postDiarization = true,
            diarizationThreshold = 0.55f,
        )
        INTERVIEW -> settings.copy(
            recordingPreset = this,
            vadThreshold = 0.24f,
            micGain = 2.0f,
            recordingAudioSourceMode = RecordingAudioSourceMode.CLEAN,
            recordingAgc = false,
            liveDiarization = true,
            postDiarization = true,
            diarizationThreshold = 0.50f,
        )
        LONG_MEETING -> settings.copy(
            recordingPreset = this,
            vadThreshold = 0.26f,
            micGain = 3.0f,
            recordingAudioSourceMode = RecordingAudioSourceMode.CLEAN,
            recordingAgc = false,
            liveDiarization = false,
            postDiarization = false,
            diarizationThreshold = 0.50f,
        )
        CUSTOM -> settings.copy(recordingPreset = this)
    }
}
