package com.overmind.meetingscribe.audio

enum class RecordingFormat(val extension: String, val mimeType: String) {
    M4A_AAC("m4a", "audio/mp4"),
    WAV("wav", "audio/wav"),
}

enum class RecordingAudioSourceMode {
    CLEAN,
    VOICE_RECOGNITION,
    CALL_PROCESSING,
}
