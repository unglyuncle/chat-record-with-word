package com.overmind.meetingscribe.asr.online

import android.util.Log
import com.overmind.meetingscribe.asr.ASREngine
import com.overmind.meetingscribe.asr.ASRListener
import com.overmind.meetingscribe.asr.EngineState
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.util.Gzip
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
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 火山引擎 豆包 大模型流式语音识别 (bigmodel) over WebSocket.
 *
 * Binary protocol, big-endian: [4-byte header][optional int32 seq][uint32 payloadSize][payload].
 * Header bytes: 0x11 | (msgType<<4|flags) | 0x11(JSON|GZIP) | 0x00.
 *   msgType: 0x1 full-client-request, 0x2 audio-only, 0x9 full-server-response, 0xF error.
 * Flow: connect (header auth) -> send full-client-request (config, gzip) -> stream gzip(PCM)
 *       audio-only frames -> send last frame with NEG flag.
 *
 * NOTE: exact field names for 说话人分离 / 情绪 on the streaming endpoint are unconfirmed; this
 * scaffold parses an optional utterances[].speaker if present and otherwise reports UNKNOWN.
 */
class VolcDoubaoEngine(private val settings: AppSettings) : ASREngine {

    override val type = EngineType.VOLC_DOUBAO
    private var listener: ASRListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var pcmChannel: Channel<ByteArray>? = null
    private val seq = AtomicInteger(1)
    private var ready = CompletableDeferred<Unit>()

    override fun setListener(listener: ASRListener?) {
        this.listener = listener
    }

    override suspend fun init(): Result<Unit> = runCatching {
        require(settings.volcAppId.isNotBlank() && settings.volcAccessKey.isNotBlank()) {
            "请先在设置中填写火山 AppID 与 AccessKey"
        }
        require(settings.volcResourceId.isNotBlank()) { "缺少火山 ResourceId" }
    }

    override fun startStreaming() {
        ready = CompletableDeferred()
        seq.set(1)
        val ch = RealtimeAudioQueue.channel<ByteArray>()
        pcmChannel = ch
        val request = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")
            .addHeader("X-Api-App-Key", settings.volcAppId)
            .addHeader("X-Api-Access-Key", settings.volcAccessKey)
            .addHeader("X-Api-Resource-Id", settings.volcResourceId)
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        listener?.onState(EngineState.INITIALIZING)
        ws = Net.client.newWebSocket(request, socketListener())
        scope.launch { sender(ch) }
    }

    private fun configJson(): String = JSONObject()
        .put("user", JSONObject().put("uid", "meetingscribe"))
        .put("audio", JSONObject().put("format", "pcm").put("rate", 16000).put("bits", 16).put("channel", 1))
        .put(
            "request",
            JSONObject()
                .put("model_name", "bigmodel")
                .put("enable_itn", true)
                .put("enable_punc", true)
                .put("enable_ddc", false)
                .put("show_utterances", true)
                .put("result_type", "single")
                .put("vad_segment_duration", 800),
        )
        .toString()

    /** Builds one protocol frame. Sets sequence + JSON|GZIP serialization byte to match the demo. */
    private fun frame(messageType: Int, flags: Int, seqNum: Int, payload: ByteArray): ByteArray {
        val hasSeq = flags != 0x0
        val size = 4 + (if (hasSeq) 4 else 0) + 4 + payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(((0x1 shl 4) or 0x1).toByte())            // protocol version | header size(=1)
            put(((messageType shl 4) or flags).toByte())  // message type | flags
            put(((0x1 shl 4) or 0x1).toByte())            // serialization JSON | compression GZIP
            put(0x00)                                      // reserved
            if (hasSeq) putInt(seqNum)
            putInt(payload.size)
            put(payload)
        }.array()
    }

    private suspend fun sender(ch: Channel<ByteArray>) {
        try {
            ready.await()
            for (chunk in ch) {
                val frame = frame(
                    messageType = 0x2,
                    flags = 0x1, // POS_SEQUENCE
                    seqNum = seq.getAndIncrement(),
                    payload = Gzip.compress(chunk),
                )
                ws?.send(frame.toByteString())
            }
            // last packet: NEG_WITH_SEQUENCE (negative sequence) signals end of audio
            val last = frame(0x2, 0x3, -seq.getAndIncrement(), Gzip.compress(ByteArray(0)))
            ws?.send(last.toByteString())
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
        if (!ready.isCompleted) ready.cancel()
        runCatching { scope.cancel() }
        listener = null
    }

    private fun socketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runCatching {
                val config = frame(0x1, 0x1, seq.getAndIncrement(), Gzip.compress(configJson().toByteArray()))
                webSocket.send(config.toByteString())
                if (!ready.isCompleted) ready.complete(Unit)
                listener?.onState(EngineState.LISTENING)
            }.onFailure {
                if (!ready.isCompleted) ready.complete(Unit)
                listener?.onError("火山初始化失败: ${it.message}", it)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = parseFrame(bytes.toByteArray())
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(Unit)
            listener?.onError("火山连接失败: ${t.message}", t)
        }
    }

    private fun parseFrame(data: ByteArray) {
        if (data.size < 4) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val b0 = bb.get().toInt()
        val b1 = bb.get().toInt()
        bb.get() // serialization | compression
        bb.get() // reserved
        val headerSize = b0 and 0x0F
        val messageType = (b1 shr 4) and 0x0F
        val flags = b1 and 0x0F
        val compression = (data[2].toInt() shr 0) and 0x0F
        val extra = headerSize * 4 - 4
        if (extra > 0 && bb.remaining() >= extra) bb.position(bb.position() + extra)

        try {
            when (messageType) {
                0x9 -> { // full server response
                    if (flags != 0 && bb.remaining() >= 4) bb.int // sequence
                    if (bb.remaining() < 4) return
                    val payloadSize = bb.int
                    val n = payloadSize.coerceIn(0, bb.remaining())
                    val payload = ByteArray(n).also { bb.get(it) }
                    val json = if (compression == 0x1) Gzip.decompress(payload) else payload
                    parseServerJson(String(json, Charsets.UTF_8))
                }

                0xF -> { // error
                    val code = if (bb.remaining() >= 4) bb.int else -1
                    val msgSize = if (bb.remaining() >= 4) bb.int else 0
                    val n = msgSize.coerceIn(0, bb.remaining())
                    val raw = ByteArray(n).also { bb.get(it) }
                    val msg = runCatching { if (compression == 0x1) Gzip.decompress(raw) else raw }.getOrDefault(raw)
                    listener?.onError("火山错误[$code]: ${String(msg, Charsets.UTF_8)}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "parse error", t)
        }
    }

    private fun parseServerJson(json: String) {
        val result = runCatching { JSONObject(json) }.getOrNull()?.optJSONObject("result") ?: return
        val utterances = result.optJSONArray("utterances")
        if (utterances != null && utterances.length() > 0) {
            for (i in 0 until utterances.length()) {
                val u = utterances.getJSONObject(i)
                val t = u.optString("text")
                if (t.isBlank()) continue
                val speaker = u.optInt("speaker", TranscriptSegment.UNKNOWN_SPEAKER)
                if (u.optBoolean("definite", false)) {
                    listener?.onSegment(
                        TranscriptSegment(
                            text = t,
                            speaker = speaker,
                            startMs = u.optLong("start_time", 0),
                            endMs = u.optLong("end_time", 0),
                        ),
                    )
                } else {
                    listener?.onPartial(t)
                }
            }
        } else {
            result.optString("text").takeIf { it.isNotBlank() }?.let { listener?.onPartial(it) }
        }
    }

    private companion object {
        const val TAG = "VolcDoubaoEngine"
    }
}
