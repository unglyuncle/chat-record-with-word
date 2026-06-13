package com.overmind.meetingscribe.data

import android.content.Context
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import java.io.File

/**
 * Resolves on-device model file locations (under filesDir/models) and reports readiness.
 * Pure filesystem logic — no network (see [ModelDownloader]).
 */
class ModelManager(context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun isReady(component: ModelComponent): Boolean =
        component.expectedFiles.all { rel ->
            val f = File(modelsDir, rel)
            f.exists() && f.length() >= (component.minBytesByFile[rel] ?: 1L)
        }

    private fun absolute(rel: String) = File(modelsDir, rel).absolutePath

    fun senseVoiceModel() = absolute(ModelComponent.SENSE_VOICE.expectedFiles[0])
    fun senseVoiceTokens() = absolute(ModelComponent.SENSE_VOICE.expectedFiles[1])
    fun sileroVad() = absolute(ModelComponent.SILERO_VAD.expectedFiles[0])
    fun paraformerModel() = absolute(ModelComponent.PARAFORMER.expectedFiles[0])
    fun paraformerTokens() = absolute(ModelComponent.PARAFORMER.expectedFiles[1])
    fun zipformerCtcModel(kind: OfflineModelKind) = absolute(zipformerComponent(kind).expectedFiles[0])
    fun zipformerCtcTokens(kind: OfflineModelKind) = absolute(zipformerComponent(kind).expectedFiles[1])
    fun funAsrNanoEncoderAdaptor() = absolute(ModelComponent.FUNASR_NANO.expectedFiles[0])
    fun funAsrNanoLlm() = absolute(ModelComponent.FUNASR_NANO.expectedFiles[1])
    fun funAsrNanoEmbedding() = absolute(ModelComponent.FUNASR_NANO.expectedFiles[2])
    fun funAsrNanoTokenizer() =
        absolute(ModelComponent.FUNASR_NANO.expectedFiles[3].substringBeforeLast('/'))
    fun qwen3ConvFrontend() = absolute(ModelComponent.QWEN3_ASR.expectedFiles[0])
    fun qwen3Encoder() = absolute(ModelComponent.QWEN3_ASR.expectedFiles[1])
    fun qwen3Decoder() = absolute(ModelComponent.QWEN3_ASR.expectedFiles[2])
    fun qwen3Tokenizer() =
        absolute(ModelComponent.QWEN3_ASR.expectedFiles[3].substringBeforeLast('/'))
    fun punctModel() = absolute(ModelComponent.PUNCT_CT.expectedFiles[0])
    fun segmentationModel() = absolute(ModelComponent.PYANNOTE_SEG.expectedFiles[0])
    fun embeddingModel() = absolute(ModelComponent.CAMPP_EMBED.expectedFiles[0])

    /** ASR + VAD components for a given offline model. Punctuation is optional post-processing. */
    fun requiredForAsr(kind: OfflineModelKind): List<ModelComponent> = when (kind) {
        OfflineModelKind.SENSE_VOICE -> listOf(ModelComponent.SENSE_VOICE, ModelComponent.SILERO_VAD)
        OfflineModelKind.PARAFORMER ->
            listOf(ModelComponent.PARAFORMER, ModelComponent.SILERO_VAD)
        OfflineModelKind.ZIPFORMER_CTC -> listOf(ModelComponent.ZIPFORMER_CTC)
        OfflineModelKind.ZIPFORMER_CTC_XLARGE -> listOf(ModelComponent.ZIPFORMER_CTC_XLARGE)
        OfflineModelKind.FUNASR_NANO -> listOf(ModelComponent.FUNASR_NANO, ModelComponent.SILERO_VAD)
        OfflineModelKind.QWEN3_ASR -> listOf(ModelComponent.QWEN3_ASR, ModelComponent.SILERO_VAD)
    }

    private fun zipformerComponent(kind: OfflineModelKind): ModelComponent = when (kind) {
        OfflineModelKind.ZIPFORMER_CTC -> ModelComponent.ZIPFORMER_CTC
        OfflineModelKind.ZIPFORMER_CTC_XLARGE -> ModelComponent.ZIPFORMER_CTC_XLARGE
        else -> error("$kind is not a Zipformer CTC model")
    }

    val diarizationComponents = listOf(ModelComponent.PYANNOTE_SEG, ModelComponent.CAMPP_EMBED)

    fun asrReady(kind: OfflineModelKind) = requiredForAsr(kind).all { isReady(it) }
    fun diarizationReady() = diarizationComponents.all { isReady(it) }
    fun embeddingReady() = isReady(ModelComponent.CAMPP_EMBED)

    /** Removes the installed files/dir for a component (frees storage). */
    fun delete(component: ModelComponent) {
        val top = component.expectedFiles.first().substringBefore('/')
        val target = File(modelsDir, top)
        if (target.exists()) target.deleteRecursively()
    }
}
