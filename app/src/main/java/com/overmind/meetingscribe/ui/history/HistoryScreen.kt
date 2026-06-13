package com.overmind.meetingscribe.ui.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.audio.AudioConstants
import com.overmind.meetingscribe.audio.WavIo
import com.overmind.meetingscribe.data.ExportedTranscript
import com.overmind.meetingscribe.data.HistorySession
import com.overmind.meetingscribe.ui.components.SegmentItem
import com.overmind.meetingscribe.ui.components.TranscriptChatBackground
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var playbackState by remember { mutableStateOf(PlaybackState()) }
    val playback = remember { SegmentPlaybackController { playbackState = it } }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    var renameTarget by remember { mutableStateOf<HistorySession?>(null) }
    var deleteTarget by remember { mutableStateOf<HistorySession?>(null) }

    val filter = HistoryFilter.valueOf(filterName)
    val selected = sessions.firstOrNull { it.id == selectedId }
    val visibleSessions = remember(sessions, query, filterName) {
        sessions.filter { it.matches(query, filter) }
    }

    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.exports.collect { exported ->
            runCatching { shareExport(context, exported) }
                .onFailure { snackbarHostState.showSnackbar("分享失败：${it.message}") }
        }
    }

    fun leaveDetail() {
        playback.stop()
        selectedId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selected?.title ?: "历史记录",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected == null) onBack() else leaveDetail() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selected != null) {
                        TextButton(onClick = { vm.setPinned(selected, !selected.pinned) }) {
                            Text(if (selected.pinned) "取消置顶" else "置顶")
                        }
                        IconButton(onClick = { vm.setFavorite(selected, !selected.favorite) }) {
                            Icon(
                                imageVector = if (selected.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (selected.favorite) "取消收藏" else "收藏",
                            )
                        }
                        IconButton(onClick = { deleteTarget = selected }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                },
            )
        },
        bottomBar = {
            selected
                ?.takeIf {
                    it.hasRecording &&
                        it.segments.isNotEmpty() &&
                        playbackState.sessionId == it.id &&
                        playbackState.hasLoop
                }
                ?.let { session ->
                PlaybackBar(
                    session = session,
                    state = playbackState,
                    onToggle = { playback.togglePlayPause(session) },
                    onSeek = { playback.seekTo(it) },
                    onSpeed = { playback.setSpeed(nextSpeed(playbackState.speed)) },
                    onPrevious = { playback.previous(session) },
                    onNext = { playback.next(session) },
                    onClose = { playback.stop() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (selected == null) {
            HistoryListContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                sessions = visibleSessions,
                allSessionsEmpty = sessions.isEmpty(),
                query = query,
                onQueryChange = { query = it },
                filter = filter,
                onFilterChange = { filterName = it.name },
                onOpenSession = { selectedId = it.id },
            )
        } else {
            HistoryDetailContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                session = selected,
                playbackState = playbackState,
                onPlayWholeRecording = {
                    runCatching { openRecording(context, selected.recordingPath) }
                        .onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "无法播放录音") } }
                },
                onPlaySegment = { segment ->
                    runCatching { playback.play(selected, segment) }
                        .onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "无法播放对应录音") } }
                },
                onRename = { renameTarget = selected },
                onExportMarkdown = { vm.exportMarkdown(selected) },
                onExportHtml = { vm.exportHtml(selected) },
            )
        }
    }

    renameTarget?.let { session ->
        RenameHistoryDialog(
            session = session,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                vm.rename(session, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { session ->
        DeleteHistoryDialog(
            session = session,
            onDismiss = { deleteTarget = null },
            onDeleteRecord = {
                playback.stop()
                vm.delete(session, deleteRecording = false)
                deleteTarget = null
                if (selectedId == session.id) selectedId = null
            },
            onDeleteAll = {
                playback.stop()
                vm.delete(session, deleteRecording = true)
                deleteTarget = null
                if (selectedId == session.id) selectedId = null
            },
        )
    }
}

@Composable
private fun HistoryListContent(
    modifier: Modifier,
    sessions: List<HistorySession>,
    allSessionsEmpty: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    onOpenSession: (HistorySession) -> Unit,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索会议或文字") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { onFilterChange(item) },
                    label = { Text(item.label) },
                )
            }
        }
        when {
            allSessionsEmpty -> EmptyHistoryText("还没有历史记录")
            sessions.isEmpty() -> EmptyHistoryText("没有匹配的历史记录")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        HistoryRow(session = session, onClick = { onOpenSession(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun HistoryRow(session: HistorySession, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (session.favorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "已收藏",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "${fullDate(session.createdAtMs)} · ${session.segments.size} 段 · ${formatDuration(session.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.pinned) AssistChip(onClick = {}, label = { Text("置顶") })
                AssistChip(onClick = {}, label = { Text(if (session.hasRecording) "包含录音" else "仅文字") })
            }
        }
    }
}

@Composable
private fun HistoryDetailContent(
    modifier: Modifier,
    session: HistorySession,
    playbackState: PlaybackState,
    onPlayWholeRecording: () -> Unit,
    onPlaySegment: (TranscriptSegment) -> Unit,
    onRename: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportHtml: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentSegmentId = playbackState.takeIf { it.sessionId == session.id }?.segmentId

    LaunchedEffect(currentSegmentId, session.id) {
        val index = session.segments.indexOfFirst { it.id == currentSegmentId }
        if (index >= 0) listState.animateScrollToItem(index + 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.background(TranscriptChatBackground),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${fullDate(session.createdAtMs)} · ${session.segments.size} 段 · ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onRename) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("重命名")
                        }
                        TextButton(onClick = onPlayWholeRecording, enabled = session.hasRecording) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("外部播放")
                        }
                        TextButton(onClick = onExportMarkdown, enabled = session.segments.isNotEmpty()) {
                            Text("导出 MD")
                        }
                        TextButton(onClick = onExportHtml, enabled = session.segments.isNotEmpty()) {
                            Text("导出 HTML")
                        }
                    }
                }
            }
        }
        if (session.segments.isEmpty()) {
            item {
                Text(
                    text = "这条历史还没有转写文字",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(session.segments, key = { it.id }) { segment ->
                SegmentItem(
                    segment = segment,
                    onClick = { onPlaySegment(segment) },
                    highlighted = currentSegmentId == segment.id,
                )
            }
        }
    }
}

@Composable
private fun RenameHistoryDialog(
    session: HistorySession,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(session.id) { mutableStateOf(session.customTitle ?: session.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("会议标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DeleteHistoryDialog(
    session: HistorySession,
    onDismiss: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条历史？") },
        text = {
            Text(
                if (session.hasRecording) {
                    "可以只删除列表里的记录，也可以连录音文件一起删除。"
                } else {
                    "这条历史没有可用录音文件。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDeleteAll, enabled = session.hasRecording) {
                Text("记录和录音都删")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDeleteRecord) { Text("只删记录") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private data class PlaybackState(
    val sessionId: String? = null,
    val segmentId: String? = null,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
) {
    val loopDurationMs: Long
        get() = (loopEndMs - loopStartMs).coerceAtLeast(0L)

    val hasLoop: Boolean
        get() = segmentId != null && loopEndMs > loopStartMs
}

@Composable
private fun PlaybackBar(
    session: HistorySession,
    state: PlaybackState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val active = state.sessionId == session.id && state.hasLoop
    val duration = if (active) state.loopDurationMs else 0L
    val position = if (active) {
        (state.positionMs - state.loopStartMs).coerceIn(0L, duration.coerceAtLeast(0L))
    } else {
        0L
    }
    val current = if (active) session.segments.firstOrNull { it.id == state.segmentId } else null

    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.let { "单句循环 · ${it.text.take(42)}" } ?: "点击消息开始单句循环",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭播放")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一句")
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (active && state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (active && state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一句")
                }
                TextButton(onClick = onSpeed) {
                    Text("%.2gx".format(state.speed))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatDuration(position)}/${formatDuration(duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction -> onSeek(state.loopStartMs + (duration * fraction).toLong()) },
                enabled = active && duration > 0L,
            )
        }
    }
}

private fun nextSpeed(current: Float): Float {
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    val index = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return speeds[(index + 1).floorMod(speeds.size)]
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

private class SegmentPlaybackController(
    private val onState: (PlaybackState) -> Unit,
) {
    private data class WavSlice(
        val samples: ShortArray,
        val sampleRate: Int,
        val startMs: Long,
        val endMs: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wavTrack: AudioTrack? = null
    private var wavSlice: WavSlice? = null
    private var tickRunnable: Runnable? = null
    private var loopRunnable: Runnable? = null
    private var state = PlaybackState()
    private var activeSegments: List<TranscriptSegment> = emptyList()

    fun play(session: HistorySession, segment: TranscriptSegment? = null) {
        require(session.hasRecording) { "录音文件不存在" }
        val target = segment
            ?: activeSegments.firstOrNull { it.id == state.segmentId }
            ?: session.segments.firstOrNull()
        require(target != null) { "这条历史还没有可播放的文字" }
        val file = session.recordingPath?.let { File(it) }
        require(file != null && file.exists()) { "录音文件不存在" }
        if (file.extension.equals("wav", ignoreCase = true)) {
            playWavSegment(session, file, target)
            return
        }
        val mediaPlayer = ensurePlayer(session)
        setLoopSegment(target)
        mediaPlayer.start()
        state = state.copy(isPlaying = true)
        applySpeed()
        publish()
        scheduleTick()
        scheduleLoopRestart()
    }

    fun togglePlayPause(session: HistorySession) {
        wavTrack?.takeIf { state.sessionId == session.id }?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                state = state.copy(isPlaying = false, positionMs = currentWavPositionMs())
                publish()
                stopTick()
            } else {
                if (state.positionMs >= state.loopEndMs) seekToInternal(state.loopStartMs)
                track.play()
                state = state.copy(isPlaying = true, positionMs = currentWavPositionMs())
                publish()
                scheduleTick()
            }
            return
        }
        val mediaPlayer = player.takeIf { state.sessionId == session.id } ?: run {
            play(session, session.segments.firstOrNull())
            return
        }
        if (!state.hasLoop) {
            play(session, session.segments.firstOrNull())
            return
        }
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            state = state.copy(isPlaying = false, positionMs = mediaPlayer.currentPosition.toLong())
            publish()
            stopTick()
            stopLoopTimer()
        } else {
            if (state.hasLoop && state.positionMs >= state.loopEndMs) {
                seekToInternal(state.loopStartMs)
            }
            mediaPlayer.start()
            state = state.copy(isPlaying = true, positionMs = mediaPlayer.currentPosition.toLong())
            publish()
            scheduleTick()
            scheduleLoopRestart()
        }
    }

    fun seekTo(positionMs: Long) {
        if (player == null && wavTrack == null) return
        seekToInternal(positionMs)
        publish()
        if (state.isPlaying) scheduleLoopRestart()
    }

    fun previous(session: HistorySession) {
        if (state.sessionId != session.id) {
            play(session, session.segments.firstOrNull())
            return
        }
        val targetIndex = (currentIndex() - 1).coerceAtLeast(0)
        activeSegments.getOrNull(targetIndex)?.let { play(session, it) }
    }

    fun next(session: HistorySession) {
        if (state.sessionId != session.id) {
            play(session, session.segments.firstOrNull())
            return
        }
        val targetIndex = (currentIndex() + 1).coerceAtMost(activeSegments.lastIndex)
        activeSegments.getOrNull(targetIndex)?.let { play(session, it) }
    }

    fun setSpeed(speed: Float) {
        state = state.copy(speed = speed)
        applySpeed()
        publish()
        if (state.isPlaying) scheduleLoopRestart()
    }

    fun stop() {
        stopTick()
        stopLoopTimer()
        wavTrack?.let {
            runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() }
            runCatching { it.release() }
        }
        wavTrack = null
        wavSlice = null
        player?.let {
            runCatching {
                if (it.isPlaying) it.stop()
            }
            runCatching { it.release() }
        }
        player = null
        activeSegments = emptyList()
        state = PlaybackState(speed = state.speed)
        publish()
    }

    fun release() {
        stop()
    }

    private fun ensurePlayer(session: HistorySession): MediaPlayer {
        player?.takeIf { state.sessionId == session.id }?.let {
            activeSegments = session.segments
            return it
        }
        stop()
        val file = session.recordingPath?.let { File(it) }
        require(file != null && file.exists()) { "录音文件不存在" }
        activeSegments = session.segments
        val mediaPlayer = createMediaPlayer(file).apply {
            setOnCompletionListener { completedPlayer ->
                stopTick()
                if (state.hasLoop) {
                    seekToInternal(state.loopStartMs)
                    completedPlayer.start()
                    state = state.copy(isPlaying = true)
                    publish()
                    scheduleTick()
                } else {
                    val duration = state.durationMs.coerceAtLeast(completedPlayer.duration.toLong())
                    state = state.copy(
                        positionMs = duration,
                        segmentId = segmentIdAt(duration),
                        isPlaying = false,
                        durationMs = duration,
                    )
                    publish()
                }
            }
        }
        player = mediaPlayer
        val duration = mediaPlayer.duration.toLong().coerceAtLeast(session.durationMs)
        state = PlaybackState(
            sessionId = session.id,
            segmentId = segmentIdAt(0L),
            positionMs = 0L,
            durationMs = duration,
            isPlaying = false,
            speed = state.speed,
        )
        applySpeed()
        publish()
        return mediaPlayer
    }

    private fun createMediaPlayer(file: File): MediaPlayer {
        val mediaPlayer = MediaPlayer()
        try {
            val loaded = runCatching {
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.prepare()
            }.recoverCatching { first ->
                mediaPlayer.reset()
                FileInputStream(file).use { input ->
                    mediaPlayer.setDataSource(input.fd)
                    mediaPlayer.prepare()
                }
            }
            loaded.getOrElse { throw IllegalStateException(playbackFailureMessage(file), it) }
            return mediaPlayer
        } catch (t: Throwable) {
            runCatching { mediaPlayer.release() }
            throw t
        }
    }

    private fun playbackFailureMessage(file: File): String =
        if (file.extension.equals("m4a", ignoreCase = true)) {
            "这段录音无法解码，可能是旧录音没有完整保存。可以尝试点“外部播放”，或重新录一段验证。"
        } else {
            "这段录音文件无法播放，可能已损坏或格式不支持。"
        }

    private fun playWavSegment(session: HistorySession, file: File, segment: TranscriptSegment) {
        stop()
        activeSegments = session.segments
        val sampleRate = WavIo.pcm16MonoSampleRate(file)
        val sampleCount = WavIo.pcm16MonoSampleCount(file)
        require(sampleCount > 0) { "录音文件没有可播放的音频数据" }
        val mediaDurationMs = maxOf(sampleCount * 1000L / sampleRate, session.durationMs)
        val segmentStart = segment.startMs.coerceIn(0L, mediaDurationMs.coerceAtLeast(0L))
        val startMs = (segmentStart - LOOP_PADDING_MS).coerceAtLeast(0L)
        val endMs = loopEndFor(segment, segmentStart, mediaDurationMs)
        require(endMs > startMs) { "这句话没有可循环的时间" }

        val startSample = startMs * sampleRate / 1000L
        val endSample = ((endMs * sampleRate + 999L) / 1000L).coerceAtMost(sampleCount)
        val samples = WavIo.readPcm16MonoRangeShort(file, startSample, (endSample - startSample).toInt())
        require(samples.isNotEmpty()) { "这句话的录音片段不可用" }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * AudioConstants.BYTES_PER_SAMPLE)
            .build()
        val written = track.write(samples, 0, samples.size)
        require(written > 0) { "录音片段准备失败" }
        runCatching { track.setLoopPoints(0, written, -1) }
        wavTrack = track
        wavSlice = WavSlice(
            samples = samples.copyOf(written),
            sampleRate = sampleRate,
            startMs = startMs,
            endMs = startMs + written * 1000L / sampleRate,
        )
        state = PlaybackState(
            sessionId = session.id,
            segmentId = segment.id,
            loopStartMs = wavSlice?.startMs ?: startMs,
            loopEndMs = wavSlice?.endMs ?: endMs,
            positionMs = wavSlice?.startMs ?: startMs,
            durationMs = mediaDurationMs,
            isPlaying = false,
            speed = state.speed,
        )
        applySpeed()
        track.play()
        state = state.copy(isPlaying = true)
        publish()
        scheduleTick()
    }

    private fun setLoopSegment(segment: TranscriptSegment) {
        val duration = state.durationMs.coerceAtLeast(0L)
        val segmentStart = segment.startMs.coerceIn(0L, duration.coerceAtLeast(0L))
        val start = (segmentStart - LOOP_PADDING_MS).coerceAtLeast(0L)
        val end = loopEndFor(segment, segmentStart, duration)
        require(end > start) { "这句话没有可循环的时间" }
        state = state.copy(
            segmentId = segment.id,
            loopStartMs = start,
            loopEndMs = end,
            positionMs = start,
        )
        seekToInternal(start)
    }

    private fun loopEndFor(segment: TranscriptSegment, startMs: Long, mediaDurationMs: Long): Long {
        val index = activeSegments.indexOfFirst { it.id == segment.id }
        val nextStart = activeSegments.getOrNull(index + 1)
            ?.startMs
            ?.takeIf { it > startMs }
        val ownEnd = segment.endMs.takeIf { it > startMs }
        val rawEnd = when {
            ownEnd != null && nextStart != null -> minOf(ownEnd, nextStart)
            ownEnd != null -> ownEnd
            nextStart != null -> nextStart
            else -> startMs + DEFAULT_LOOP_MS
        }
        val paddedEnd = rawEnd + LOOP_PADDING_MS
        val capped = if (mediaDurationMs > startMs) paddedEnd.coerceAtMost(mediaDurationMs) else paddedEnd
        return capped.coerceAtLeast(startMs + MIN_LOOP_MS)
    }

    private fun seekToInternal(positionMs: Long) {
        wavTrack?.let { track ->
            val slice = wavSlice ?: return
            val target = positionMs.coerceIn(state.loopStartMs, state.loopEndMs)
            val targetFrame = ((target - slice.startMs) * slice.sampleRate / 1000L)
                .coerceIn(0L, (slice.samples.size - 1).coerceAtLeast(0).toLong())
                .toInt()
            val wasPlaying = state.isPlaying && track.playState == AudioTrack.PLAYSTATE_PLAYING
            runCatching {
                if (wasPlaying) track.pause()
                track.setPlaybackHeadPosition(targetFrame)
                if (wasPlaying) track.play()
            }
            state = state.copy(positionMs = slice.startMs + targetFrame * 1000L / slice.sampleRate)
            return
        }
        val mediaPlayer = player ?: return
        val duration = state.durationMs.coerceAtLeast(0L)
        val target = if (state.hasLoop) {
            positionMs.coerceIn(state.loopStartMs, state.loopEndMs)
        } else {
            positionMs.coerceIn(0L, duration)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } else {
            @Suppress("DEPRECATION")
            mediaPlayer.seekTo(target.toInt())
        }
        state = state.copy(positionMs = target, segmentId = state.segmentId ?: segmentIdAt(target))
    }

    private fun currentIndex(): Int =
        activeSegments.indexOfFirst { it.id == state.segmentId }.takeIf { it >= 0 }
            ?: activeSegments.indexOfLast { it.startMs <= state.positionMs }.coerceAtLeast(0)

    private fun segmentIdAt(positionMs: Long): String? {
        if (activeSegments.isEmpty()) return null
        return activeSegments.firstOrNull { seg ->
            val end = if (seg.endMs > seg.startMs) seg.endMs else seg.startMs + 1
            positionMs in seg.startMs until end
        }?.id ?: activeSegments.lastOrNull { it.startMs <= positionMs }?.id ?: activeSegments.firstOrNull()?.id
    }

    private fun applySpeed() {
        wavTrack?.let { track ->
            val slice = wavSlice ?: return
            val rate = (slice.sampleRate * state.speed).roundToInt().coerceAtLeast(1)
            runCatching { track.playbackRate = rate }
            return
        }
        val mediaPlayer = player ?: return
        runCatching {
            mediaPlayer.playbackParams = PlaybackParams().setSpeed(state.speed)
        }
    }

    private fun scheduleTick() {
        stopTick()
        val runnable = object : Runnable {
            override fun run() {
                wavTrack?.let { track ->
                    state = state.copy(
                        positionMs = currentWavPositionMs(),
                        isPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING,
                    )
                    publish()
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) handler.postDelayed(this, 120L)
                    return
                }
                val mediaPlayer = player ?: return
                var position = mediaPlayer.currentPosition.toLong()
                if (state.hasLoop && position >= state.loopEndMs) {
                    restartLoop()
                    position = state.positionMs
                }
                state = if (state.hasLoop) {
                    state.copy(positionMs = position, isPlaying = mediaPlayer.isPlaying)
                } else {
                    state.copy(
                        positionMs = position,
                        segmentId = segmentIdAt(position),
                        isPlaying = mediaPlayer.isPlaying,
                    )
                }
                publish()
                if (mediaPlayer.isPlaying) handler.postDelayed(this, 120L)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun scheduleLoopRestart() {
        stopLoopTimer()
        if (wavTrack != null) return
        val mediaPlayer = player ?: return
        if (!state.hasLoop || !mediaPlayer.isPlaying) return
        val position = mediaPlayer.currentPosition.toLong().coerceAtLeast(state.loopStartMs)
        val remaining = (state.loopEndMs - position).coerceAtLeast(1L)
        val speed = state.speed.coerceAtLeast(0.1f)
        val delay = (remaining / speed).toLong().coerceAtLeast(20L)
        val runnable = Runnable { restartLoop() }
        loopRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun restartLoop() {
        wavTrack?.let { track ->
            if (!state.hasLoop) return
            seekToInternal(state.loopStartMs)
            if (state.isPlaying && track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            state = state.copy(positionMs = state.loopStartMs, isPlaying = state.isPlaying)
            publish()
            return
        }
        val mediaPlayer = player ?: return
        if (!state.hasLoop) return
        seekToInternal(state.loopStartMs)
        if (state.isPlaying && !mediaPlayer.isPlaying) mediaPlayer.start()
        state = state.copy(positionMs = state.loopStartMs, isPlaying = mediaPlayer.isPlaying || state.isPlaying)
        publish()
        if (state.isPlaying) scheduleLoopRestart()
    }

    private fun stopTick() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun stopLoopTimer() {
        loopRunnable?.let { handler.removeCallbacks(it) }
        loopRunnable = null
    }

    private fun publish() {
        onState(state)
    }

    private fun currentWavPositionMs(): Long {
        val track = wavTrack ?: return state.positionMs
        val slice = wavSlice ?: return state.positionMs
        val frame = if (slice.samples.isNotEmpty()) {
            track.playbackHeadPosition.toLong().floorMod(slice.samples.size.toLong())
        } else {
            0L
        }
        return (slice.startMs + frame * 1000L / slice.sampleRate).coerceIn(slice.startMs, slice.endMs)
    }

    private fun Long.floorMod(other: Long): Long = ((this % other) + other) % other

    private companion object {
        const val DEFAULT_LOOP_MS = 1500L
        const val LOOP_PADDING_MS = 500L
        const val MIN_LOOP_MS = 300L
    }
}

private enum class HistoryFilter(val label: String) {
    ALL("全部"),
    TODAY("今天"),
    WEEK("近 7 天"),
    MONTH("近 30 天"),
    FAVORITE("收藏"),
}

private fun HistorySession.matches(query: String, filter: HistoryFilter): Boolean {
    val trimmed = query.trim()
    val queryMatched = trimmed.isBlank() ||
        title.contains(trimmed, ignoreCase = true) ||
        segments.any { it.text.contains(trimmed, ignoreCase = true) }
    if (!queryMatched) return false
    return when (filter) {
        HistoryFilter.ALL -> true
        HistoryFilter.TODAY -> createdAtMs >= startOfToday()
        HistoryFilter.WEEK -> createdAtMs >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        HistoryFilter.MONTH -> createdAtMs >= System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        HistoryFilter.FAVORITE -> favorite
    }
}

private fun startOfToday(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun openRecording(context: Context, path: String?) {
    val file = path?.let { File(it) }
    require(file != null && file.exists()) { "录音文件不存在" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, recordingMimeType(file))
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "播放录音"))
}

private fun recordingMimeType(file: File): String =
    if (file.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "audio/wav"

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

private fun fullDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
