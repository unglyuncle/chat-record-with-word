package com.overmind.meetingscribe.data

/**
 * A downloadable on-device model artifact from the sherpa-onnx release assets.
 *
 * [archive] = true means the URL is a `.tar.bz2` that unpacks to a directory; false means a bare
 * `.onnx` file. [expectedFiles] are paths (relative to the models dir) that must exist after a
 * successful install — used both to place bare files and to verify readiness.
 */
enum class ModelComponent(
    val displayName: String,
    val url: String,
    val archive: Boolean,
    val expectedFiles: List<String>,
    val approxSize: String,
) {
    SENSE_VOICE(
        displayName = "SenseVoice Small int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt",
        ),
        approxSize = "≈ 230 MB",
    ),
    SILERO_VAD(
        displayName = "Silero VAD",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        archive = false,
        expectedFiles = listOf("silero_vad.onnx"),
        approxSize = "≈ 2 MB",
    ),
    PARAFORMER(
        displayName = "Paraformer zh int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-paraformer-zh-2023-09-14/model.int8.onnx",
            "sherpa-onnx-paraformer-zh-2023-09-14/tokens.txt",
        ),
        approxSize = "≈ 220 MB",
    ),
    ZIPFORMER_CTC(
        displayName = "Zipformer CTC zh int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/model.int8.onnx",
            "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30/tokens.txt",
        ),
        approxSize = "≈ 128 MB",
    ),
    ZIPFORMER_CTC_XLARGE(
        displayName = "Zipformer CTC zh XLarge int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-ctc-zh-xlarge-int8-2025-06-30.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-streaming-zipformer-ctc-zh-xlarge-int8-2025-06-30/model.int8.onnx",
            "sherpa-onnx-streaming-zipformer-ctc-zh-xlarge-int8-2025-06-30/tokens.txt",
        ),
        approxSize = "≈ 590 MB",
    ),
    FUNASR_NANO(
        displayName = "FunASR Nano int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-funasr-nano-int8-2025-12-30/encoder_adaptor.int8.onnx",
            "sherpa-onnx-funasr-nano-int8-2025-12-30/llm.int8.onnx",
            "sherpa-onnx-funasr-nano-int8-2025-12-30/embedding.int8.onnx",
            "sherpa-onnx-funasr-nano-int8-2025-12-30/Qwen3-0.6B/tokenizer.json",
        ),
        approxSize = "≈ 842 MB",
    ),
    QWEN3_ASR(
        displayName = "Qwen3-ASR 0.6B int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/conv_frontend.onnx",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/encoder.int8.onnx",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/decoder.int8.onnx",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/tokenizer/tokenizer_config.json",
        ),
        approxSize = "≈ 879 MB",
    ),
    PUNCT_CT(
        displayName = "CT-Transformer punctuation",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12.tar.bz2",
        archive = true,
        expectedFiles = listOf(
            "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12/model.onnx",
        ),
        approxSize = "≈ 280 MB",
    ),
    PYANNOTE_SEG(
        displayName = "Pyannote speaker segmentation",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        archive = true,
        expectedFiles = listOf("sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"),
        approxSize = "≈ 6 MB",
    ),

    // NOTE: the release tag "speaker-recongition-models" is misspelled upstream — keep as-is.
    CAMPP_EMBED(
        displayName = "3D-Speaker CAM++ embedding",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
        archive = false,
        expectedFiles = listOf("3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"),
        approxSize = "≈ 28 MB",
    ),
}
