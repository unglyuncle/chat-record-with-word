package com.overmind.meetingscribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.util.Log

/**
 * Thin AudioRecord wrapper that reads 16 kHz / mono / PCM-16 on a dedicated high-priority thread
 * and pushes fixed-size buffers to [onAudio]. The same backing array is reused across callbacks,
 * so consumers must copy/convert synchronously (engines do, then offload heavy work).
 */
class PcmAudioRecorder(
    private val onAudio: (samples: ShortArray, length: Int) -> Unit,
    private val onError: (message: String) -> Unit,
    private val sourceMode: RecordingAudioSourceMode = RecordingAudioSourceMode.CLEAN,
    private val enableAgc: Boolean = false,
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val readSamples: Int = AudioConstants.READ_SAMPLES,
    private val onStarted: (sampleRate: Int) -> Unit = {},
) {
    private var record: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    var actualSampleRate: Int = sampleRate
        private set
    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service is started.
    fun start(): Boolean {
        for (candidateRate in sampleRateCandidates()) {
            val minBuf = AudioRecord.getMinBufferSize(
                candidateRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) continue

            val candidateReadSamples = readSamplesFor(candidateRate)
            val bufferSize = maxOf(minBuf, candidateReadSamples * AudioConstants.BYTES_PER_SAMPLE * 2)
            val ar = createAudioRecord(bufferSize, candidateRate)
            if (ar == null) continue
            record = ar
            actualSampleRate = candidateRate
            if (enableAgc) enableAutomaticGainControl(ar.audioSessionId)
            running = true
            return try {
                ar.startRecording()
                onStarted(candidateRate)
                thread = Thread({ loop(ar, candidateReadSamples) }, "pcm-recorder").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                true
            } catch (t: Throwable) {
                onError("启动录音失败: ${t.message}")
                stop()
                false
            }
        }
        onError("录音器初始化失败（麦克风可能被占用）")
        return false
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int, candidateRate: Int): AudioRecord? {
        val sources = audioSources()
        for (source in sources) {
            val ar = runCatching {
                AudioRecord(
                    source,
                    candidateRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull()
            if (ar?.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord initialized with source=${audioSourceName(source)}")
                return ar
            }
            ar?.release()
        }
        return null
    }

    private fun sampleRateCandidates(): List<Int> =
        if (sampleRate == AudioConstants.SAMPLE_RATE) {
            listOf(AudioConstants.SAMPLE_RATE)
        } else {
            listOf(sampleRate, 44_100, AudioConstants.SAMPLE_RATE).distinct()
        }

    private fun readSamplesFor(candidateRate: Int): Int =
        if (candidateRate == sampleRate) readSamples else candidateRate / 10

    private fun enableAutomaticGainControl(audioSessionId: Int) {
        if (!AutomaticGainControl.isAvailable()) return
        agc = runCatching {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        }.onFailure {
            Log.w(TAG, "AGC unavailable: ${it.message}")
        }.getOrNull()
    }

    private fun audioSources(): List<Int> {
        val clean = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        return when (sourceMode) {
            RecordingAudioSourceMode.CLEAN -> clean
            RecordingAudioSourceMode.VOICE_RECOGNITION -> buildList {
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.MIC)
            }
            RecordingAudioSourceMode.CALL_PROCESSING -> listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
        }
    }

    private fun loop(ar: AudioRecord, actualReadSamples: Int) {
        val buf = ShortArray(actualReadSamples)
        while (running) {
            val n = ar.read(buf, 0, buf.size)
            when {
                n > 0 -> onAudio(buf, n)
                n == 0 -> { /* no data yet */ }
                else -> {
                    if (running) {
                        onError("录音读取错误 ($n)")
                        Log.e(TAG, "AudioRecord.read returned $n")
                    }
                    break
                }
            }
        }
    }

    /** Idempotent. Must NOT be called from the recorder thread (it joins that thread). */
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(800) } }
        thread = null
        agc?.let { runCatching { it.release() } }
        agc = null
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> source.toString()
    }

    private companion object {
        const val TAG = "PcmAudioRecorder"
    }
}
