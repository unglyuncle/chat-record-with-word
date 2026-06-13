package com.overmind.meetingscribe.asr.offline

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.overmind.meetingscribe.asr.ASREngine
import com.overmind.meetingscribe.asr.ASRListener
import com.overmind.meetingscribe.asr.EngineState
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.audio.AudioConstants
import com.overmind.meetingscribe.audio.WavIo
import com.overmind.meetingscribe.audio.WavWriter
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.data.ModelComponent
import com.overmind.meetingscribe.data.ModelManager
import com.overmind.meetingscribe.util.RealtimeAudioQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * On-device engine: silero VAD segments the stream; each segment is transcribed by an offline
 * recognizer (SenseVoice or Paraformer). While recording, a [LiveSpeakerClusterer] attaches
 * provisional speaker labels; on [stop] the whole recording is re-diarized with [OfflineDiarizer]
 * and labels are corrected via [ASRListener.onSpeakersCorrected].
 *
 * Audio flows in on the recorder thread ([feedAudio]) but is processed on a worker coroutine via a
 * channel, so transcription never blocks capture.
 */
class SherpaOfflineEngine(
    private val models: ModelManager,
    private val settings: AppSettings,
) : ASREngine {

    override val type = EngineType.SHERPA_OFFLINE

    private var listener: ASRListener? = null

    private var recognizer: OfflineRecognizer? = null
    private var streamingRecognizer: OnlineRecognizer? = null
    private var streamingStream: OnlineStream? = null
    private var vad: Vad? = null
    private var punct: OfflinePunctuation? = null
    private var embedder: SpeakerEmbedder? = null
    private var clusterer: LiveSpeakerClusterer? = null
    private var speakerExecutor: ExecutorService? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null
    private var audioChannel: Channel<ShortArray>? = null

    private var wavWriter: WavWriter? = null
    private var wavFile: File? = null

    private val sessionSegments = ArrayList<TranscriptSegment>()
    private var lastEmotion: String? = null

    private val kind get() = settings.offlineModel

    override fun setListener(listener: ASRListener?) {
        this.listener = listener
    }

    override suspend fun init(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            if (!models.asrReady(kind)) error(modelNotReadyMessage(kind))

            if (kind.isStreaming) {
                streamingRecognizer = OnlineRecognizer(assetManager = null, config = buildStreamingConfig())
            } else {
                vad = Vad(
                    assetManager = null,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = models.sileroVad(),
                            threshold = settings.vadThreshold,
                            minSilenceDuration = 0.35f,
                            minSpeechDuration = 0.08f,
                            windowSize = 512,
                            maxSpeechDuration = 12.0f,
                        ),
                        sampleRate = AudioConstants.SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                )
                recognizer = OfflineRecognizer(assetManager = null, config = buildRecognizerConfig())
            }

            if (kind.usesExternalPunctuation && models.isReady(ModelComponent.PUNCT_CT)) {
                punct = OfflinePunctuation(
                    assetManager = null,
                    config = OfflinePunctuationConfig(
                        model = OfflinePunctuationModelConfig(
                            ctTransformer = models.punctModel(),
                            numThreads = 1,
                            provider = "cpu",
                        ),
                    ),
                )
            }

            if (settings.liveDiarization && models.embeddingReady()) {
                runCatching {
                    val emb = SpeakerEmbedder(models.embeddingModel())
                    embedder = emb
                    clusterer = LiveSpeakerClusterer(emb, settings.diarizationThreshold)
                    speakerExecutor = Executors.newSingleThreadExecutor()
                }.onFailure { Log.w(TAG, "live diarization unavailable: ${it.message}") }
            }
        }
    }

    private fun modelNotReadyMessage(kind: OfflineModelKind): String = when (kind) {
        OfflineModelKind.SENSE_VOICE -> "离线模型未就绪，请在设置中下载 SenseVoice 与 VAD 模型"
        OfflineModelKind.PARAFORMER -> "离线模型未就绪，请在设置中下载 Paraformer、VAD 与标点模型"
        OfflineModelKind.ZIPFORMER_CTC -> "离线模型未就绪，请在设置中下载 Zipformer-CTC 流式模型"
        OfflineModelKind.ZIPFORMER_CTC_XLARGE -> "离线模型未就绪，请在设置中下载 Zipformer-CTC XLarge 流式模型"
        OfflineModelKind.FUNASR_NANO -> "离线模型未就绪，请在设置中下载 FunASR Nano 与 VAD 模型"
        OfflineModelKind.QWEN3_ASR -> "离线模型未就绪，请在设置中下载 Qwen3-ASR 与 VAD 模型"
    }

    private fun buildStreamingConfig(): OnlineRecognizerConfig {
        val modelConfig = OnlineModelConfig(
            zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = models.zipformerCtcModel(kind)),
            tokens = models.zipformerCtcTokens(kind),
            numThreads = if (kind == OfflineModelKind.ZIPFORMER_CTC_XLARGE) 2 else 1,
            provider = "cpu",
        )
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioConstants.SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    }

    private fun buildRecognizerConfig(): OfflineRecognizerConfig {
        val modelConfig = when (kind) {
            OfflineModelKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = models.senseVoiceModel(),
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = models.senseVoiceTokens(),
                numThreads = 2,
                provider = "cpu",
                modelType = "sense_voice",
            )

            OfflineModelKind.PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = models.paraformerModel()),
                tokens = models.paraformerTokens(),
                numThreads = 2,
                provider = "cpu",
                modelType = "paraformer",
            )

            OfflineModelKind.FUNASR_NANO -> OfflineModelConfig(
                funasrNano = OfflineFunAsrNanoModelConfig(
                    encoderAdaptor = models.funAsrNanoEncoderAdaptor(),
                    llm = models.funAsrNanoLlm(),
                    embedding = models.funAsrNanoEmbedding(),
                    tokenizer = models.funAsrNanoTokenizer(),
                    language = "zh",
                    itn = true,
                ),
                numThreads = 2,
                provider = "cpu",
            )

            OfflineModelKind.QWEN3_ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = models.qwen3ConvFrontend(),
                    encoder = models.qwen3Encoder(),
                    decoder = models.qwen3Decoder(),
                    tokenizer = models.qwen3Tokenizer(),
                    maxTotalLen = 1024,
                    maxNewTokens = 1024,
                ),
                numThreads = 2,
                provider = "cpu",
            )

            OfflineModelKind.ZIPFORMER_CTC,
            OfflineModelKind.ZIPFORMER_CTC_XLARGE -> error("$kind uses OnlineRecognizer")
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioConstants.SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
    }

    override fun startStreaming() {
        if (kind.isStreaming && streamingRecognizer == null) {
            listener?.onError("流式识别器未初始化")
            return
        }
        if (!kind.isStreaming && recognizer == null) {
            listener?.onError("离线识别器未初始化")
            return
        }
        sessionSegments.clear()
        lastEmotion = null
        clusterer?.reset()
        runCatching { vad?.reset() }
        runCatching { streamingStream?.release() }
        streamingStream = streamingRecognizer?.createStream()

        val recDir = File(models.modelsDir.parentFile ?: models.modelsDir, "rec").apply { mkdirs() }
        val f = File(recDir, "session_${System.currentTimeMillis()}.wav")
        wavFile = f
        wavWriter = WavWriter(f)

        val ch = RealtimeAudioQueue.channel<ShortArray>()
        audioChannel = ch
        listener?.onState(EngineState.LISTENING)
        workerJob = scope.launch {
            if (kind.isStreaming) streamingWorker(ch) else worker(ch)
        }
    }

    override fun feedAudio(pcm: ShortArray, length: Int) {
        audioChannel?.trySend(pcm.copyOf(length))
    }

    private suspend fun worker(ch: Channel<ShortArray>) {
        val v = vad ?: return
        val partial = FloatChunkBuffer()
        var lastPartialMs = 0L
        try {
            while (true) {
                // Block for the next chunk, then DRAIN everything already queued before doing any
                // heavy work. This is the fix for "falls progressively behind": finals (one decode
                // per VAD segment) always stay caught up to realtime; the live preview is only
                // attempted once the backlog is empty, so under load it self-degrades to finals-only
                // instead of accumulating unbounded latency.
                var chunk = ch.receiveCatching().getOrNull() ?: break
                while (true) {
                    val c = applyGain(chunk)
                    wavWriter?.write(c, c.size)
                    val f = FloatArray(c.size) { c[it] / 32768.0f }
                    v.acceptWaveform(f)
                    partial.append(f)
                    while (!v.empty()) {
                        val seg = v.front()
                        emitFinal(seg.start, seg.samples)
                        v.pop()
                        partial.clear() // reset the in-progress window on every endpoint
                        listener?.onPartial("")
                    }
                    chunk = ch.tryReceive().getOrNull() ?: break
                }
                // Caught up. A live preview re-decodes only the current (bounded, ≤ maxSpeechDuration)
                // utterance window — its cost never grows with meeting length.
                if (v.isSpeechDetected() && partial.size >= AudioConstants.SAMPLE_RATE / 4) {
                    val now = System.currentTimeMillis()
                    if (now - lastPartialMs >= PARTIAL_INTERVAL_MS) {
                        lastPartialMs = now
                        val text = decode(partial.tail(PARTIAL_MAX_SAMPLES))
                        if (text.isNotBlank()) listener?.onPartial(text)
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected on switch/release
        } catch (t: Throwable) {
            Log.e(TAG, "worker error", t)
            listener?.onError("离线转写出错: ${t.message}", t)
        }
    }

    private suspend fun streamingWorker(ch: Channel<ShortArray>) {
        val r = streamingRecognizer ?: return
        val stream = streamingStream ?: r.createStream().also { streamingStream = it }
        val segmentAudio = FloatChunkBuffer()
        var totalSamples = 0L
        var segmentStartSample = 0L
        var lastPartialMs = 0L
        var lastResult = ""
        try {
            while (true) {
                var chunk = ch.receiveCatching().getOrNull() ?: break
                while (true) {
                    val c = applyGain(chunk)
                    wavWriter?.write(c, c.size)
                    val f = FloatArray(c.size) { c[it] / 32768.0f }
                    stream.acceptWaveform(f, AudioConstants.SAMPLE_RATE)
                    segmentAudio.append(f)
                    totalSamples += c.size

                    while (r.isReady(stream)) r.decode(stream)
                    lastResult = r.getResult(stream).text.trim()
                    val now = System.currentTimeMillis()
                    if (lastResult.isNotBlank() && now - lastPartialMs >= PARTIAL_INTERVAL_MS) {
                        lastPartialMs = now
                        listener?.onPartial(lastResult)
                    }

                    if (r.isEndpoint(stream)) {
                        emitStreamingFinal(segmentStartSample, totalSamples, segmentAudio.toArray(), lastResult)
                        r.reset(stream)
                        segmentAudio.clear()
                        segmentStartSample = totalSamples
                        lastResult = ""
                        listener?.onPartial("")
                    }

                    chunk = ch.tryReceive().getOrNull() ?: break
                }
            }

            stream.inputFinished()
            while (r.isReady(stream)) r.decode(stream)
            lastResult = r.getResult(stream).text.trim()
            if (lastResult.isNotBlank() && segmentAudio.size > 0) {
                emitStreamingFinal(segmentStartSample, totalSamples, segmentAudio.toArray(), lastResult)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected on switch/release
        } catch (t: Throwable) {
            Log.e(TAG, "streaming worker error", t)
            listener?.onError("流式离线转写出错: ${t.message}", t)
        }
    }

    private fun emitFinal(startSample: Int, samples: FloatArray) {
        val raw = decode(samples)
        if (raw.isBlank()) return
        val text = maybePunctuate(raw)
        val startMs = startSample.toLong() * 1000 / AudioConstants.SAMPLE_RATE
        val endMs = (startSample.toLong() + samples.size) * 1000 / AudioConstants.SAMPLE_RATE
        val segment = TranscriptSegment(
            text = text,
            speaker = TranscriptSegment.UNKNOWN_SPEAKER,
            startMs = startMs,
            endMs = endMs,
            emotion = lastEmotion,
        )
        sessionSegments.add(segment)
        listener?.onSegment(segment)
        // Speaker embedding costs about as much as decoding; running it inline would ~double
        // per-segment latency. Emit text now and attach the provisional label when the (serial,
        // ordered) clusterer catches up. The whole-recording pass still corrects it on stop.
        assignSpeakerAsync(segment.id, samples)
    }

    private fun emitStreamingFinal(
        startSample: Long,
        endSample: Long,
        samples: FloatArray,
        rawText: String,
    ) {
        val raw = rawText.trim()
        if (raw.isBlank()) return
        val text = maybePunctuate(raw)
        val startMs = startSample * 1000 / AudioConstants.SAMPLE_RATE
        val endMs = endSample * 1000 / AudioConstants.SAMPLE_RATE
        val segment = TranscriptSegment(
            text = text,
            speaker = TranscriptSegment.UNKNOWN_SPEAKER,
            startMs = startMs,
            endMs = endMs,
            emotion = null,
        )
        sessionSegments.add(segment)
        listener?.onSegment(segment)
        assignSpeakerAsync(segment.id, samples)
    }

    /** Computes the provisional speaker label off the decode thread, in submission (segment) order. */
    private fun assignSpeakerAsync(segmentId: String, samples: FloatArray) {
        val c = clusterer ?: return
        val exec = speakerExecutor ?: return
        runCatching {
            exec.execute {
                val sp = runCatching { c.assign(samples) }.getOrDefault(-1)
                if (sp >= 0) listener?.onSpeakersCorrected(mapOf(segmentId to sp))
            }
        }
    }

    /** Block until queued speaker tasks finish, so live labels settle before the whole-file pass. */
    private fun drainSpeakerTasks() {
        val exec = speakerExecutor ?: return
        val latch = CountDownLatch(1)
        val submitted = runCatching { exec.execute { latch.countDown() } }.isSuccess
        if (submitted) runCatching { latch.await(5, TimeUnit.SECONDS) }
    }

    /** Amplifies quiet captures so far-field speech clears the VAD threshold. Peaks are hard-clipped. */
    private fun applyGain(src: ShortArray): ShortArray {
        if (src.isEmpty()) return src
        val rms = rms(src)
        val adaptiveGain =
            if (rms >= FAR_FIELD_NOISE_FLOOR_RMS && rms < FAR_FIELD_TARGET_RMS) {
                (FAR_FIELD_TARGET_RMS / rms).coerceIn(1.0f, FAR_FIELD_MAX_GAIN)
            } else {
                1.0f
            }
        val g = maxOf(settings.micGain, adaptiveGain).coerceIn(1.0f, FAR_FIELD_MAX_GAIN)
        if (g <= 1.01f) return src
        return ShortArray(src.size) { (src[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun rms(src: ShortArray): Float {
        var sum = 0.0
        for (s in src) {
            val x = s / 32768.0
            sum += x * x
        }
        return sqrt(sum / src.size).toFloat()
    }

    private fun decode(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val stream = r.createStream()
        stream.acceptWaveform(samples, AudioConstants.SAMPLE_RATE)
        r.decode(stream)
        val result = r.getResult(stream)
        stream.release()
        lastEmotion = result.emotion?.takeIf { it.isNotBlank() && !it.contains("UNKNOWN", ignoreCase = true) }
        return result.text.trim()
    }

    private fun maybePunctuate(text: String): String {
        val p = punct ?: return text
        return runCatching { p.addPunctuation(text) }.getOrDefault(text)
    }

    override fun stop() {
        val job = workerJob
        val ch = audioChannel
        scope.launch {
            ch?.close()
            runCatching { job?.join() }
            // Flush any trailing in-progress speech the VAD was still holding.
            runCatching {
                vad?.let { v ->
                    v.flush()
                    while (!v.empty()) {
                        val seg = v.front()
                        emitFinal(seg.start, seg.samples)
                        v.pop()
                    }
                }
            }
            listener?.onPartial("")
            wavWriter?.close()
            drainSpeakerTasks()
            finalizeDiarization()
            listener?.onState(EngineState.STOPPED)
            cleanupSessionFile()
            audioChannel = null
            workerJob = null
        }
    }

    /** Whole-recording diarization pass; corrects provisional live speaker labels. Best-effort. */
    private fun finalizeDiarization() {
        if (!settings.postDiarization || !models.diarizationReady()) return
        val f = wavFile ?: return
        if (sessionSegments.isEmpty()) return

        listener?.onState(EngineState.FINALIZING)
        var diarizer: OfflineDiarizer? = null
        try {
            diarizer = OfflineDiarizer(
                segmentationModelPath = models.segmentationModel(),
                embeddingModelPath = models.embeddingModel(),
                threshold = settings.diarizationThreshold,
            )
            val diar = try {
                val samples = WavIo.readPcm16MonoAsFloat(f)
                if (samples.isEmpty()) emptyList() else diarizer.diarize(samples)
            } catch (oom: OutOfMemoryError) {
                // Whole recording didn't fit in memory. Fall back to bounded-memory chunks, stitching
                // speaker ids across chunk boundaries by voiceprint similarity. Approximate, but it
                // lets long meetings finish instead of dropping the correction entirely.
                Log.w(TAG, "whole-file diarization OOM; retrying in chunks", oom)
                diarizeChunked(f, WavIo.pcm16MonoSampleCount(f), diarizer)
            }
            applyDiarization(diar)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "diarization OOM", oom)
            listener?.onError("会议过长，离线说话人分离内存不足，已保留实时标注")
        } catch (t: Throwable) {
            Log.e(TAG, "diarization failed", t)
            listener?.onError("说话人分离失败：${t.message}")
        } finally {
            diarizer?.release()
        }
    }

    private fun applyDiarization(diar: List<OfflineDiarizer.Seg>) {
        if (diar.isEmpty()) return
        val corrections = HashMap<String, Int>()
        for (seg in sessionSegments) {
            val sp = bestSpeaker(seg, diar)
            if (sp >= 0 && sp != seg.speaker) corrections[seg.id] = sp
        }
        if (corrections.isNotEmpty()) listener?.onSpeakersCorrected(corrections)
    }

    private fun bestSpeaker(seg: TranscriptSegment, diar: List<OfflineDiarizer.Seg>): Int {
        val s = seg.startMs / 1000f
        val e = seg.endMs / 1000f
        var best = -1
        var bestOverlap = 0f
        for (d in diar) {
            val overlap = minOf(e, d.endSec) - maxOf(s, d.startSec)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = d.speaker
            }
        }
        return best
    }

    /**
     * Diarizes a long recording in [DIARIZATION_CHUNK_SEC] chunks. Each chunk is diarized
     * independently, then its local speaker ids are mapped to global ones by comparing each
     * speaker's longest-segment voiceprint against running global centroids.
     */
    private fun diarizeChunked(
        file: File,
        totalSamples: Long,
        diarizer: OfflineDiarizer,
    ): List<OfflineDiarizer.Seg> {
        if (totalSamples <= 0) return emptyList()
        val sr = AudioConstants.SAMPLE_RATE
        val chunkSamples = DIARIZATION_CHUNK_SEC * sr
        val result = ArrayList<OfflineDiarizer.Seg>()
        val globalCentroids = ArrayList<FloatArray>()
        var embedder: SpeakerEmbedder? = null
        try {
            embedder = SpeakerEmbedder(models.embeddingModel())
            var start = 0L
            while (start < totalSamples) {
                val count = minOf(chunkSamples.toLong(), totalSamples - start).toInt()
                val chunk = WavIo.readPcm16MonoRange(file, start, count)
                if (chunk.isEmpty()) break
                val offsetSec = start.toFloat() / sr
                val localDiar = diarizer.diarize(chunk)
                val localToGlobal = HashMap<Int, Int>()
                for ((localSp, segs) in localDiar.groupBy { it.speaker }) {
                    val centroid = speakerCentroid(chunk, segs, embedder) ?: continue
                    localToGlobal[localSp] = matchOrAddCentroid(globalCentroids, centroid)
                }
                for (s in localDiar) {
                    val g = localToGlobal[s.speaker] ?: continue
                    result.add(OfflineDiarizer.Seg(offsetSec + s.startSec, offsetSec + s.endSec, g))
                }
                start += count
            }
        } finally {
            embedder?.release()
        }
        return result
    }

    /** Representative voiceprint for a chunk-local speaker: embedding of their longest segment. */
    private fun speakerCentroid(
        chunk: FloatArray,
        segs: List<OfflineDiarizer.Seg>,
        embedder: SpeakerEmbedder,
    ): FloatArray? {
        val sr = AudioConstants.SAMPLE_RATE
        val longest = segs.maxByOrNull { it.endSec - it.startSec } ?: return null
        val from = (longest.startSec * sr).toInt().coerceIn(0, chunk.size)
        val to = (longest.endSec * sr).toInt().coerceIn(from, chunk.size)
        if (to - from < MIN_CENTROID_SAMPLES) return null
        return runCatching { embedder.embed(chunk.copyOfRange(from, to)) }.getOrNull()
    }

    private fun matchOrAddCentroid(centroids: ArrayList<FloatArray>, emb: FloatArray): Int {
        var bestIdx = -1
        var bestSim = -1f
        for (i in centroids.indices) {
            val sim = SpeakerEmbedder.cosine(emb, centroids[i])
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }
        return if (bestIdx >= 0 && bestSim >= settings.diarizationThreshold) {
            bestIdx
        } else {
            centroids.add(emb)
            centroids.size - 1
        }
    }

    private fun cleanupSessionFile() {
        runCatching { wavFile?.delete() }
        wavFile = null
    }

    override fun release() {
        runCatching { audioChannel?.close() }
        runCatching { scope.cancel() }
        runCatching { wavWriter?.close() }
        runCatching { recognizer?.release() }
        runCatching { streamingStream?.release() }
        runCatching { streamingRecognizer?.release() }
        runCatching { vad?.release() }
        runCatching { punct?.release() }
        runCatching { speakerExecutor?.shutdownNow() }
        embedder?.release()
        recognizer = null
        streamingStream = null
        streamingRecognizer = null
        vad = null
        punct = null
        embedder = null
        clusterer = null
        speakerExecutor = null
        cleanupSessionFile()
        listener = null
    }

    /** Growable float accumulator for partial-result decoding (cleared on each finalized utterance). */
    private class FloatChunkBuffer {
        private var arr = FloatArray(AudioConstants.SAMPLE_RATE)
        var size = 0
            private set

        fun append(src: FloatArray) {
            if (size + src.size > arr.size) {
                var cap = arr.size * 2
                while (cap < size + src.size) cap *= 2
                arr = arr.copyOf(cap)
            }
            System.arraycopy(src, 0, arr, size, src.size)
            size += src.size
        }

        fun toArray(): FloatArray = arr.copyOf(size)
        fun tail(maxSamples: Int): FloatArray =
            if (size <= maxSamples) arr.copyOf(size) else arr.copyOfRange(size - maxSamples, size)

        fun clear() {
            size = 0
        }
    }

    private companion object {
        const val TAG = "SherpaOfflineEngine"
        const val PARTIAL_INTERVAL_MS = 300L
        const val PARTIAL_MAX_SAMPLES = AudioConstants.SAMPLE_RATE * 6
        const val FAR_FIELD_TARGET_RMS = 0.06f
        const val FAR_FIELD_NOISE_FLOOR_RMS = 0.0025f
        const val FAR_FIELD_MAX_GAIN = 12.0f
        const val DIARIZATION_CHUNK_SEC = 600
        const val MIN_CENTROID_SAMPLES = AudioConstants.SAMPLE_RATE / 2
    }
}
