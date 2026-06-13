package com.overmind.meetingscribe.asr.offline

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * Authoritative speaker diarization over a whole recording: pyannote segmentation + CAM++ speaker
 * embeddings + clustering. Non-streaming — it consumes the entire 16 kHz mono [FloatArray] at once.
 * With [numClusters] = -1 the speaker count is auto-decided from [threshold].
 */
class OfflineDiarizer(
    segmentationModelPath: String,
    embeddingModelPath: String,
    threshold: Float = 0.5f,
    numThreads: Int = 2,
) {
    data class Seg(val startSec: Float, val endSec: Float, val speaker: Int)

    private val sd = OfflineSpeakerDiarization(
        assetManager = null,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModelPath),
                numThreads = numThreads,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModelPath,
                numThreads = numThreads,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )

    val sampleRate: Int get() = sd.sampleRate()

    fun diarize(samples: FloatArray, onProgress: ((Float) -> Unit)? = null): List<Seg> {
        val result = if (onProgress != null) {
            sd.processWithCallback(
                samples = samples,
                callback = { processed, total, _ ->
                    if (total > 0) onProgress(processed.toFloat() / total)
                    0
                },
            )
        } else {
            sd.process(samples)
        }
        return result.map { Seg(it.start, it.end, it.speaker) }
    }

    fun release() {
        runCatching { sd.release() }
    }
}
