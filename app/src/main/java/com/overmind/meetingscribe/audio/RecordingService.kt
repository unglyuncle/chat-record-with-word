package com.overmind.meetingscribe.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.overmind.meetingscribe.MainActivity
import com.overmind.meetingscribe.R
import com.overmind.meetingscribe.asr.EngineController
import com.overmind.meetingscribe.data.AppSettings
import kotlin.math.sqrt
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: PcmAudioRecorder? = null
    @Volatile
    private var currentSession: RecordingSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var started = false
    @Volatile
    private var paused = false
    @Volatile
    private var finalizingStop = false

    private var recordingFormat = RecordingFormat.M4A_AAC
    private var audioSourceMode = RecordingAudioSourceMode.CLEAN
    private var enableAgc = false
    @Volatile
    private var recordingSampleRate = AudioConstants.RECORDING_SAMPLE_RATE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            else -> startRecording(intent)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent?) {
        if (started) return
        if (finalizingStop) {
            EngineController.onMessage("上一段录音正在保存，请稍等")
            return
        }
        readOptions(intent)
        started = true
        paused = false
        goForeground(getString(R.string.status_listening))
        acquireWakeLock()
        var session: RecordingSession? = null

        val rec = PcmAudioRecorder(
            onAudio = { buf, n ->
                if (!paused && n > 0) {
                    // Copy off the reused capture buffer, then enqueue. Drop the oldest chunk rather
                    // than block the capture thread if the processor ever falls behind.
                    val chunk = buf.copyOf(n)
                    session?.enqueue(chunk)
                }
            },
            onError = { msg ->
                EngineController.onError(msg)
                mainHandler.post { stopEverything() }
            },
            sourceMode = audioSourceMode,
            enableAgc = enableAgc,
            sampleRate = AudioConstants.RECORDING_SAMPLE_RATE,
            readSamples = AudioConstants.RECORDING_READ_SAMPLES,
            onStarted = { sampleRate ->
                recordingSampleRate = sampleRate
                LiveRecordingAudioBuffer.reset(this, AudioConstants.SAMPLE_RATE)
                val savedFile = RecordingStore.newRecordingFile(this, recordingFormat)
                val writer = runCatching { createWriter(savedFile, recordingFormat, sampleRate) }
                    .onFailure {
                        EngineController.onMessage("录音保存初始化失败：${it.message}")
                    }
                    .getOrNull()
                session = RecordingSession(
                    service = this,
                    file = savedFile,
                    writer = writer,
                    sampleRate = sampleRate,
                ).also {
                    currentSession = it
                    it.startProcessing()
                }
                EngineController.startSession(if (writer == null) null else savedFile.absolutePath)
            },
        )
        if (!rec.start()) {
            stopEverything()
            return
        }
        recorder = rec
    }

    private fun pauseRecording() {
        if (!started || paused) return
        paused = true
        EngineController.setSessionPaused(true)
        goForeground("已暂停")
    }

    private fun resumeRecording() {
        if (!started || !paused) return
        paused = false
        EngineController.setSessionPaused(false)
        goForeground(getString(R.string.status_listening))
    }

    private fun stopEverything() {
        if (!started) {
            if (!finalizingStop) stopSelf()
            return
        }
        val session = currentSession
        currentSession = null
        started = false
        paused = false
        finalizingStop = true
        recorder?.stop()
        recorder = null
        LiveRecordingAudioBuffer.reset(sampleRate = AudioConstants.SAMPLE_RATE)
        EngineController.stopSession()

        if (session != null) {
            goForeground("正在保存录音")
            session.finishInBackground {
                mainHandler.post { finishStoppedService() }
            }
        } else {
            finishStoppedService()
        }
    }

    override fun onDestroy() {
        val session = currentSession
        currentSession = null
        recorder?.stop()
        recorder = null
        session?.finishInBackground()
        LiveRecordingAudioBuffer.reset(sampleRate = AudioConstants.SAMPLE_RATE)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun finishStoppedService() {
        finalizingStop = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createWriter(file: java.io.File, format: RecordingFormat, sampleRate: Int): RecordingFileWriter =
        when (format) {
            RecordingFormat.M4A_AAC -> AacM4aWriter(file, sampleRate = sampleRate)
            RecordingFormat.WAV -> WavWriter(file, sampleRate = sampleRate)
        }

    private class RecordingSession(
        private val service: RecordingService,
        private var file: java.io.File,
        private var writer: RecordingFileWriter?,
        private val sampleRate: Int,
    ) {
        private val audioQueue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
        @Volatile
        private var processing = false
        @Volatile
        private var feedEngine = true
        private var processThread: Thread? = null
        private var asrCarry = ShortArray(0)
        private var resamplePhase = 0.0
        private var lastAudioLevelAtMs = 0L

        fun startProcessing() {
            processing = true
            processThread = Thread({ processLoop() }, "audio-process").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }

        fun enqueue(chunk: ShortArray) {
            if (!processing) return
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll()
                audioQueue.offer(chunk)
            }
        }

        fun finishInBackground(onFinished: () -> Unit = {}) {
            if (writer != null) {
                EngineController.startBackgroundRecordingSave()
            }
            feedEngine = false
            processing = false
            val thread = processThread
            Thread({
                try {
                    thread?.let { runCatching { it.join(BACKGROUND_FINISH_JOIN_MS) } }
                    processThread = null
                    finishRecordingSave()
                } finally {
                    onFinished()
                }
            }, "recording-save-finalize").start()
        }

        private fun processLoop() {
            try {
                while (processing || audioQueue.isNotEmpty()) {
                    val chunk = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    reportAudioLevel(chunk, chunk.size)
                    saveAudioChunk(chunk, chunk.size)
                    val asrSamples = downsampleForTranscription(chunk, chunk.size)
                    if (asrSamples.isNotEmpty() && feedEngine) {
                        LiveRecordingAudioBuffer.append(asrSamples, asrSamples.size)
                        EngineController.feed(asrSamples, asrSamples.size)
                    }
                }
            } catch (t: Throwable) {
                writer = null
                runCatching { file.delete() }
                EngineController.failBackgroundRecordingSave("录音后台处理失败")
                EngineController.onMessage("录音后台处理失败：${t.message}")
            }
        }

        private fun reportAudioLevel(buf: ShortArray, n: Int) {
            if (n <= 0) return
            val now = System.currentTimeMillis()
            if (now - lastAudioLevelAtMs < AUDIO_LEVEL_INTERVAL_MS) return
            lastAudioLevelAtMs = now

            var sumSquares = 0.0
            var peak = 0
            for (i in 0 until n) {
                val sample = buf[i].toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
                val normalized = sample / Short.MAX_VALUE.toDouble()
                sumSquares += normalized * normalized
            }
            val rms = sqrt(sumSquares / n).toFloat()
            val peakLevel = (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
            val displayLevel = (sqrt(rms.coerceIn(0f, 1f)) * 1.35f)
                .coerceAtLeast(peakLevel * 0.55f)
                .coerceIn(0f, 1f)
            EngineController.reportAudioLevel(displayLevel)
        }

        private fun saveAudioChunk(buf: ShortArray, n: Int) {
            val activeWriter = writer ?: return
            runCatching { activeWriter.write(buf, n) }
                .onFailure {
                    writer = null
                    runCatching { file.delete() }
                    EngineController.failBackgroundRecordingSave("录音保存失败")
                    EngineController.onMessage("录音保存失败：${it.message}")
                }
        }

        private fun finishRecordingSave() {
            val activeWriter = writer
            writer = null
            if (activeWriter == null) return

            val durationMs = activeWriter.durationMs
            val closed = runCatching { activeWriter.close() }
            if (closed.isFailure || durationMs <= 0L) {
                runCatching { file.delete() }
                val reason = closed.exceptionOrNull()?.message ?: "录音太短"
                EngineController.failBackgroundRecordingSave("录音未保存：$reason")
                EngineController.onMessage("录音未保存：$reason")
                return
            }

            RecordingStore.scan(service, file, activeWriter.mimeType)
            EngineController.finishBackgroundRecordingSave("已保存到历史")
            EngineController.onMessage("录音已保存：${file.absolutePath}")
        }

        private fun downsampleForTranscription(buf: ShortArray, n: Int): ShortArray {
            if (n <= 0) return ShortArray(0)
            val sourceRate = sampleRate
            val targetRate = AudioConstants.SAMPLE_RATE
            if (sourceRate == targetRate) return buf.copyOf(n)

            val ratio = sourceRate / targetRate
            if (ratio <= 1) return buf.copyOf(n)

            val total = ShortArray(asrCarry.size + n)
            asrCarry.copyInto(total)
            buf.copyInto(total, destinationOffset = asrCarry.size, startIndex = 0, endIndex = n)

            if (sourceRate % targetRate != 0) return resampleLinear(total, sourceRate, targetRate)

            val outLength = total.size / ratio
            val remainder = total.size % ratio
            val out = ShortArray(outLength)
            for (i in 0 until outLength) {
                var sum = 0
                val base = i * ratio
                for (j in 0 until ratio) sum += total[base + j].toInt()
                out[i] = (sum / ratio).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            resamplePhase = 0.0
            asrCarry = if (remainder > 0) {
                total.copyOfRange(total.size - remainder, total.size)
            } else {
                ShortArray(0)
            }
            return out
        }

        private fun resampleLinear(total: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
            if (total.size < 2) {
                asrCarry = total
                return ShortArray(0)
            }
            val step = sourceRate.toDouble() / targetRate.toDouble()
            val out = ArrayList<Short>(total.size * targetRate / sourceRate)
            var position = resamplePhase
            while (position + 1.0 < total.size) {
                val i = position.toInt()
                val fraction = position - i
                val sample = total[i] * (1.0 - fraction) + total[i + 1] * fraction
                out += sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                position += step
            }
            val consumed = position.toInt().coerceIn(0, total.size)
            resamplePhase = position - consumed
            asrCarry = if (consumed < total.size) total.copyOfRange(consumed, total.size) else ShortArray(0)
            return out.toShortArray()
        }
    }

    private fun readOptions(intent: Intent?) {
        recordingFormat = enumOrDefault(
            intent?.getStringExtra(EXTRA_RECORDING_FORMAT),
            RecordingFormat.M4A_AAC,
        )
        audioSourceMode = enumOrDefault(
            intent?.getStringExtra(EXTRA_AUDIO_SOURCE_MODE),
            RecordingAudioSourceMode.CLEAN,
        )
        enableAgc = intent?.getBooleanExtra(EXTRA_RECORDING_AGC, false) ?: false
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private fun goForeground(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(this, 0, openIntent, pendingFlags())

        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent, pendingFlags())
        val pauseResumeAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseResumeText = getString(if (paused) R.string.action_resume else R.string.action_pause)
        val pauseResumeIntent = Intent(this, RecordingService::class.java).setAction(pauseResumeAction)
        val pauseResumePi = PendingIntent.getService(this, 2, pauseResumeIntent, pendingFlags())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text, statusText))
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .addAction(0, pauseResumeText, pauseResumePi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun pendingFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_RECORDING_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001
        private const val WAKE_LOCK_TAG = "meetingscribe:recording"
        private const val MAX_RECORDING_MS = 4L * 60 * 60 * 1000
        // ~12.8 s of 48 kHz / 100 ms chunks. Generous headroom; encoding is normally many× realtime.
        private const val QUEUE_CAPACITY = 128
        private const val BACKGROUND_FINISH_JOIN_MS = 60_000L
        private const val AUDIO_LEVEL_INTERVAL_MS = 80L
        const val ACTION_START = "com.overmind.meetingscribe.START"
        const val ACTION_STOP = "com.overmind.meetingscribe.STOP"
        const val ACTION_PAUSE = "com.overmind.meetingscribe.PAUSE"
        const val ACTION_RESUME = "com.overmind.meetingscribe.RESUME"
        private const val EXTRA_RECORDING_FORMAT = "recording_format"
        private const val EXTRA_AUDIO_SOURCE_MODE = "audio_source_mode"
        private const val EXTRA_RECORDING_AGC = "recording_agc"

        fun start(context: Context, settings: AppSettings) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RECORDING_FORMAT, settings.recordingFormat.name)
                .putExtra(EXTRA_AUDIO_SOURCE_MODE, settings.recordingAudioSourceMode.name)
                .putExtra(EXTRA_RECORDING_AGC, settings.recordingAgc)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
