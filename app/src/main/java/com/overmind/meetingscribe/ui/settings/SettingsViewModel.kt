package com.overmind.meetingscribe.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import com.overmind.meetingscribe.audio.RecordingAudioSourceMode
import com.overmind.meetingscribe.audio.RecordingFormat
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.data.ModelComponent
import com.overmind.meetingscribe.data.ModelDownloader
import com.overmind.meetingscribe.data.ModelManager
import com.overmind.meetingscribe.data.RecordingEnvironmentPreset
import com.overmind.meetingscribe.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val manager = ModelManager(app)
    private val downloader = ModelDownloader(manager)

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** [progress] is non-null only while a download is in flight. */
    data class ModelUi(val ready: Boolean, val progress: Float?)

    private val _models = MutableStateFlow(snapshotModels())
    val models = _models.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts = _toasts.asSharedFlow()

    private fun snapshotModels(): Map<ModelComponent, ModelUi> =
        ModelComponent.entries.associateWith { ModelUi(manager.isReady(it), null) }

    fun refreshModels() {
        _models.value = snapshotModels()
    }

    fun download(component: ModelComponent) {
        if (_models.value[component]?.progress != null) return // already downloading
        viewModelScope.launch {
            setModel(component, ModelUi(ready = false, progress = 0f))
            downloader.ensure(component) { p -> setModel(component, ModelUi(false, p)) }
                .onSuccess { setModel(component, ModelUi(true, null)) }
                .onFailure {
                    setModel(component, ModelUi(manager.isReady(component), null))
                    _toasts.tryEmit("${component.displayName} 下载失败：${it.message}")
                }
        }
    }

    fun delete(component: ModelComponent) {
        manager.delete(component)
        refreshModels()
    }

    private fun setModel(component: ModelComponent, ui: ModelUi) {
        _models.update { it + (component to ui) }
    }

    private fun edit(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun setEngine(engine: EngineType) = edit { it.copy(engine = engine) }
    fun setOfflineModel(kind: OfflineModelKind) = edit { it.copy(offlineModel = kind) }
    fun setRecordingPreset(preset: RecordingEnvironmentPreset) = edit { preset.applyTo(it) }
    fun setLiveDiarization(enabled: Boolean) =
        edit { it.copy(liveDiarization = enabled, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setPostDiarization(enabled: Boolean) =
        edit { it.copy(postDiarization = enabled, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setDiarizationThreshold(value: Float) =
        edit { it.copy(diarizationThreshold = value, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setVadThreshold(value: Float) =
        edit { it.copy(vadThreshold = value, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setMicGain(value: Float) =
        edit { it.copy(micGain = value, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setRecordingFormat(value: RecordingFormat) = edit { it.copy(recordingFormat = value) }
    fun setRecordingAudioSourceMode(value: RecordingAudioSourceMode) =
        edit { it.copy(recordingAudioSourceMode = value, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }
    fun setRecordingAgc(enabled: Boolean) =
        edit { it.copy(recordingAgc = enabled, recordingPreset = RecordingEnvironmentPreset.CUSTOM) }

    fun setXfyunAppId(v: String) = edit { it.copy(xfyunAppId = v.trim()) }
    fun setXfyunApiKey(v: String) = edit { it.copy(xfyunApiKey = v.trim()) }
    fun setXfyunRole(enabled: Boolean) = edit { it.copy(xfyunRoleSeparation = enabled) }

    fun setDashScopeApiKey(v: String) = edit { it.copy(dashScopeApiKey = v.trim()) }
    fun setDashScopeModel(v: String) = edit { it.copy(dashScopeModel = v.trim()) }

    fun setVolcAppId(v: String) = edit { it.copy(volcAppId = v.trim()) }
    fun setVolcAccessKey(v: String) = edit { it.copy(volcAccessKey = v.trim()) }
    fun setVolcResourceId(v: String) = edit { it.copy(volcResourceId = v.trim()) }
}
