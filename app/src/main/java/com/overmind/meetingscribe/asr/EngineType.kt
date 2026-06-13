package com.overmind.meetingscribe.asr

import com.overmind.meetingscribe.R

/**
 * The four switchable ASR backends. [online] engines need network + credentials;
 * the offline engine runs fully on-device after a one-time model download.
 */
enum class EngineType(val labelRes: Int, val online: Boolean) {
    SHERPA_OFFLINE(R.string.engine_sherpa_offline, online = false),
    XFYUN_RTASR(R.string.engine_xfyun, online = true),
    ALI_FUNASR(R.string.engine_ali, online = true),
    VOLC_DOUBAO(R.string.engine_volc, online = true),
}
