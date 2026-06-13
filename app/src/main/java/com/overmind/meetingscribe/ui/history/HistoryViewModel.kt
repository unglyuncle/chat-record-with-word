package com.overmind.meetingscribe.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overmind.meetingscribe.data.ExportedTranscript
import com.overmind.meetingscribe.data.HistoryRepository
import com.overmind.meetingscribe.data.HistorySession
import com.overmind.meetingscribe.data.TranscriptExportFormat
import com.overmind.meetingscribe.data.TranscriptExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val _sessions = MutableStateFlow<List<HistorySession>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val _exports = MutableSharedFlow<ExportedTranscript>(extraBufferCapacity = 2)
    val exports = _exports.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = withContext(Dispatchers.IO) {
                HistoryRepository.list(getApplication())
            }
        }
    }

    fun delete(session: HistorySession, deleteRecording: Boolean = false) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HistoryRepository.delete(getApplication(), session, deleteRecording)
            }
            _messages.emit(if (deleteRecording) "历史和录音已删除" else "历史记录已删除")
            refresh()
        }
    }

    fun rename(session: HistorySession, title: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HistoryRepository.rename(getApplication(), session, title)
            }
            _messages.emit("标题已更新")
            refresh()
        }
    }

    fun setPinned(session: HistorySession, pinned: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HistoryRepository.setPinned(getApplication(), session, pinned)
            }
            refresh()
        }
    }

    fun setFavorite(session: HistorySession, favorite: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HistoryRepository.setFavorite(getApplication(), session, favorite)
            }
            refresh()
        }
    }

    fun exportMarkdown(session: HistorySession) {
        export(session, TranscriptExportFormat.MARKDOWN)
    }

    fun exportHtml(session: HistorySession) {
        export(session, TranscriptExportFormat.HTML)
    }

    private fun export(session: HistorySession, format: TranscriptExportFormat) {
        if (session.segments.isEmpty()) {
            viewModelScope.launch { _messages.emit("这条历史没有可导出的转写内容") }
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    TranscriptExporter.export(
                        context = getApplication(),
                        segments = session.segments,
                        partial = "",
                        format = format,
                    )
                }
            }.onSuccess {
                _exports.emit(it)
                _messages.emit("文档已导出：${it.path}")
            }.onFailure {
                _messages.emit("导出失败：${it.message}")
            }
        }
    }
}
