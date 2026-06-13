package com.overmind.meetingscribe.audio

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class LiveAudioSlice(
    val samples: ShortArray,
    val sampleRate: Int,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Session-scoped PCM cache for click-to-play while a recording is still running.
 *
 * The persisted M4A/WAV file is not a reliable live playback source until it is closed, so the UI
 * plays short finalized transcript segments from this 16 kHz temp file instead.
 */
object LiveRecordingAudioBuffer {
    private var sampleRate = AudioConstants.SAMPLE_RATE
    private var nextSample = 0L
    private var file: File? = null
    private var out: BufferedOutputStream? = null

    @Synchronized
    fun reset(context: Context? = null, sampleRate: Int = AudioConstants.SAMPLE_RATE) {
        closeLocked(delete = true)
        this.sampleRate = sampleRate
        nextSample = 0L

        if (context != null) {
            val dir = File(context.applicationContext.cacheDir, "live_audio").apply { mkdirs() }
            val pcmFile = File(dir, "current_session.pcm").apply { delete() }
            file = pcmFile
            out = BufferedOutputStream(FileOutputStream(pcmFile))
        }
    }

    @Synchronized
    fun append(samples: ShortArray, length: Int) {
        val stream = out ?: return
        if (length <= 0) return
        val bytes = ByteArray(length * AudioConstants.BYTES_PER_SAMPLE)
        var j = 0
        for (i in 0 until length) {
            val s = samples[i].toInt()
            bytes[j++] = (s and 0xFF).toByte()
            bytes[j++] = ((s shr 8) and 0xFF).toByte()
        }
        stream.write(bytes)
        nextSample += length
    }

    @Synchronized
    fun slice(startMs: Long, endMs: Long): LiveAudioSlice? {
        val pcmFile = file?.takeIf { it.exists() } ?: return null
        if (endMs <= startMs || nextSample <= 0L) return null
        val requestedStart = msToSampleFloor(startMs)
        val requestedEnd = msToSampleCeil(endMs).coerceAtMost(nextSample)
        if (requestedEnd <= requestedStart) return null

        out?.flush()
        val length = (requestedEnd - requestedStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(length * AudioConstants.BYTES_PER_SAMPLE)
        var readBytes = 0
        RandomAccessFile(pcmFile, "r").use { raf ->
            val startByte = requestedStart * AudioConstants.BYTES_PER_SAMPLE
            if (startByte >= raf.length()) return null
            raf.seek(startByte)
            while (readBytes < bytes.size) {
                val r = raf.read(bytes, readBytes, bytes.size - readBytes)
                if (r <= 0) break
                readBytes += r
            }
        }
        val sampleCount = readBytes / AudioConstants.BYTES_PER_SAMPLE
        if (sampleCount <= 0) return null
        val samplesOut = ShortArray(sampleCount)
        var k = 0
        for (i in 0 until sampleCount) {
            val lo = bytes[k].toInt() and 0xFF
            val hi = bytes[k + 1].toInt()
            samplesOut[i] = ((hi shl 8) or lo).toShort()
            k += AudioConstants.BYTES_PER_SAMPLE
        }
        return LiveAudioSlice(
            samples = samplesOut,
            sampleRate = sampleRate,
            startMs = sampleToMs(requestedStart),
            endMs = sampleToMs(requestedStart + samplesOut.size),
        )
    }

    private fun closeLocked(delete: Boolean) {
        runCatching { out?.flush() }
        runCatching { out?.close() }
        out = null
        if (delete) runCatching { file?.delete() }
        file = null
    }

    private fun msToSampleFloor(ms: Long): Long =
        ms.coerceAtLeast(0L) * sampleRate / 1000L

    private fun msToSampleCeil(ms: Long): Long =
        (ms.coerceAtLeast(0L) * sampleRate + 999L) / 1000L

    private fun sampleToMs(sample: Long): Long =
        sample * 1000L / sampleRate
}
