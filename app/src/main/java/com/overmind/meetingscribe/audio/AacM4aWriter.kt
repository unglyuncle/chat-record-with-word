package com.overmind.meetingscribe.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log

class AacM4aWriter(
    private val file: java.io.File,
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val channels: Int = AudioConstants.CHANNELS,
    private val bitRate: Int = AudioConstants.RECORDING_BIT_RATE,
) : RecordingFileWriter {
    override val mimeType: String = RecordingFormat.M4A_AAC.mimeType
    override val durationMs: Long
        get() = totalSamples * 1000L / sampleRate

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var muxerStarted = false
    private var trackIndex = -1
    private var totalSamples = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate / 10 * channels * AudioConstants.BYTES_PER_SAMPLE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    @Synchronized
    override fun write(samples: ShortArray, length: Int) {
        if (closed || length <= 0) return
        var offset = 0
        while (offset < length) {
            drain(endOfStream = false)
            val inputIndex = dequeueInputRetrying()
            if (inputIndex < 0) return
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            val count = minOf(length - offset, input.remaining() / AudioConstants.BYTES_PER_SAMPLE)
            if (count <= 0) return
            for (i in offset until offset + count) {
                val s = samples[i].toInt()
                input.put((s and 0xFF).toByte())
                input.put(((s shr 8) and 0xFF).toByte())
            }
            val ptsUs = totalSamples * 1_000_000L / sampleRate
            totalSamples += count
            codec.queueInputBuffer(inputIndex, 0, count * AudioConstants.BYTES_PER_SAMPLE, ptsUs, 0)
            offset += count
            drain(endOfStream = false)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var finalizeFailure: Throwable? = null
        runCatching {
            queueEndOfStream()
            drain(endOfStream = true)
            check(muxerStarted) { "AAC 编码器没有输出可保存的音频数据" }
        }.onFailure {
            finalizeFailure = it
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        val muxerStopFailure = runCatching {
            if (muxerStarted) muxer.stop()
        }.exceptionOrNull()
        runCatching { muxer.release() }

        finalizeFailure?.let { throw IllegalStateException("M4A 录音封装失败：${it.message}", it) }
        muxerStopFailure?.let { throw IllegalStateException("M4A 录音写入结尾失败：${it.message}", it) }
    }

    /**
     * Wait for a free input buffer, draining encoded output (which frees inputs) between attempts.
     * The original code returned on the first miss and silently dropped the rest of the chunk —
     * under load that meant lost audio in the saved file. Bounded so a wedged codec can't spin.
     */
    private fun dequeueInputRetrying(): Int {
        var tries = 0
        while (true) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) return idx
            if (++tries >= MAX_INPUT_RETRIES) {
                Log.w(TAG, "No AAC input buffer after $tries tries; dropping a chunk")
                return -1
            }
            drain(endOfStream = false)
        }
    }

    private fun queueEndOfStream() {
        var tries = 0
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val ptsUs = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            if (++tries >= MAX_INPUT_RETRIES) {
                throw IllegalStateException("AAC 编码器没有可用的结束缓冲区")
            }
            drain(endOfStream = false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var emptyOutputTries = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream && ++emptyOutputTries >= MAX_OUTPUT_RETRIES) {
                        throw IllegalStateException("AAC 编码器结束超时")
                    }
                    if (!endOfStream) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emptyOutputTries = 0
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    emptyOutputTries = 0
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && bufferInfo.size > 0 && muxerStarted) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_RETRIES = 50
        const val MAX_OUTPUT_RETRIES = 200
        const val TAG = "AacM4aWriter"
    }
}
