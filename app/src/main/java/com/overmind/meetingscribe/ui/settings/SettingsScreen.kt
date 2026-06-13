package com.overmind.meetingscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.overmind.meetingscribe.R
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import com.overmind.meetingscribe.audio.RecordingAudioSourceMode
import com.overmind.meetingscribe.audio.RecordingFormat
import com.overmind.meetingscribe.data.ModelComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refreshModels() }
    LaunchedEffect(Unit) { vm.toasts.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.settings_engine_section),
                hint = stringResource(R.string.settings_engine_hint),
            ) {
                EngineType.entries.forEach { engine ->
                    SelectableRow(
                        selected = settings.engine == engine,
                        title = stringResource(engine.labelRes),
                        onClick = { vm.setEngine(engine) },
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.settings_offline_model_section),
                hint = stringResource(R.string.settings_offline_model_hint),
            ) {
                OfflineModelKind.entries.forEach { kind ->
                    SelectableRow(
                        selected = settings.offlineModel == kind,
                        title = kind.modelName,
                        onClick = { vm.setOfflineModel(kind) },
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.settings_diarization_section),
                hint = "离线使用时，可以边录边先标出是谁在说话；录完后再统一整理一遍，结果会更准。",
            ) {
                SwitchRow(
                    title = "录音时先标出说话人",
                    checked = settings.liveDiarization,
                    onChange = vm::setLiveDiarization,
                )
                SwitchRow(
                    title = "录完后自动整理说话人",
                    checked = settings.postDiarization,
                    onChange = vm::setPostDiarization,
                )
                Text(
                    text = "区分人数：%.2f（调低会分得更细）".format(settings.diarizationThreshold),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = settings.diarizationThreshold,
                    onValueChange = vm::setDiarizationThreshold,
                    valueRange = 0.3f..0.8f,
                    steps = 9,
                )
            }

            SectionCard(
                title = "识别灵敏度",
                hint = "离手机远、说话小声时，可以让它更容易听到人声；误触发太多时再调低。",
            ) {
                Text(
                    text = "更容易听到人声：%.2f".format(settings.vadThreshold),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = settings.vadThreshold,
                    onValueChange = vm::setVadThreshold,
                    valueRange = 0.08f..0.6f,
                    steps = 25,
                )
                Text(
                    text = "小声增强：%.1f 倍".format(settings.micGain),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = settings.micGain,
                    onValueChange = vm::setMicGain,
                    valueRange = 1f..12f,
                    steps = 21,
                )
            }

            SectionCard(
                title = "录音保存与音质",
                hint = "想让录音更好听，选“自然收音”；想让文字更稳，选“突出人声”。默认会尽量省空间。",
            ) {
                Text(
                    text = "保存方式",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                RecordingFormat.entries.forEach { format ->
                    SelectableRow(
                        selected = settings.recordingFormat == format,
                        title = recordingFormatLabel(format),
                        onClick = { vm.setRecordingFormat(format) },
                    )
                }
                Text(
                    text = "收音方式",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                RecordingAudioSourceMode.entries.forEach { mode ->
                    SelectableRow(
                        selected = settings.recordingAudioSourceMode == mode,
                        title = recordingSourceLabel(mode),
                        onClick = { vm.setRecordingAudioSourceMode(mode) },
                    )
                }
                SwitchRow(
                    title = "自动放大小声说话（噪声也可能变大）",
                    checked = settings.recordingAgc,
                    onChange = vm::setRecordingAgc,
                )
            }

            SectionCard(title = stringResource(R.string.settings_models_section)) {
                ModelComponent.entries.forEach { component ->
                    val ui = models[component]
                    ModelRow(
                        name = component.displayName,
                        size = component.approxSize,
                        ready = ui?.ready == true,
                        progress = ui?.progress,
                        onDownload = { vm.download(component) },
                        onDelete = { vm.delete(component) },
                    )
                }
            }

            SectionCard(
                title = "讯飞在线识别",
                hint = "需要在讯飞控制台复制应用编号和接口密钥。",
            ) {
                CredentialField("应用编号", settings.xfyunAppId, vm::setXfyunAppId)
                CredentialField("接口密钥", settings.xfyunApiKey, vm::setXfyunApiKey)
                SwitchRow(
                    title = "自动区分说话人",
                    checked = settings.xfyunRoleSeparation,
                    onChange = vm::setXfyunRole,
                )
            }

            SectionCard(
                title = "阿里在线识别",
                hint = "阿里的实时识别暂时不能自动区分说话人。",
            ) {
                CredentialField("阿里密钥", settings.dashScopeApiKey, vm::setDashScopeApiKey)
                CredentialField("识别版本", settings.dashScopeModel, vm::setDashScopeModel)
            }

            SectionCard(
                title = "火山在线识别",
                hint = "需要在火山控制台复制应用编号、访问密钥和资源编号。",
            ) {
                CredentialField("应用编号", settings.volcAppId, vm::setVolcAppId)
                CredentialField("访问密钥", settings.volcAccessKey, vm::setVolcAccessKey)
                CredentialField("资源编号", settings.volcResourceId, vm::setVolcResourceId)
            }

            Spacer(Modifier.width(0.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun SelectableRow(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelRow(
    name: String,
    size: String,
    ready: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            progress != null -> {
                CircularProgressIndicator(modifier = Modifier.width(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            ready -> {
                Text(
                    stringResource(R.string.model_downloaded),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onDelete) { Text(stringResource(R.string.model_delete)) }
            }
            else -> {
                Button(onClick = onDownload) { Text(stringResource(R.string.model_download)) }
            }
        }
    }
}

@Composable
private fun CredentialField(label: String, value: String, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value) }
    // Seed once when the stored value loads from DataStore (without clobbering active edits).
    LaunchedEffect(value) {
        if (text.isBlank() && value.isNotBlank()) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

private fun recordingFormatLabel(format: RecordingFormat): String = when (format) {
    RecordingFormat.M4A_AAC -> "省空间（推荐）"
    RecordingFormat.WAV -> "尽量保留原声（占空间大）"
}

private fun recordingSourceLabel(mode: RecordingAudioSourceMode): String = when (mode) {
    RecordingAudioSourceMode.CLEAN -> "自然收音（录音更好听）"
    RecordingAudioSourceMode.VOICE_RECOGNITION -> "突出人声（文字更稳）"
    RecordingAudioSourceMode.CALL_PROCESSING -> "强力降噪（嘈杂时再用）"
}
