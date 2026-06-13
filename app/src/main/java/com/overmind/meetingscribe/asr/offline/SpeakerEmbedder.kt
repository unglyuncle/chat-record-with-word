package com.overmind.meetingscribe.asr.offline

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.overmind.meetingscribe.audio.AudioConstants
import kotlin.math.sqrt

/**
 * Wraps sherpa-onnx's [SpeakerEmbeddingExtractor] (CAM++/3D-Speaker) to turn an audio segment
 * into a fixed-length speaker embedding. Used by [LiveSpeakerClusterer] for provisional, real-time
 * speaker labels while recording.
 */
class SpeakerEmbedder(modelPath: String, numThreads: Int = 1) {

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = numThreads,
            provider = "cpu",
        ),
    )

    val dim: Int get() = extractor.dim()

    fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, AudioConstants.SAMPLE_RATE)
        stream.inputFinished()
        val embedding = extractor.compute(stream)
        stream.release()
        return embedding
    }

    fun release() {
        runCatching { extractor.release() }
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return -1f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return dot / (sqrt(na) * sqrt(nb) + 1e-8f)
        }
    }
}
