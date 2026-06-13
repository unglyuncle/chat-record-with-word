package com.overmind.meetingscribe.ui.transcribe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overmind.meetingscribe.asr.EngineController
import com.overmind.meetingscribe.audio.RecordingService
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.data.ExportedTranscript
import com.overmind.meetingscribe.data.SettingsRepository
import com.overmind.meetingscribe.data.TranscriptExportFormat
import com.overmind.meetingscribe.data.TranscriptExporter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val state = EngineController.state
    val segments = EngineController.segments
    val partial = EngineController.partial
    val sessionActive = EngineController.sessionActive
    val sessionPaused = EngineController.sessionPaused
    val audioLevel = EngineController.audioLevel
    val backgroundStatus = EngineController.backgroundStatus
    val playbackRecordingPath = EngineController.playbackRecordingPath
    val errors = EngineController.errors
    val messages = EngineController.messages

    private val _exports = MutableSharedFlow<ExportedTranscript>(extraBufferCapacity = 2)
    val exports = _exports.asSharedFlow()

    fun toggleRecording() {
        if (sessionActive.value) stopRecording() else startRecording()
    }

    private fun startRecording() {
        viewModelScope.launch {
            // Ensure the engine for the current settings is initialized, then hand audio capture
            // to the foreground service. ensureEngine reports its own errors via EngineController.
            EngineController.ensureEngine(settings.value)
                .onSuccess { RecordingService.start(getApplication(), settings.value) }
        }
    }

    private fun stopRecording() {
        RecordingService.stop(getApplication())
    }

    fun pauseRecording() {
        RecordingService.pause(getApplication())
    }

    fun resumeRecording() {
        RecordingService.resume(getApplication())
    }

    fun exportMarkdown() {
        export(TranscriptExportFormat.MARKDOWN)
    }

    fun exportHtml() {
        export(TranscriptExportFormat.HTML)
    }

    private fun export(format: TranscriptExportFormat) {
        val snapshot = segments.value
        val liveText = partial.value
        if (snapshot.isEmpty() && liveText.isBlank()) {
            viewModelScope.launch { EngineController.onMessage("没有可导出的转写内容") }
            return
        }
        viewModelScope.launch {
            runCatching {
                TranscriptExporter.export(getApplication(), snapshot, liveText, format)
            }.onSuccess {
                _exports.emit(it)
                EngineController.onMessage("文档已导出：${it.path}")
            }.onFailure {
                EngineController.onMessage("导出失败：${it.message}")
            }
        }
    }
}
