package com.overmind.meetingscribe.ui.transcribe

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.overmind.meetingscribe.R
import com.overmind.meetingscribe.asr.BackgroundTaskStatus
import com.overmind.meetingscribe.asr.EngineState
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.asr.offline.OfflineModelKind
import com.overmind.meetingscribe.audio.LiveRecordingAudioBuffer
import com.overmind.meetingscribe.data.ExportedTranscript
import com.overmind.meetingscribe.ui.components.PartialItem
import com.overmind.meetingscribe.ui.components.SegmentItem
import com.overmind.meetingscribe.ui.components.SegmentLoopPlaybackBar
import com.overmind.meetingscribe.ui.components.SegmentLoopPlaybackController
import com.overmind.meetingscribe.ui.components.SegmentLoopPlaybackState
import com.overmind.meetingscribe.ui.components.TranscriptChatBackground
import com.overmind.meetingscribe.ui.components.nextLoopSpeed
import com.overmind.meetingscribe.ui.components.speakerLabel
import kotlinx.coroutines.launch
import java.io.File

private const val LIVE_SESSION_ID = "current_transcript"
private const val PLAYBACK_CONTEXT_MS = 500L
private const val FALLBACK_SEGMENT_MS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    vm: TranscribeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val sessionActive by vm.sessionActive.collectAsStateWithLifecycle()
    val sessionPaused by vm.sessionPaused.collectAsStateWithLifecycle()
    val audioLevel by vm.audioLevel.collectAsStateWithLifecycle()
    val backgroundStatus by vm.backgroundStatus.collectAsStateWithLifecycle()
    val playbackRecordingPath by vm.playbackRecordingPath.collectAsStateWithLifecycle()
    var playbackState by remember { mutableStateOf(SegmentLoopPlaybackState()) }
    val playback = remember { SegmentLoopPlaybackController { playbackState = it } }

    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }
    LaunchedEffect(sessionActive) {
        if (sessionActive) playback.stop()
    }
    LaunchedEffect(Unit) {
        vm.errors.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.exports.collect { exported ->
            runCatching { shareExport(context, exported) }
                .onFailure { snackbarHostState.showSnackbar("分享失败：${it.message}") }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.toggleRecording()
    }

    fun onRecordClick() {
        if (sessionActive) {
            vm.toggleRecording()
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) vm.toggleRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    fun playSegment(segment: TranscriptSegment) {
        val path = playbackRecordingPath
        if (!sessionActive && path.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("当前聊天还没有可播放的录音") }
            return
        }
        runCatching {
            if (sessionActive) {
                val slice = LiveRecordingAudioBuffer.slice(
                    startMs = (segment.startMs - PLAYBACK_CONTEXT_MS).coerceAtLeast(0L),
                    endMs = segmentPlaybackEnd(segment, segments) + PLAYBACK_CONTEXT_MS,
                ) ?: error("这句话的录音还没进入可播放缓存，稍等一下再点")
                playback.playLive(LIVE_SESSION_ID, slice, segments, segment)
            } else {
                playback.play(LIVE_SESSION_ID, path, segments, segment)
            }
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar(it.message ?: "无法播放对应录音") }
        }
    }

    fun playAdjacent(offset: Int) {
        val currentIndex = segments.indexOfFirst { it.id == playbackState.segmentId }
        val targetIndex = if (currentIndex >= 0) {
            (currentIndex + offset).coerceIn(0, segments.lastIndex)
        } else if (offset < 0) {
            0
        } else {
            segments.lastIndex
        }
        segments.getOrNull(targetIndex)?.let { playSegment(it) }
    }

    Scaffold(
        topBar = {
            val canExport = segments.isNotEmpty() || partial.isNotBlank()
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_transcribe))
                        Text(
                            text = engineSubtitle(settings.engine, settings.offlineModel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenHistory) {
                        Text("历史")
                    }
                    TextButton(onClick = vm::exportMarkdown, enabled = canExport) {
                        Text("MD")
                    }
                    TextButton(onClick = vm::exportHtml, enabled = canExport) {
                        Text("HTML")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.title_settings))
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (playbackState.sessionId == LIVE_SESSION_ID && playbackState.hasLoop) {
                    SegmentLoopPlaybackBar(
                        sessionId = LIVE_SESSION_ID,
                        title = "点击消息播放对应录音",
                        segments = segments,
                        state = playbackState,
                        onToggle = { playback.togglePlayPause(LIVE_SESSION_ID, playbackRecordingPath, segments) },
                        onSeek = { playback.seekTo(it) },
                        onSpeed = { playback.setSpeed(nextLoopSpeed(playbackState.speed)) },
                        onPrevious = { playAdjacent(-1) },
                        onNext = { playAdjacent(1) },
                        onClose = { playback.stop() },
                    )
                }
                backgroundStatus?.let { status ->
                    BackgroundStatusStrip(status = status)
                }
                RecordBar(
                    state = state,
                    sessionActive = sessionActive,
                    sessionPaused = sessionPaused,
                    audioLevel = audioLevel,
                    onToggle = ::onRecordClick,
                    onPause = vm::pauseRecording,
                    onResume = vm::resumeRecording,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        TranscriptArea(
            modifier = Modifier.fillMaxSize().padding(padding),
            segments = segments,
            partial = partial,
            scope = scope,
            highlightedSegmentId = if (playbackState.sessionId == LIVE_SESSION_ID) playbackState.segmentId else null,
            onSegmentClick = ::playSegment,
        )
    }
}

private fun segmentPlaybackEnd(segment: TranscriptSegment, segments: List<TranscriptSegment>): Long {
    val nextStart = segments
        .dropWhile { it.id != segment.id }
        .drop(1)
        .firstOrNull()
        ?.startMs
        ?.takeIf { it > segment.startMs }
    val ownEnd = segment.endMs.takeIf { it > segment.startMs }
    return when {
        ownEnd != null && nextStart != null -> minOf(ownEnd, nextStart)
        ownEnd != null -> ownEnd
        nextStart != null -> nextStart
        else -> segment.startMs + FALLBACK_SEGMENT_MS
    }
}

private fun shareExport(context: Context, exported: ExportedTranscript) {
    val file = File(exported.path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = exported.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享转写文档"))
}

@Composable
private fun TranscriptArea(
    modifier: Modifier,
    segments: List<TranscriptSegment>,
    partial: String,
    scope: kotlinx.coroutines.CoroutineScope,
    highlightedSegmentId: String?,
    onSegmentClick: (TranscriptSegment) -> Unit,
) {
    val listState = rememberLazyListState()
    val hasPartial = partial.isNotBlank()
    val itemCount = segments.size + if (hasPartial) 1 else 0

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var autoFollow by remember { mutableStateOf(true) }

    // User dragging away from the bottom pauses auto-scroll ("自由滑动").
    LaunchedEffect(listState.isScrollInProgress, atBottom) {
        if (listState.isScrollInProgress && !atBottom) autoFollow = false
    }
    // Reaching the bottom again re-arms following.
    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }
    // New content scrolls to the end only while following. scrollToItem (not animate) keeps long
    // transcripts smooth — animating across hundreds of items is what caused the lag.
    LaunchedEffect(itemCount, partial) {
        if (autoFollow && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Box(modifier = modifier.background(TranscriptChatBackground)) {
        if (itemCount == 0) {
            Text(
                text = stringResource(R.string.transcript_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(segments, key = { it.id }) { seg ->
                    SegmentItem(
                        segment = seg,
                        onClick = { onSegmentClick(seg) },
                        highlighted = highlightedSegmentId == seg.id,
                    )
                }
                if (hasPartial) {
                    item(key = "partial") { PartialItem(partial) }
                }
            }
        }

        if (!atBottom && itemCount > 0) {
            SmallFloatingActionButton(
                onClick = {
                    autoFollow = true
                    scope.launch { listState.scrollToItem(itemCount - 1) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.resume_autoscroll))
            }
        }
    }
}

@Composable
private fun SegmentDetailDialog(segment: TranscriptSegment, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val time = "%02d:%02d".format(segment.startMs / 1000 / 60, segment.startMs / 1000 % 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(speakerLabel(segment.speaker)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (segment.startMs > 0) {
                    Text(
                        text = "时间 $time",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(text = segment.text, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(segment.text))
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RecordBar(
    state: EngineState,
    sessionActive: Boolean,
    sessionPaused: Boolean,
    audioLevel: Float,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText(state, sessionActive, sessionPaused),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (sessionActive) {
                    Spacer(Modifier.height(8.dp))
                    AudioLevelMeter(
                        level = if (sessionPaused) 0f else audioLevel,
                        enabled = !sessionPaused,
                    )
                }
                if (state == EngineState.FINALIZING) {
                    Text(
                        text = "请稍候，正在对整段录音做说话人校正",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state == EngineState.INITIALIZING || state == EngineState.FINALIZING) {
                CircularProgressIndicator(modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(16.dp))
            }
            if (sessionActive) {
                IconButton(onClick = if (sessionPaused) onResume else onPause) {
                    Icon(
                        imageVector = if (sessionPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (sessionPaused) "继续录音" else "暂停录音",
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            LargeFloatingActionButton(
                onClick = onToggle,
                containerColor = if (sessionActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = if (sessionActive) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (sessionActive) {
                        stringResource(R.string.action_stop)
                    } else {
                        stringResource(R.string.action_start)
                    },
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun BackgroundStatusStrip(status: BackgroundTaskStatus) {
    Surface(
        tonalElevation = 1.dp,
        color = if (status.active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AudioLevelMeter(level: Float, enabled: Boolean) {
    val amplitudes = remember {
        listOf(0.45f, 0.7f, 1.0f, 0.62f, 0.86f, 0.54f, 0.76f, 0.95f, 0.5f, 0.82f, 0.66f, 0.9f)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(28.dp),
    ) {
        Text(
            text = "麦克风",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 5.dp),
        )
        amplitudes.forEach { weight ->
            val barLevel = if (enabled) {
                (level * weight).coerceIn(0.04f, 1f)
            } else {
                0.04f
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((4f + 22f * barLevel).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f + 0.76f * barLevel),
                    ),
            )
        }
    }
}

@Composable
private fun statusText(state: EngineState, sessionActive: Boolean, sessionPaused: Boolean): String = when {
    state == EngineState.INITIALIZING -> stringResource(R.string.status_initializing)
    state == EngineState.FINALIZING -> stringResource(R.string.status_finalizing)
    state == EngineState.ERROR -> stringResource(R.string.status_error)
    sessionPaused -> "录音已暂停"
    sessionActive -> stringResource(R.string.status_recording)
    else -> stringResource(R.string.status_idle)
}

@Composable
private fun engineSubtitle(engine: EngineType, offlineModel: OfflineModelKind): String {
    val name = stringResource(engine.labelRes)
    return if (engine == EngineType.SHERPA_OFFLINE) {
        "$name · ${offlineModel.modelName}"
    } else {
        name
    }
}
