package com.overmind.meetingscribe.asr.online

import android.util.Log
import com.overmind.meetingscribe.asr.ASREngine
import com.overmind.meetingscribe.asr.ASRListener
import com.overmind.meetingscribe.asr.EngineState
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.util.Net
import com.overmind.meetingscribe.util.RealtimeAudioQueue
import com.overmind.meetingscribe.util.shortsToLeBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 阿里百炼 DashScope realtime ASR (Paraformer-realtime / Fun-ASR-realtime) over WebSocket.
 *
 * Flow: connect (Authorization: bearer KEY) -> send run-task (text) -> await task-started ->
 * stream raw PCM binary -> send finish-task -> task-finished.
 *
 * NOTE: the DashScope *realtime* WS models do NOT support speaker diarization (only the offline
 * recording-file API does), so speaker is always UNKNOWN here. Switch to an online engine with
 * server-side diarization (讯飞/火山) or the offline engine if you need speaker labels.
 */
class AliFunAsrEngine(private val settings: AppSettings) : ASREngine {

    override val type = EngineType.ALI_FUNASR
    private var listener: ASRListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var pcmChannel: Channel<ByteArray>? = null
    private var started = CompletableDeferred<Unit>()
    private var taskId = newTaskId()

    override fun setListener(listener: ASRListener?) {
        this.listener = listener
    }

    override suspend fun init(): Result<Unit> = runCatching {
        require(settings.dashScopeApiKey.isNotBlank()) { "请先在设置中填写阿里百炼 DASHSCOPE_API_KEY" }
    }

    override fun startStreaming() {
        started = CompletableDeferred()
        taskId = newTaskId()
        val ch = RealtimeAudioQueue.channel<ByteArray>()
        pcmChannel = ch
        val request = Request.Builder()
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/inference/")
            .addHeader("Authorization", "bearer ${settings.dashScopeApiKey}")
            .addHeader("X-DashScope-DataInspection", "enable")
            .build()
        listener?.onState(EngineState.INITIALIZING)
        ws = Net.client.newWebSocket(request, socketListener())
        scope.launch { sender(ch) }
    }

    private fun runTaskMessage(): String {
        val header = JSONObject()
            .put("action", "run-task")
            .put("task_id", taskId)
            .put("streaming", "duplex")
        val parameters = JSONObject()
            .put("format", "pcm")
            .put("sample_rate", 16000)
            .put("disfluency_removal_enabled", false)
            .put("heartbeat", true)
        if (settings.dashScopeModel.endsWith("-v2")) {
            parameters.put("language_hints", JSONArray().put("zh"))
        }
        val payload = JSONObject()
            .put("task_group", "audio")
            .put("task", "asr")
            .put("function", "recognition")
            .put("model", settings.dashScopeModel)
            .put("input", JSONObject())
            .put("parameters", parameters)
        return JSONObject().put("header", header).put("payload", payload).toString()
    }

    private fun finishTaskMessage(): String {
        val header = JSONObject()
            .put("action", "finish-task")
            .put("task_id", taskId)
            .put("streaming", "duplex")
        return JSONObject().put("header", header).put("payload", JSONObject().put("input", JSONObject())).toString()
    }

    private suspend fun sender(ch: Channel<ByteArray>) {
        try {
            started.await()
            for (chunk in ch) {
                ws?.send(chunk.toByteString())
            }
            runCatching { ws?.send(finishTaskMessage()) }
        } catch (_: kotlinx.coroutines.CancellationException) {
        } catch (t: Throwable) {
            Log.e(TAG, "sender error", t)
        }
    }

    override fun feedAudio(pcm: ShortArray, length: Int) {
        pcmChannel?.trySend(shortsToLeBytes(pcm, length))
    }

    override fun stop() {
        pcmChannel?.close()
        scope.launch {
            delay(2000)
            runCatching { ws?.close(1000, "bye") }
            listener?.onState(EngineState.STOPPED)
        }
    }

    override fun release() {
        runCatching { pcmChannel?.close() }
        runCatching { ws?.cancel() }
        ws = null
        if (!started.isCompleted) started.cancel()
        runCatching { scope.cancel() }
        listener = null
    }

    private fun socketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runCatching { webSocket.send(runTaskMessage()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!started.isCompleted) started.complete(Unit)
            listener?.onError("阿里连接失败: ${t.message}", t)
        }
    }

    private fun handle(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optJSONObject("header")?.optString("event")) {
            "task-started" -> {
                if (!started.isCompleted) started.complete(Unit)
                listener?.onState(EngineState.LISTENING)
            }

            "result-generated" -> {
                val sentence = obj.optJSONObject("payload")
                    ?.optJSONObject("output")
                    ?.optJSONObject("sentence") ?: return
                val t = sentence.optString("text")
                if (t.isBlank()) return
                val bg = sentence.optLong("begin_time", 0)
                val ed = sentence.optLong("end_time", 0)
                if (sentence.optBoolean("sentence_end", false)) {
                    listener?.onSegment(
                        TranscriptSegment(
                            text = t,
                            speaker = TranscriptSegment.UNKNOWN_SPEAKER,
                            startMs = bg,
                            endMs = ed,
                        ),
                    )
                } else {
                    listener?.onPartial(t)
                }
            }

            "task-finished" -> {
                listener?.onState(EngineState.STOPPED)
                runCatching { ws?.close(1000, "done") }
            }

            "task-failed" -> {
                val msg = obj.optJSONObject("header")?.optString("error_message").orEmpty()
                listener?.onError("阿里识别失败: $msg")
            }
        }
    }

    private companion object {
        const val TAG = "AliFunAsrEngine"
        fun newTaskId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
