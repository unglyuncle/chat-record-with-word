package com.overmind.meetingscribe.asr

import android.content.Context
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.data.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class BackgroundTaskStatus(
    val message: String,
    val active: Boolean,
)

/**
 * Process-wide coordinator between the recording service (audio producer) and the UI (consumer).
 *
 * Holds the single active [ASREngine], owns the transcript/state flows the UI observes, and
 * enforces the "stop -> release -> init" rule when switching engines. Living as an app-scoped
 * singleton lets the engine survive across the Activity being recreated while the foreground
 * [com.overmind.meetingscribe.audio.RecordingService] keeps feeding audio.
 */
object EngineController : ASRListener {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val switchMutex = Mutex()

    private var engine: ASREngine? = null
    @Volatile
    private var currentType: EngineType? = null
    @Volatile
    private var currentSettings: AppSettings? = null
    @Volatile
    private var currentHistoryId: String? = null
    @Volatile
    private var currentRecordingPath: String? = null
    @Volatile
    private var historyDirty = false
    @Volatile
    private var persistLoopStarted = false

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _sessionPaused = MutableStateFlow(false)
    val sessionPaused: StateFlow<Boolean> = _sessionPaused.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _backgroundStatus = MutableStateFlow<BackgroundTaskStatus?>(null)
    val backgroundStatus: StateFlow<BackgroundTaskStatus?> = _backgroundStatus.asStateFlow()
    private val backgroundStatusToken = AtomicLong(0)
    private val backgroundStatusLock = Any()
    private var backgroundSaveTasks = 0
    private var backgroundCorrectionTasks = 0

    private val _playbackRecordingPath = MutableStateFlow<String?>(null)
    val playbackRecordingPath: StateFlow<String?> = _playbackRecordingPath.asStateFlow()

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        startPersistLoopOnce()
    }

    /**
     * Persist the live transcript on a fixed cadence instead of once per segment. Each segment used
     * to trigger a full load-parse-serialize-rewrite of the whole session file, which is O(N²) over
     * a long meeting. Now segments only flag the session dirty; this loop flushes at most every
     * [PERSIST_INTERVAL_MS], and session end forces an immediate final write.
     */
    private fun startPersistLoopOnce() {
        if (persistLoopStarted) return
        persistLoopStarted = true
        scope.launch {
            while (true) {
                delay(PERSIST_INTERVAL_MS)
                if (!historyDirty) continue
                historyDirty = false
                val id = currentHistoryId ?: continue
                writeSession(id, currentRecordingPath, _segments.value, ended = false)
            }
        }
    }

    fun activeType(): EngineType? = currentType

    /**
     * Ensure an initialized engine for [AppSettings.engine] exists, tearing down any previous one
     * first. No-ops when the requested engine is already live (unless [force]). Suspends while
     * models load / the connection is validated.
     */
    suspend fun ensureEngine(settings: AppSettings, force: Boolean = false): Result<Unit> =
        switchMutex.withLock {
            if (!force && currentType == settings.engine && engine != null) {
                return@withLock Result.success(Unit)
            }
            engine?.let { old ->
                runCatching { old.stop() }
                runCatching { old.release() }
            }
            engine = null
            currentType = null
            _state.value = EngineState.INITIALIZING

            val created = ASREngineFactory.create(appContext, settings)
            created.setListener(this)
            val result = created.init()
            if (result.isSuccess) {
                engine = created
                currentType = settings.engine
                currentSettings = settings
                _state.value = EngineState.READY
            } else {
                runCatching { created.release() }
                _state.value = EngineState.ERROR
                scope.launch {
                    _errors.emit(result.exceptionOrNull()?.message ?: "引擎初始化失败")
                }
            }
            result
        }

    fun startSession(recordingPath: String? = null) {
        val e = engine ?: run {
            onError("尚未初始化引擎")
            return
        }
        _segments.value = emptyList()
        _partial.value = ""
        historyDirty = false
        _audioLevel.value = 0f
        clearCompletedBackgroundStatusIfIdle()
        _sessionActive.value = true
        _sessionPaused.value = false
        currentRecordingPath = recordingPath
        _playbackRecordingPath.value = recordingPath
        currentHistoryId = runCatching {
            HistoryRepository.createSession(
                context = appContext,
                recordingPath = recordingPath,
                engineType = currentType?.name,
            ).id
        }.getOrNull()
        runCatching { e.startStreaming() }.onFailure { onError("无法开始转写会话", it) }
    }

    /** Forward audio from the recording service to the active engine. */
    fun feed(pcm: ShortArray, length: Int) {
        engine?.feedAudio(pcm, length)
    }

    fun stopSession() {
        val finishingEngine = engine
        val finishingHistoryId = currentHistoryId
        val finishingRecordingPath = currentRecordingPath
        val finishingSegments = _segments.value

        engine = null
        currentType = null
        currentHistoryId = null
        currentRecordingPath = null
        historyDirty = false
        _sessionActive.value = false
        _sessionPaused.value = false
        _audioLevel.value = 0f
        _partial.value = ""
        _state.value = EngineState.STOPPED

        if (finishingHistoryId != null) {
            scope.launch { writeSession(finishingHistoryId, finishingRecordingPath, finishingSegments, ended = true) }
        }
        if (finishingEngine != null && finishingHistoryId != null) {
            scope.launch(Dispatchers.Default) {
                val listener = BackgroundSessionListener(
                    historyId = finishingHistoryId,
                    recordingPath = finishingRecordingPath,
                    initialSegments = finishingSegments,
                )
                finishingEngine.setListener(listener)
                runCatching { finishingEngine.stop() }
                if (!listener.awaitFinished()) {
                    finishBackgroundCorrection("上一段录音后台收尾超时")
                    _messages.emit("上一段录音后台收尾超时，已跳过部分后处理")
                }
                runCatching { finishingEngine.release() }
            }
        } else {
            runCatching { finishingEngine?.stop() }
            runCatching { finishingEngine?.release() }
        }
    }

    fun setSessionPaused(paused: Boolean) {
        _sessionPaused.value = paused
        if (paused) _audioLevel.value = 0f
    }

    fun reportAudioLevel(level: Float) {
        if (!_sessionActive.value || _sessionPaused.value) return
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    fun startBackgroundRecordingSave() {
        synchronized(backgroundStatusLock) {
            backgroundSaveTasks += 1
            publishBackgroundStatusLocked()
        }
    }

    fun finishBackgroundRecordingSave(message: String? = null) {
        synchronized(backgroundStatusLock) {
            if (backgroundSaveTasks > 0) backgroundSaveTasks -= 1
            publishBackgroundStatusLocked(doneMessage = message ?: "已保存到历史")
        }
    }

    fun failBackgroundRecordingSave(message: String) {
        synchronized(backgroundStatusLock) {
            if (backgroundSaveTasks > 0) backgroundSaveTasks -= 1
            publishBackgroundStatusLocked(doneMessage = message)
        }
    }

    fun shutdown() {
        scope.launch {
            switchMutex.withLock {
                engine?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                engine = null
                currentType = null
                currentSettings = null
                _state.value = EngineState.IDLE
            }
        }
    }

    // ---- ASRListener (engines call back on arbitrary threads; StateFlow writes are safe) ----

    override fun onState(state: EngineState) {
        _state.value = state
        if (state == EngineState.STOPPED || state == EngineState.ERROR) {
            // Force a final write of the latest snapshot (including any offline speaker correction
            // that arrived just before STOPPED), then detach so the timer can't rewrite a stale id.
            val id = currentHistoryId
            val path = currentRecordingPath
            currentHistoryId = null
            currentRecordingPath = null
            historyDirty = false
            if (id != null) scope.launch { writeSession(id, path, _segments.value, ended = true) }
        }
    }

    override fun onPartial(text: String) {
        _partial.value = text
    }

    override fun onSegment(segment: TranscriptSegment) {
        _segments.update { it + segment }
        _partial.value = ""
        historyDirty = true
    }

    override fun onSpeakersCorrected(speakerById: Map<String, Int>) {
        if (speakerById.isEmpty()) return
        _segments.update { list ->
            list.map { seg -> speakerById[seg.id]?.let { seg.copy(speaker = it) } ?: seg }
        }
        historyDirty = true
    }

    override fun onError(message: String, cause: Throwable?) {
        _state.value = EngineState.ERROR
        scope.launch { _errors.emit(message) }
    }

    fun onMessage(message: String) {
        scope.launch { _messages.emit(message) }
    }

    private fun setBackgroundStatus(status: BackgroundTaskStatus?, autoClearMs: Long = 0L) {
        val token = backgroundStatusToken.incrementAndGet()
        _backgroundStatus.value = status
        if (status != null && autoClearMs > 0L) {
            scope.launch {
                delay(autoClearMs)
                if (backgroundStatusToken.get() == token) {
                    _backgroundStatus.value = null
                }
            }
        }
    }

    private fun startBackgroundCorrection() {
        synchronized(backgroundStatusLock) {
            backgroundCorrectionTasks += 1
            publishBackgroundStatusLocked()
        }
    }

    private fun finishBackgroundCorrection(message: String? = null) {
        synchronized(backgroundStatusLock) {
            if (backgroundCorrectionTasks > 0) backgroundCorrectionTasks -= 1
            publishBackgroundStatusLocked(doneMessage = message ?: "已保存到历史")
        }
    }

    private fun publishBackgroundStatusLocked(doneMessage: String? = null) {
        val status = when {
            backgroundCorrectionTasks > 0 -> BackgroundTaskStatus("说话人校正中…", active = true)
            backgroundSaveTasks > 0 -> BackgroundTaskStatus("上一段录音正在后台保存…", active = true)
            doneMessage != null -> BackgroundTaskStatus(doneMessage, active = false)
            else -> null
        }
        setBackgroundStatus(
            status = status,
            autoClearMs = if (status?.active == false) STATUS_DONE_CLEAR_MS else 0L,
        )
    }

    private fun clearCompletedBackgroundStatusIfIdle() {
        synchronized(backgroundStatusLock) {
            if (backgroundSaveTasks == 0 && backgroundCorrectionTasks == 0 && _backgroundStatus.value?.active == false) {
                setBackgroundStatus(null)
            }
        }
    }

    private suspend fun writeSession(
        id: String,
        path: String?,
        segments: List<TranscriptSegment>,
        ended: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                HistoryRepository.updateSession(
                    context = appContext,
                    id = id,
                    recordingPath = path,
                    segments = segments,
                    endedAtMs = if (ended) System.currentTimeMillis() else null,
                )
            }
        }
    }

    private class BackgroundSessionListener(
        private val historyId: String,
        private val recordingPath: String?,
        initialSegments: List<TranscriptSegment>,
    ) : ASRListener {
        private val finished = CountDownLatch(1)
        private var segments: List<TranscriptSegment> = initialSegments
        private var correctionStarted = false
        private var finishedOnce = false

        fun awaitFinished(): Boolean =
            runCatching { finished.await(BACKGROUND_ENGINE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)

        override fun onState(state: EngineState) {
            when (state) {
                EngineState.FINALIZING -> {
                    if (!correctionStarted) {
                        correctionStarted = true
                        startBackgroundCorrection()
                    }
                }
                EngineState.STOPPED -> {
                    if (finishedOnce) return
                    finishedOnce = true
                    scope.launch { writeSession(historyId, recordingPath, segments, ended = true) }
                    if (correctionStarted) finishBackgroundCorrection() else publishFinishedWithoutCorrection()
                    finished.countDown()
                }
                EngineState.ERROR -> {
                    if (finishedOnce) return
                    finishedOnce = true
                    scope.launch { writeSession(historyId, recordingPath, segments, ended = true) }
                    if (correctionStarted) {
                        finishBackgroundCorrection("后台处理失败")
                    } else {
                        publishFinishedWithoutCorrection("后台处理失败")
                    }
                    finished.countDown()
                }
                else -> Unit
            }
        }

        override fun onPartial(text: String) = Unit

        override fun onSegment(segment: TranscriptSegment) {
            segments = segments + segment
            scope.launch { writeSession(historyId, recordingPath, segments, ended = false) }
        }

        override fun onSpeakersCorrected(speakerById: Map<String, Int>) {
            if (speakerById.isEmpty()) return
            segments = segments.map { seg -> speakerById[seg.id]?.let { seg.copy(speaker = it) } ?: seg }
            scope.launch { writeSession(historyId, recordingPath, segments, ended = false) }
        }

        override fun onError(message: String, cause: Throwable?) {
            if (finishedOnce) return
            finishedOnce = true
            scope.launch {
                writeSession(historyId, recordingPath, segments, ended = true)
                if (correctionStarted) {
                    finishBackgroundCorrection("后台处理失败：$message")
                } else {
                    publishFinishedWithoutCorrection("后台处理失败：$message")
                }
                _messages.emit("上一段录音后台收尾失败：$message")
            }
            finished.countDown()
        }

        private fun publishFinishedWithoutCorrection(message: String = "已保存到历史") {
            synchronized(backgroundStatusLock) {
                publishBackgroundStatusLocked(doneMessage = message)
            }
        }
    }

    private const val PERSIST_INTERVAL_MS = 1500L
    private const val BACKGROUND_ENGINE_STOP_TIMEOUT_MS = 120_000L
    private const val STATUS_DONE_CLEAR_MS = 6_000L
}
