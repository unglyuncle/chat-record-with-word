package com.overmind.meetingscribe.ui.components

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.audio.AudioConstants
import com.overmind.meetingscribe.audio.LiveAudioSlice
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class SegmentLoopPlaybackState(
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
fun SegmentLoopPlaybackBar(
    sessionId: String,
    title: String,
    segments: List<TranscriptSegment>,
    state: SegmentLoopPlaybackState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val active = state.sessionId == sessionId && state.hasLoop
    val duration = if (active) state.loopDurationMs else 0L
    val position = if (active) {
        (state.positionMs - state.loopStartMs).coerceIn(0L, duration.coerceAtLeast(0L))
    } else {
        0L
    }
    val current = if (active) segments.firstOrNull { it.id == state.segmentId } else null

    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.let { "单句循环 · ${it.text.take(42)}" } ?: title,
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

fun nextLoopSpeed(current: Float): Float {
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    val index = speeds.indexOfFirst { abs(it - current) < 0.01f }
    return speeds[(index + 1).floorMod(speeds.size)]
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

class SegmentLoopPlaybackController(
    private val onState: (SegmentLoopPlaybackState) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var liveTrack: AudioTrack? = null
    private var liveSlice: LiveAudioSlice? = null
    private var tickRunnable: Runnable? = null
    private var loopRunnable: Runnable? = null
    private var state = SegmentLoopPlaybackState()
    private var activeSegments: List<TranscriptSegment> = emptyList()

    fun play(
        sessionId: String,
        recordingPath: String?,
        segments: List<TranscriptSegment>,
        segment: TranscriptSegment? = null,
    ) {
        require(segments.isNotEmpty()) { "当前没有可播放的文字" }
        val target = segment
            ?: activeSegments.firstOrNull { it.id == state.segmentId }
            ?: segments.first()
        val mediaPlayer = ensurePlayer(sessionId, recordingPath, segments)
        setLoopSegment(target)
        mediaPlayer.start()
        state = state.copy(isPlaying = true)
        applySpeed()
        publish()
        scheduleTick()
        scheduleLoopRestart()
    }

    fun playLive(
        sessionId: String,
        slice: LiveAudioSlice,
        segments: List<TranscriptSegment>,
        segment: TranscriptSegment,
    ) {
        require(slice.samples.isNotEmpty()) { "这句话还没有可播放的录音" }
        stop()
        activeSegments = segments
        liveSlice = slice
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(slice.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(slice.samples.size * AudioConstants.BYTES_PER_SAMPLE)
            .build()
        val written = audioTrack.write(slice.samples, 0, slice.samples.size)
        require(written == slice.samples.size) { "实时录音片段准备失败" }
        runCatching { audioTrack.setLoopPoints(0, written, -1) }
        liveTrack = audioTrack
        state = SegmentLoopPlaybackState(
            sessionId = sessionId,
            segmentId = segment.id,
            loopStartMs = slice.startMs,
            loopEndMs = slice.endMs,
            positionMs = slice.startMs,
            durationMs = slice.endMs,
            isPlaying = false,
            speed = state.speed,
        )
        applySpeed()
        audioTrack.play()
        state = state.copy(isPlaying = true)
        publish()
        scheduleTick()
    }

    fun togglePlayPause(sessionId: String, recordingPath: String?, segments: List<TranscriptSegment>) {
        liveTrack?.takeIf { state.sessionId == sessionId }?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                state = state.copy(isPlaying = false, positionMs = currentLivePositionMs())
                publish()
                stopTick()
            } else {
                if (state.positionMs >= state.loopEndMs) seekToInternal(state.loopStartMs)
                track.play()
                state = state.copy(isPlaying = true, positionMs = currentLivePositionMs())
                publish()
                scheduleTick()
            }
            return
        }
        val mediaPlayer = player.takeIf { state.sessionId == sessionId } ?: run {
            play(sessionId, recordingPath, segments, segments.firstOrNull())
            return
        }
        if (!state.hasLoop) {
            play(sessionId, recordingPath, segments, segments.firstOrNull())
            return
        }
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            state = state.copy(isPlaying = false, positionMs = mediaPlayer.currentPosition.toLong())
            publish()
            stopTick()
            stopLoopTimer()
        } else {
            if (state.positionMs >= state.loopEndMs) seekToInternal(state.loopStartMs)
            mediaPlayer.start()
            state = state.copy(isPlaying = true, positionMs = mediaPlayer.currentPosition.toLong())
            publish()
            scheduleTick()
            scheduleLoopRestart()
        }
    }

    fun seekTo(positionMs: Long) {
        if (player == null && liveTrack == null) return
        seekToInternal(positionMs)
        publish()
        if (state.isPlaying) scheduleLoopRestart()
    }

    fun previous(sessionId: String, recordingPath: String?, segments: List<TranscriptSegment>) {
        if (player == null || state.sessionId != sessionId) {
            play(sessionId, recordingPath, segments, segments.firstOrNull())
            return
        }
        val targetIndex = (currentIndex() - 1).coerceAtLeast(0)
        activeSegments.getOrNull(targetIndex)?.let { play(sessionId, recordingPath, segments, it) }
    }

    fun next(sessionId: String, recordingPath: String?, segments: List<TranscriptSegment>) {
        if (player == null || state.sessionId != sessionId) {
            play(sessionId, recordingPath, segments, segments.firstOrNull())
            return
        }
        val targetIndex = (currentIndex() + 1).coerceAtMost(activeSegments.lastIndex)
        activeSegments.getOrNull(targetIndex)?.let { play(sessionId, recordingPath, segments, it) }
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
        liveTrack?.let {
            runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() }
            runCatching { it.release() }
        }
        liveTrack = null
        liveSlice = null
        player?.let {
            runCatching {
                if (it.isPlaying) it.stop()
            }
            runCatching { it.release() }
        }
        player = null
        activeSegments = emptyList()
        state = SegmentLoopPlaybackState(speed = state.speed)
        publish()
    }

    fun release() {
        stop()
    }

    private fun ensurePlayer(
        sessionId: String,
        recordingPath: String?,
        segments: List<TranscriptSegment>,
    ): MediaPlayer {
        player?.takeIf { state.sessionId == sessionId }?.let {
            activeSegments = segments
            return it
        }
        stop()
        val file = recordingPath?.let { File(it) }
        require(file != null && file.exists()) { "录音文件不存在，请先停止录音保存文件" }
        activeSegments = segments
        val mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { completedPlayer ->
                stopTick()
                if (state.hasLoop) {
                    seekToInternal(state.loopStartMs)
                    completedPlayer.start()
                    state = state.copy(isPlaying = true)
                    publish()
                    scheduleTick()
                    scheduleLoopRestart()
                } else {
                    val duration = state.durationMs.coerceAtLeast(completedPlayer.duration.toLong())
                    state = state.copy(positionMs = duration, isPlaying = false, durationMs = duration)
                    publish()
                }
            }
        }
        player = mediaPlayer
        state = SegmentLoopPlaybackState(
            sessionId = sessionId,
            positionMs = 0L,
            durationMs = mediaPlayer.duration.toLong(),
            isPlaying = false,
            speed = state.speed,
        )
        applySpeed()
        publish()
        return mediaPlayer
    }

    private fun setLoopSegment(segment: TranscriptSegment) {
        val duration = state.durationMs.coerceAtLeast(0L)
        val segmentStart = segment.startMs.coerceIn(0L, duration.coerceAtLeast(0L))
        val start = (segmentStart - LOOP_PADDING_MS).coerceAtLeast(0L)
        val end = loopEndFor(segment, segmentStart, duration)
        require(end > start) { "这句话没有可播放的时间" }
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
        val nextStart = activeSegments.getOrNull(index + 1)?.startMs?.takeIf { it > startMs }
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
        liveTrack?.let { track ->
            val slice = liveSlice ?: return
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
        state = state.copy(positionMs = target)
    }

    private fun currentIndex(): Int =
        activeSegments.indexOfFirst { it.id == state.segmentId }.takeIf { it >= 0 }
            ?: activeSegments.indexOfLast { it.startMs <= state.positionMs }.coerceAtLeast(0)

    private fun applySpeed() {
        liveTrack?.let { track ->
            val slice = liveSlice ?: return
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
                liveTrack?.let { track ->
                    state = state.copy(
                        positionMs = currentLivePositionMs(),
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
                state = state.copy(positionMs = position, isPlaying = mediaPlayer.isPlaying)
                publish()
                if (mediaPlayer.isPlaying) handler.postDelayed(this, 120L)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun scheduleLoopRestart() {
        stopLoopTimer()
        if (liveTrack != null) return
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
        liveTrack?.let { track ->
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

    private fun currentLivePositionMs(): Long {
        val track = liveTrack ?: return state.positionMs
        val slice = liveSlice ?: return state.positionMs
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
