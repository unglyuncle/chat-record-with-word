package com.overmind.meetingscribe.asr.offline

import com.overmind.meetingscribe.R

/** Selectable on-device ASR model for the offline (sherpa-onnx) engine. */
enum class OfflineModelKind(val labelRes: Int, val modelName: String) {
    /** Multilingual (zh/en/ja/ko/yue), with emotion/event tags. Default. */
    SENSE_VOICE(R.string.offline_model_sensevoice, "SenseVoice Small int8"),

    /** Mandarin-focused, higher CN accuracy; punctuation added via a separate CT-Transformer. */
    PARAFORMER(R.string.offline_model_paraformer, "Paraformer zh int8"),

    /** Mandarin streaming CTC model; lower latency than VAD + offline segment decoding. */
    ZIPFORMER_CTC(R.string.offline_model_zipformer_ctc, "Zipformer CTC zh int8"),

    /** Larger Mandarin streaming CTC model for better accuracy on capable devices. */
    ZIPFORMER_CTC_XLARGE(R.string.offline_model_zipformer_ctc_xlarge, "Zipformer CTC zh XLarge int8"),

    /** High-accuracy Chinese/English/Japanese offline model; large LLM-style decoder. */
    FUNASR_NANO(R.string.offline_model_funasr_nano, "FunASR Nano int8"),

    /** Qwen3-ASR 0.6B int8 offline model; highest-cost experimental option. */
    QWEN3_ASR(R.string.offline_model_qwen3_asr, "Qwen3-ASR 0.6B int8"),
}

val OfflineModelKind.isStreaming: Boolean
    get() = this == OfflineModelKind.ZIPFORMER_CTC || this == OfflineModelKind.ZIPFORMER_CTC_XLARGE

val OfflineModelKind.usesExternalPunctuation: Boolean
    get() = this == OfflineModelKind.SENSE_VOICE ||
        this == OfflineModelKind.PARAFORMER ||
        this == OfflineModelKind.ZIPFORMER_CTC ||
        this == OfflineModelKind.ZIPFORMER_CTC_XLARGE
