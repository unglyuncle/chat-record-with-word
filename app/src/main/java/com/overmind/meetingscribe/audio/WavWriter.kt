package com.overmind.meetingscribe.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit little-endian PCM into a WAV file, patching the RIFF/data sizes on [close]
 * (sizes aren't known until recording ends). Used by the offline engine to keep the full
 * recording around for the post-stop speaker-diarization pass.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val channels: Int = AudioConstants.CHANNELS,
    private val bitsPerSample: Int = AudioConstants.BITS_PER_SAMPLE,
) : RecordingFileWriter {
    override val mimeType: String = RecordingFormat.WAV.mimeType
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var dataBytes = 0L
    private var closed = false

    init {
        out.write(ByteArray(44)) // placeholder header
    }

    @Synchronized
    override fun write(samples: ShortArray, length: Int) {
        if (closed) return
        val bytes = ByteArray(length * 2)
        var j = 0
        for (i in 0 until length) {
            val s = samples[i].toInt()
            bytes[j++] = (s and 0xFF).toByte()
            bytes[j++] = ((s shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        dataBytes += bytes.size
    }

    override val durationMs: Long
        get() = dataBytes / 2 * 1000L / sampleRate

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            out.flush()
            out.close()
        }
        patchHeader()
    }

    private fun patchHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.write(intLE((36 + dataBytes).toInt()))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intLE(16))
            raf.write(shortLE(1)) // PCM
            raf.write(shortLE(channels))
            raf.write(intLE(sampleRate))
            raf.write(intLE(byteRate))
            raf.write(shortLE(blockAlign))
            raf.write(shortLE(bitsPerSample))
            raf.writeBytes("data")
            raf.write(intLE(dataBytes.toInt()))
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

object WavIo {
    /**
     * Reads a 16-bit PCM mono WAV (as produced by [WavWriter]) into a normalized FloatArray in
     * [-1, 1]. Reads chunk-wise to avoid an extra full-size byte buffer. The resulting float
     * array itself is large for long recordings (~230 MB/hour) — callers should guard for OOM.
     */
    fun readPcm16MonoAsFloat(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (total <= 44) return FloatArray(0)
            val dataLen = (total - 44)
            val n = (dataLen / 2).toInt()
            val out = FloatArray(n)
            raf.seek(44)
            val buf = ByteArray(8192) // even size -> samples never split across reads
            var idx = 0
            while (idx < n) {
                val r = raf.read(buf)
                if (r <= 0) break
                var k = 0
                while (k + 1 < r && idx < n) {
                    val lo = buf[k].toInt() and 0xFF
                    val hi = buf[k + 1].toInt() // sign-extended
                    out[idx++] = ((hi shl 8) or lo) / 32768.0f
                    k += 2
                }
            }
            return out
        }
    }

    /** Sample count of a 16-bit mono PCM WAV body (excludes the 44-byte header). */
    fun pcm16MonoSampleCount(file: File): Long {
        val total = file.length()
        return if (total <= 44) 0L else (total - 44) / 2
    }

    fun pcm16MonoSampleRate(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44) { "WAV 文件太短" }
            require(raf.readFourCc() == "RIFF") { "不是 RIFF WAV 文件" }
            raf.seek(8)
            require(raf.readFourCc() == "WAVE") { "不是 WAVE 文件" }
            raf.seek(24)
            val sampleRate = raf.readIntLE()
            require(sampleRate > 0) { "WAV 采样率无效" }
            return sampleRate
        }
    }

    /**
     * Reads [count] samples starting at [startSample] from a 16-bit PCM mono WAV into a normalized
     * FloatArray, clamped to what's available. Lets the diarizer process long recordings in
     * bounded-memory chunks instead of one ~230 MB/hour array.
     */
    fun readPcm16MonoRange(file: File, startSample: Long, count: Int): FloatArray {
        if (count <= 0 || startSample < 0) return FloatArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (total <= 44) return FloatArray(0)
            val available = ((total - 44) / 2 - startSample).coerceAtLeast(0L)
            val n = minOf(count.toLong(), available).toInt()
            if (n <= 0) return FloatArray(0)
            val out = FloatArray(n)
            raf.seek(44 + startSample * 2)
            val buf = ByteArray(8192)
            var idx = 0
            while (idx < n) {
                val r = raf.read(buf, 0, minOf(buf.size, (n - idx) * 2))
                if (r <= 0) break
                var k = 0
                while (k + 1 < r && idx < n) {
                    val lo = buf[k].toInt() and 0xFF
                    val hi = buf[k + 1].toInt()
                    out[idx++] = ((hi shl 8) or lo) / 32768.0f
                    k += 2
                }
            }
            return out
        }
    }

    fun readPcm16MonoRangeShort(file: File, startSample: Long, count: Int): ShortArray {
        if (count <= 0 || startSample < 0) return ShortArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (total <= 44) return ShortArray(0)
            val available = ((total - 44) / 2 - startSample).coerceAtLeast(0L)
            val n = minOf(count.toLong(), available).toInt()
            if (n <= 0) return ShortArray(0)
            val out = ShortArray(n)
            raf.seek(44 + startSample * 2)
            val buf = ByteArray(8192)
            var idx = 0
            while (idx < n) {
                val r = raf.read(buf, 0, minOf(buf.size, (n - idx) * 2))
                if (r <= 0) break
                var k = 0
                while (k + 1 < r && idx < n) {
                    val lo = buf[k].toInt() and 0xFF
                    val hi = buf[k + 1].toInt()
                    out[idx++] = ((hi shl 8) or lo).toShort()
                    k += 2
                }
            }
            return if (idx == out.size) out else out.copyOf(idx)
        }
    }

    private fun RandomAccessFile.readFourCc(): String {
        val bytes = ByteArray(4)
        val n = read(bytes)
        require(n == 4) { "WAV 文件头不完整" }
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "WAV 文件头不完整" }
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
